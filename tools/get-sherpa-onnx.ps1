<#
.SYNOPSIS
  会話翻訳の音声認識に使うsherpa-onnxのAARと、音声認識のモデルを取得する（docs/06_開発環境.md）。

.DESCRIPTION
  1. sherpa-onnxのAAR（ONNX Runtimeを静的に組み込んだ版）を third_party/sherpa-onnx/ へ置く。ビルドに要る
  2. -Models を付けると、SenseVoice Small（int8）とSilero VADを models/asr/ へ置く。端末へはadbで送る

  どれもGitに入れない（.gitignore）。版は gradle/libs.versions.toml の sherpa-onnx に合わせる。

.EXAMPLE
  ./tools/get-sherpa-onnx.ps1 -Models
#>
param([switch]$Models)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$repo = Split-Path $PSScriptRoot -Parent
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

function Get-Verified($url, $path, $sha256) {
    if ((Test-Path $path) -and (Get-FileHash $path -Algorithm SHA256).Hash -eq $sha256) { return }
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    Invoke-WebRequest $url -OutFile $path
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash
    if ($actual -ne $sha256) { Remove-Item $path; throw "ハッシュが違います: $path ($actual)" }
}

# 2026-11-06に取得して確かめたハッシュ
$version = "2.0.1"
$aar = "sherpa-onnx-static-link-onnxruntime-$version.aar"
Get-Verified "$base/v$version/$aar" (Join-Path $repo "third_party\sherpa-onnx\$aar") "07E042D97F3A6BD5B7FE74D4EEF0513DC01A714B16C15A903250095ABA43A575"
"AAR: third_party\sherpa-onnx\$aar"

if ($Models) {
    $asr = Join-Path $repo "models\asr"
    Get-Verified "$base/asr-models/silero_vad.onnx" (Join-Path $asr "silero_vad.onnx") "9E2449E1087496D8D4CABA907F23E0BD3F78D91FA552479BB9C23AC09CBB1FD6"
    # 元のSenseVoice Small（FunAudioLLM）。2025-09-09版は広東語向けに追加学習した派生（WSYue-ASR）なので使わない
    $sv = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    $model = Join-Path $asr "$sv\model.int8.onnx"
    if (-not (Test-Path $model)) {
        $tar = Join-Path $env:TEMP "$sv.tar.bz2"
        Invoke-WebRequest "$base/asr-models/$sv.tar.bz2" -OutFile $tar
        tar -xjf $tar -C $asr
        Remove-Item $tar
    }
    if ((Get-FileHash $model -Algorithm SHA256).Hash -ne "C71F0CE00BEC95B07744E116345E33D8CBBE08CEF896382CF907BF4B51A2CD51") {
        throw "ハッシュが違います: $model"
    }
    "モデル: $asr（端末へは docs/06_開発環境.md「モデルの置き方」の手順で送る）"
}
