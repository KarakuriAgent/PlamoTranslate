<#
.SYNOPSIS
  イテレーション0のスパイク：llama.cppのCLIとPLaMoのモデルを端末へ送り、端末上で翻訳と速度計測を行う。

.DESCRIPTION
  事前に build-android へ llama-completion と llama-bench をビルドし、
  models/ に plamo-3-translate-0.8b-Q4_K_M.gguf を置いておく（docs/06_開発環境.md）。
  端末は adb devices に1台だけ見えている状態にする。

.EXAMPLE
  ./tools/device-translate.ps1 -Text "내일 몇 시에 만날까요?" -Source ko -Target ja
  ./tools/device-translate.ps1 -Bench
#>
param(
    [string]$Text = "내일 몇 시에 만날까요?",
    [string]$Source = "ko",
    [string]$Target = "ja",
    [string]$Model = "plamo-3-translate-0.8b-Q4_K_M.gguf",
    [int]$Threads = 8,  # CLI単体では8が最速（2026-11-05）。アプリ内は6（docs/00_現在地.md）
    [switch]$Bench,
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$remote = "/data/local/tmp/plamo"

if (-not $SkipPush) {
    & $adb shell "mkdir -p $remote" | Out-Null
    foreach ($bin in "llama-completion", "llama-bench") {
        & $adb push (Join-Path $repo "build-android\bin\$bin") "$remote/" | Out-Null
        & $adb shell "chmod +x $remote/$bin" | Out-Null
    }
    # モデルは端末に同じ名前があれば送り直さない
    $exists = & $adb shell "ls $remote/$Model 2>/dev/null"
    if (-not $exists) {
        & $adb push (Join-Path $repo "models\$Model") "$remote/"
    }
}

if ($Bench) {
    & $adb shell "cd $remote && ./llama-bench -m $Model -p 128 -n 64 -t 4,6,8"
    return
}

# PFML3プロンプト（docs/03_翻訳コア.md）。改行を入れずに1行でつなぐ
$form = if ($Target -eq "ja") { "<|plamo:key|>form<|plamo:val|>polite" } else { "" }
$prompt = "<|plamo:tag|>dataset<|plamo:msg|>translation" +
          "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>$Source<|plamo:msg|>$Text" +
          "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>$Target$form<|plamo:msg|>"

# 端末のシェルへ渡すため、プロンプトはファイル経由にする（特殊文字の解釈を避ける）
$tmp = New-TemporaryFile
[System.IO.File]::WriteAllText($tmp, $prompt, (New-Object System.Text.UTF8Encoding $false))
& $adb push $tmp "$remote/prompt.txt" | Out-Null
Remove-Item $tmp

& $adb shell "cd $remote && ./llama-completion -m $Model -f prompt.txt -n 128 --temp 0 -t $Threads -no-cnv --no-display-prompt 2>&1"
