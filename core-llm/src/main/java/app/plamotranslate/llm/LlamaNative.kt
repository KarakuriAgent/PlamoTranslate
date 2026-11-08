package app.plamotranslate.llm

/** llama.cppのJNI入口。呼び出しは [TranslationEngine] が1本のスレッドに直列化する。 */
internal object LlamaNative {
    init {
        System.loadLibrary("plamo-llm")
    }

    /** 1トークン分の文字列（UTF-8、文字の途中で切れない）を受け取る。false を返すと生成を止める。 */
    fun interface TokenCallback {
        fun onToken(utf8: ByteArray): Boolean
    }

    /**
     * 計算部分のライブラリを [nativeLibDir] から読み込む。[loadGpu] が false ならCPUの部分だけ。
     * 最初の1回だけ有効で、GPUのデバイスが使えるようになったかを返す。[nativeLoad] より前に呼ぶ。
     */
    external fun nativeInit(nativeLibDir: String, loadGpu: Boolean, cpuVariant: String): Boolean

    /** 読み込んだCPUの計算部分のファイル名（libggml-cpu-*.so）。GPUの端末で自動選択したときは空。 */
    external fun nativeCpuBackend(): String

    /** 失敗したら 0 を返す。 */
    external fun nativeLoad(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int,
        poll: Int, useThreadPool: Boolean, useMmap: Boolean, boost: Boolean,
    ): Long
    external fun nativeFree(handle: Long)

    /** 生成したトークン数、または負のエラーコード（-1 トークン化失敗、-2 長すぎる、-3 推論失敗）。 */
    external fun nativeTranslate(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback): Int

    /** 計測用：性能ヒントの有無とスレッド数（0なら変えない）を切り替える。 */
    external fun nativeConfigure(handle: Long, boost: Boolean, nThreads: Int)
}
