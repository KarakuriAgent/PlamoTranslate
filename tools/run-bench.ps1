<#
.SYNOPSIS
  画面翻訳の評価を端末で回し、結果をPCへ取り出して採点する（docs/05_画面翻訳.md「評価」）。

.DESCRIPTION
  1. tools/testdata/ocr/<Set>/ の画像を端末のアプリ領域 bench/in へ送る（前回分は消して入れ直す）
  2. デバッグビルドの BenchActivity を起動し、完了をlogcatで待つ
  3. bench/out を tools/testdata/ocr/<Set>-out/<Label>/ へ取り出す
  4. truth.json があれば tools/eval-screen.py で採点する

  画像と結果は著作物を含むためGitに入れない（.gitignore の /tools/testdata/ocr/）。

.EXAMPLE
  ./tools/run-bench.ps1 -Set 2026-11-05 -Label baseline
#>
param(
    [string]$Set = "2026-11-05",
    [string]$Label = (Get-Date -Format "HHmmss"),
    # 原文の言語（AUTO・CHINESE・KOREAN・LATIN。改名前の ENGLISH も LATIN として読む）。浮遊ボタンの長押しで選ぶのと同じ
    [string]$Lang = "AUTO"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$src = Join-Path $repo "tools\testdata\ocr\$Set"
$out = Join-Path $repo "tools\testdata\ocr\$Set-out\$Label"
$remote = "/sdcard/Android/data/app.plamotranslate/files/bench"

& $adb shell "rm -rf $remote/in $remote/out; mkdir -p $remote/in" | Out-Null
Get-ChildItem $src -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png)$' } | ForEach-Object {
    & $adb push $_.FullName "$remote/in/" | Out-Null
}
# REDMAGICは既定で全ログを止めているので、アプリのタグだけ開ける（再起動で戻る）
& $adb shell setprop log.tag.PlamoTranslate V
& $adb logcat -c
& $adb shell am start -S -n app.plamotranslate/.BenchActivity --es lang $Lang | Out-Null

$deadline = (Get-Date).AddMinutes(15)
do {
    Start-Sleep 3
    $done = & $adb logcat -d -s PlamoTranslate:I | Select-String 'ベンチ: (完了|画像がありません|翻訳モデル)'
} until ($done -or (Get-Date) -gt $deadline)
& $adb logcat -d -s PlamoTranslate:I | Select-String 'ベンチ:' | ForEach-Object { $_.Line -replace '^.*PlamoTranslate: ', '' }

New-Item -ItemType Directory -Force $out | Out-Null
& $adb pull "$remote/out/." $out | Out-Null
$truth = Join-Path $src "truth.json"
if (Test-Path $truth) {
    $env:PYTHONUTF8 = "1"
    & python (Join-Path $repo "tools\eval-screen.py") (Join-Path $out "results.jsonl") $truth
}
"結果: $out"
