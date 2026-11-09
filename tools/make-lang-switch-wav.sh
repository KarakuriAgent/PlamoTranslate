#!/usr/bin/env bash
# 言語の切り替えを試す音声（日本語とドイツ語をつないだ16kHz・モノラル・16bitのWAV）を作る。
# Android標準の端末内音声認識の試し（SpeechProbeActivity、docs/04_会話翻訳.md「音声認識の候補」）で流す。
#
#   ./tools/make-lang-switch-wav.sh <出力先のフォルダー>
#
# 素材はsherpa-onnxのモデルに付く試験音声で、コミットとSHA-256を固定して取る。
#   日本語：SenseVoice（csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17）の test_wavs/ja.wav
#   ドイツ語：Qwen3-ASR（csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25）の test_wavs/de.wav
# 各素材の前後の無音を切り（0.05秒は残す）、「日本語→ドイツ語→日本語→ドイツ語」の順につなぐ。
#   ja-de-gap.wav   ：区間の間に1秒の無音を挟む
#   ja-de-nogap.wav ：区間の間に無音を挟まない
# どちらも末尾に2秒の無音を足す（最後の区間の確定を待つため）。各区間の言語と開始・終了の秒は
# segments.tsv に書き、画面にも出す（切り替えの遅れを測る基準）。
# 音声はGitに入れない。出力先はリポジトリの外（PCの作業用フォルダーなど）か tools/testdata/（.gitignore済み）にする。
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "使い方: $0 <出力先のフォルダー>" >&2
    exit 1
fi
out="$1"
mkdir -p "$out/src"

sense_voice="https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
qwen3_asr="https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"

# 2026-11-09に取得して確かめたハッシュ
get() {
    local url="$1" dest="$2" sha256="$3"
    if [[ ! -f "$dest" || "$(sha256sum "$dest" | cut -d ' ' -f 1)" != "$sha256" ]]; then
        curl -fsSL --retry 3 -o "$dest.part" "$url"
        local actual
        actual="$(sha256sum "$dest.part" | cut -d ' ' -f 1)"
        if [[ "$actual" != "$sha256" ]]; then
            rm -f "$dest.part"
            echo "エラー: ハッシュが違います: $url（想定 $sha256、実際 $actual）" >&2
            exit 1
        fi
        mv "$dest.part" "$dest"
    fi
}
get "$sense_voice/test_wavs/ja.wav" "$out/src/ja.wav" 460bd8dccb0d2a5f4e29c628f837be4082d13defc64c3fc21dd1b6bb0e119095
get "$qwen3_asr/test_wavs/de.wav" "$out/src/de.wav" 80bb10c44085a7ce01a17abaf6a2095ed37e1695fca41cc0ea9733f1f24a749c

# 素材のフォルダーから読み込まないよう、-I で標準のモジュールだけを使う
python3 -I - "$out" <<'PY'
import array
import sys
import wave
from pathlib import Path

out = Path(sys.argv[1])
RATE = 16000
ORDER = ["ja", "de", "ja", "de"]
GAP_SEC = 1.0
TAIL_SEC = 2.0
MARGIN_SEC = 0.05


def read(path: Path) -> array.array:
    with wave.open(str(path), "rb") as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (RATE, 1, 2), f"16kHz・モノラル・16bitではありません: {path}"
        samples = array.array("h", w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        samples.byteswap()
    return samples


def trim(samples: array.array) -> array.array:
    """20msごとの最大振幅がしきい値を超える最初と最後の区切りまでを残す。"""
    frame = RATE // 50
    loud = [i for i in range(0, len(samples), frame) if max(abs(s) for s in samples[i:i + frame]) > 1000]
    if not loud:
        return samples
    margin = int(MARGIN_SEC * RATE)
    return samples[max(0, loud[0] - margin):min(len(samples), loud[-1] + frame + margin)]


def write(path: Path, samples: array.array) -> None:
    data = array.array("h", samples)
    if sys.byteorder == "big":
        data.byteswap()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


clips = {lang: trim(read(out / "src" / f"{lang}.wav")) for lang in set(ORDER)}
rows = ["file\tindex\tlang\tstart_sec\tend_sec"]
for name, gap in [("ja-de-gap.wav", GAP_SEC), ("ja-de-nogap.wav", 0.0)]:
    samples = array.array("h")
    for i, lang in enumerate(ORDER):
        if i > 0:
            samples.extend([0] * int(gap * RATE))
        start = len(samples) / RATE
        samples.extend(clips[lang])
        rows.append(f"{name}\t{i + 1}\t{lang}\t{start:.2f}\t{len(samples) / RATE:.2f}")
    samples.extend([0] * int(TAIL_SEC * RATE))
    write(out / name, samples)
    rows.append(f"{name}\t-\t(全体)\t0.00\t{len(samples) / RATE:.2f}")
(out / "segments.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
print("\n".join(rows))
PY
echo "出力: $out/ja-de-gap.wav, $out/ja-de-nogap.wav, $out/segments.tsv"
