// llama.cppのJNIラッパー（docs/03_翻訳コア.md）。
// Kotlin側の LlamaNative から呼ぶ。1つのハンドルを同時に複数スレッドから使わないこと（Kotlin側で直列化する）。
#include <android/log.h>
#include <android/performance_hint.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"

#define TAG "PlamoTranslate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// nativeTranslate の戻り値（負の値は失敗）
constexpr jint ERR_TOKENIZE = -1;
constexpr jint ERR_TOO_LONG = -2;
constexpr jint ERR_DECODE = -3;
constexpr jint ERR_CALLBACK = -4;

// 1トークンの生成にかけたい時間。性能ヒントでこれを目標として伝える（約80トークン/秒）
constexpr int64_t TARGET_TOKEN_NANOS = 12'000'000;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::vector<llama_token> stop_tokens;  // <|plamo:tag|>、<|plamo:bos|>
    int n_ctx = 0;

    // 常駐スレッドプール。毎回スレッドを作り直さず、性能ヒントの対象を固定できる
    ggml_threadpool *threadpool = nullptr;
    std::vector<int32_t> worker_tids;
    // 性能ヒント（ADPF）。別アプリが前面でもCPUの周波数・配置を上げてもらう
    bool boost = true;
    APerformanceHintSession *hint = nullptr;
    pid_t hint_caller = 0;
};

std::set<int32_t> list_threads() {
    std::set<int32_t> tids;
    if (DIR *dir = opendir("/proc/self/task")) {
        while (dirent *e = readdir(dir)) {
            if (e->d_name[0] != '.') tids.insert(atoi(e->d_name));
        }
        closedir(dir);
    }
    return tids;
}

std::string read_first_line(const std::string &path) {
    std::ifstream in(path);
    std::string line;
    std::getline(in, line);
    return line;
}

// 診断用：自分のプロセスのcpusetと、各コアの現在のクロック（MHz）
std::string cpu_state() {
    std::string s = "cpuset=" + read_first_line("/proc/self/cpuset") + " MHz=";
    for (int i = 0; i < 8; ++i) {
        std::string f = read_first_line("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/scaling_cur_freq");
        s += (i ? "," : "") + (f.empty() ? std::string("?") : std::to_string(atol(f.c_str()) / 1000));
    }
    return s;
}

// 呼び出し元スレッドが変わったら作り直す。作れない端末・状態では null のまま進める
void ensure_hint(Engine *e) {
    const pid_t caller = gettid();
    if (!e->boost) {
        if (e->hint) { APerformanceHint_closeSession(e->hint); e->hint = nullptr; }
        return;
    }
    if (e->hint && e->hint_caller == caller) return;
    if (e->hint) APerformanceHint_closeSession(e->hint);
    std::vector<int32_t> tids = e->worker_tids;
    tids.push_back(caller);
    e->hint = APerformanceHint_createSession(APerformanceHint_getManager(), tids.data(), tids.size(), TARGET_TOKEN_NANOS);
    e->hint_caller = caller;
    if (e->hint) {
        LOGI("性能ヒントを作りました（%zuスレッド）", tids.size());
    } else {
        LOGW("性能ヒントを作れませんでした");
    }
}

void report_hint(Engine *e, int64_t nanos) {
    if (e->hint) APerformanceHint_reportActualWorkDuration(e->hint, nanos);
}

// llama_decode を実行し、かかった時間を性能ヒントへ伝える
int decode(Engine *e, llama_batch batch) {
    const int64_t t0 = ggml_time_us();
    const int rc = llama_decode(e->ctx, batch);
    report_hint(e, (ggml_time_us() - t0) * 1000);
    return rc;
}

void log_callback(ggml_log_level level, const char *text, void *) {
    // llama.cppの情報ログは量が多いので、警告以上だけlogcatへ出す
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, TAG, text);
    }
}

std::once_flag g_backend_once;

// CPUの計算部分（libggml-cpu-*.so）は実行時に読み込むので、スレッドプールの関数も読み込んだ後に探す
using threadpool_new_fn = decltype(ggml_threadpool_new) *;
using threadpool_free_fn = decltype(ggml_threadpool_free) *;
threadpool_new_fn g_threadpool_new = nullptr;
threadpool_free_fn g_threadpool_free = nullptr;

// GPU（OpenCL）のデバイスが使えるようになったか。読み込めない端末では false
bool g_has_gpu = false;

// 読み込んだCPUの計算部分のファイル名（設定の画面に出す）
std::string g_cpu_backend;

void find_cpu_functions() {
    ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (!cpu) {
        LOGE("CPUのバックエンドが見つかりません");
        return;
    }
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(cpu);
    g_threadpool_new = (threadpool_new_fn) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
    g_threadpool_free = (threadpool_free_fn) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
}

std::string to_std_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    // parse_special を有効にしないと <|plamo:tag|> などが特殊トークンにならない
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n <= 0 ||
        llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true) < 0) {
        tokens.clear();
    }
    return tokens;
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n >= 0) return {buf, (size_t) n};
    std::string big(-n, '\0');
    n = llama_token_to_piece(vocab, token, big.data(), (int32_t) big.size(), 0, false);
    big.resize(std::max(n, 0));
    return big;
}

// 末尾の不完全なUTF-8の並びを除いた長さを返す。1文字が複数トークンに分かれる場合に備える
size_t complete_utf8_length(const std::string &s) {
    size_t len = s.size();
    // 末尾から最大3バイト戻って、文字の先頭バイトを探す
    for (size_t back = 1; back <= std::min<size_t>(4, len); ++back) {
        auto c = (unsigned char) s[len - back];
        if ((c & 0xC0) == 0x80) continue;  // 継続バイト
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
        return back >= need ? len : len - back;
    }
    return len;
}

}  // namespace

// アプリのネイティブライブラリの置き場（nativeLibraryDir）から、計算部分のライブラリを読み込む。
// load_gpu が false なら、GPU（OpenCL）の部分は読み込まずCPUだけにする。最初の1回だけ有効。
// 戻り値は、GPUのデバイスが使えるようになったか。
extern "C" JNIEXPORT jboolean JNICALL
Java_app_plamotranslate_llm_LlamaNative_nativeInit(JNIEnv *env, jobject, jstring jlib_dir, jboolean load_gpu,
                                                   jstring jcpu_variant) {
    const std::string lib_dir = to_std_string(env, jlib_dir);
    // 空なら自動。ファイル名（libggml-cpu-*.so）を指定すると、その版だけを候補にする（設定の「CPUの詳細」）
    const std::string cpu_variant = to_std_string(env, jcpu_variant);
    std::call_once(g_backend_once, [&] {
        llama_log_set(log_callback, nullptr);
        if (load_gpu) {
            // OpenCLの無い端末では libggml-opencl.so の読み込みだけが失敗し、CPUの部分は読み込まれる
            ggml_backend_load_all_from_path(lib_dir.c_str());
        } else {
            // CPUの部分だけ読み込む。世代ごとの版のうち、端末で動く中で一番点数の高いもの（ggml_backend_score）を選ぶ。
            // ggml_backend_load_all_from_path と同じ選び方を、GPUを除いて行う
            std::string best;
            int best_score = 0;
            if (DIR *dir = opendir(lib_dir.c_str())) {
                while (dirent *e = readdir(dir)) {
                    const std::string name = e->d_name;
                    if (name.rfind("libggml-cpu", 0) != 0) continue;
                    if (!cpu_variant.empty() && name != cpu_variant) continue;
                    const std::string full = lib_dir + "/" + name;
                    void *handle = dlopen(full.c_str(), RTLD_NOW | RTLD_LOCAL);
                    if (!handle) continue;
                    using score_fn = int (*)();
                    auto score = (score_fn) dlsym(handle, "ggml_backend_score");
                    const int s = score ? score() : 0;
                    dlclose(handle);
                    if (s > best_score) {
                        best_score = s;
                        best = full;
                    }
                }
                closedir(dir);
            }
            if (!best.empty() && ggml_backend_load(best.c_str())) {
                LOGI("CPUのバックエンド: %s（点数%d）", best.c_str(), best_score);
                g_cpu_backend = best.substr(best.find_last_of('/') + 1);
            } else {
                LOGE("CPUのバックエンドを読み込めませんでした: %s", lib_dir.c_str());
            }
        }
        llama_backend_init();
        find_cpu_functions();
        g_has_gpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) != nullptr;
        LOGI("計算のバックエンド: デバイス%zu個、GPU%s", ggml_backend_dev_count(), g_has_gpu ? "あり" : "なし");
    });
    return g_has_gpu ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_plamotranslate_llm_LlamaNative_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads,
                                                   jint n_gpu_layers, jint poll, jboolean use_pool, jboolean use_mmap,
                                                   jboolean boost) {
    const std::string path = to_std_string(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    // false なら、モデルをファイルの写像（mmap）ではなくメモリへ読み込む
    if (!use_mmap) mparams.load_mode = LLAMA_LOAD_MODE_NONE;
    // 0ならCPUだけ、99なら全層をGPU（OpenCL、Adreno）に載せる
    mparams.n_gpu_layers = n_gpu_layers;
    // CPUだけのときは、GPUのデバイスを一切使わない（計算の一部をGPUへ回す既定の動きも止める）
    static ggml_backend_dev_t no_devices[] = {nullptr};
    if (n_gpu_layers == 0) mparams.devices = no_devices;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("モデルを読めませんでした: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = n_ctx;  // プロンプトを1回のdecodeで処理する
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;
    if (n_gpu_layers == 0) cparams.op_offload = false;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("コンテキストを作れませんでした");
        llama_model_free(model);
        return 0;
    }

    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->n_ctx = n_ctx;
    engine->boost = boost;

    // スレッドプールを先に作って常駐させ、増えたスレッドを性能ヒントの対象として覚える
    const auto before = list_threads();
    ggml_threadpool_params tp_params = ggml_threadpool_params_default(n_threads);
    // use_pool が false なら作らず、llama.cppが推論のたびにスレッドを用意する既定の動きにする。
    // poll は仕事を待つ間に回して待つ度合い（0は眠って待つ、既定50）
    tp_params.poll = (uint32_t) poll;
    engine->threadpool = use_pool && g_threadpool_new ? g_threadpool_new(&tp_params) : nullptr;
    if (engine->threadpool) {
        llama_attach_threadpool(ctx, engine->threadpool, engine->threadpool);
        for (int32_t tid : list_threads()) {
            if (!before.count(tid)) engine->worker_tids.push_back(tid);
        }
    }
    engine->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(engine->sampler, llama_sampler_init_greedy());  // 温度0

    const llama_vocab *vocab = llama_model_get_vocab(model);
    for (const char *stop : {"<|plamo:tag|>", "<|plamo:bos|>"}) {
        auto t = tokenize(vocab, stop, false);
        if (t.size() == 1) {
            engine->stop_tokens.push_back(t[0]);
        } else {
            LOGW("停止トークン %s が1トークンになりません（%zu）", stop, t.size());
        }
    }
    LOGI("モデルを読みました: n_ctx=%d threads=%d gpu_layers=%d 常駐ワーカー=%zu poll=%d mmap=%d ヒント=%d %s", n_ctx,
         n_threads, n_gpu_layers, engine->worker_tids.size(), poll, use_mmap, boost, llama_print_system_info());
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_plamotranslate_llm_LlamaNative_nativeCpuBackend(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_cpu_backend.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_plamotranslate_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine) return;
    if (engine->hint) APerformanceHint_closeSession(engine->hint);
    llama_sampler_free(engine->sampler);
    llama_free(engine->ctx);
    if (engine->threadpool && g_threadpool_free) g_threadpool_free(engine->threadpool);
    llama_model_free(engine->model);
    delete engine;
}

// プロンプトを処理し、生成した文字列を少しずつ callback.onToken(byte[]) へ渡す（UTF-8）。
// onToken が false を返したら中断する。戻り値は生成したトークン数、または負のエラーコード。
extern "C" JNIEXPORT jint JNICALL
Java_app_plamotranslate_llm_LlamaNative_nativeTranslate(JNIEnv *env, jobject, jlong handle, jstring jprompt,
                                                        jint max_tokens, jobject callback) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_class, "onToken", "([B)Z");
    if (!on_token) return ERR_CALLBACK;

    ensure_hint(engine);

    // 翻訳ごとに独立させるため、前回のKVキャッシュを捨てる
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    llama_sampler_reset(engine->sampler);

    // 先頭のBOSは add_special=true でllama.cppが付ける
    std::vector<llama_token> tokens = tokenize(vocab, to_std_string(env, jprompt), true);
    if (tokens.empty()) return ERR_TOKENIZE;
    const int n_prompt = (int) tokens.size();
    if (n_prompt >= engine->n_ctx) return ERR_TOO_LONG;
    const int n_predict = std::min<int>(max_tokens, engine->n_ctx - n_prompt);

    const int64_t t_start = ggml_time_us();
    if (decode(engine, llama_batch_get_one(tokens.data(), n_prompt)) != 0) return ERR_DECODE;
    const int64_t t_prompt = ggml_time_us();

    std::string pending;
    int n_generated = 0;
    while (n_generated < n_predict) {
        llama_token token = llama_sampler_sample(engine->sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(vocab, token) ||
            std::find(engine->stop_tokens.begin(), engine->stop_tokens.end(), token) != engine->stop_tokens.end()) {
            break;
        }
        ++n_generated;

        pending += token_to_piece(vocab, token);
        const size_t ready = complete_utf8_length(pending);
        if (ready > 0) {
            jbyteArray bytes = env->NewByteArray((jsize) ready);
            env->SetByteArrayRegion(bytes, 0, (jsize) ready, reinterpret_cast<const jbyte *>(pending.data()));
            const jboolean keep_going = env->CallBooleanMethod(callback, on_token, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !keep_going) break;
        }

        if (decode(engine, llama_batch_get_one(&token, 1)) != 0) return ERR_DECODE;
    }
    const int64_t t_end = ggml_time_us();
    LOGI("翻訳: プロンプト %d トークン %.0fms、生成 %d トークン %.0fms（%.1f トークン/秒） ヒント=%s threads=%d %s",
         n_prompt, (t_prompt - t_start) / 1000.0, n_generated, (t_end - t_prompt) / 1000.0,
         n_generated > 0 ? n_generated * 1e6 / (double) (t_end - t_prompt) : 0.0,
         engine->hint ? "有" : "無", llama_n_threads(engine->ctx), cpu_state().c_str());
    return n_generated;
}

// 計測用の切り替え口：性能ヒントの有無とスレッド数を変える（デバッグビルドの切り替えから呼ぶ）
extern "C" JNIEXPORT void JNICALL
Java_app_plamotranslate_llm_LlamaNative_nativeConfigure(JNIEnv *, jobject, jlong handle, jboolean boost, jint n_threads) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->boost = boost;
    if (n_threads > 0) llama_set_n_threads(engine->ctx, n_threads, n_threads);
    LOGI("設定変更: ヒント=%s threads=%d", boost ? "有" : "無", llama_n_threads(engine->ctx));
}
