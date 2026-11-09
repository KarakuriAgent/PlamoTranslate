#!/usr/bin/env bash
# sherpa-onnxのAAR（ONNX Runtimeを静的に組み込んだ版）を third_party/sherpa-onnx/ へ取得し、SHA-256で照合する。
# GitHub ActionsとLinux・macOSでのビルド用（docs/06_開発環境.md「CI」）。Windowsでは tools/get-sherpa-onnx.ps1 を使う。
#
# 版とハッシュは tools/get-sherpa-onnx.ps1 の `$version = "..."` と、AARの Get-Verified の行から読む。
# 値を2か所に書かないためで、版を変えるときは get-sherpa-onnx.ps1 と gradle/libs.versions.toml の sherpa-onnx だけを直す。
# 音声認識のモデル（-Models・-Qwen3Asr）はアプリのビルドに要らないので、ここでは取らない。
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ps1="$repo/tools/get-sherpa-onnx.ps1"
toml="$repo/gradle/libs.versions.toml"

version="$(sed -n 's/^\$version = "\([^"]*\)".*/\1/p' "$ps1" | head -n 1)"
# AARの行：Get-Verified "$base/v$version/$aar" ... "<SHA-256>"
sha256="$(grep -F 'Get-Verified "$base/v$version/$aar"' "$ps1" | grep -oE '[0-9A-Fa-f]{64}' | head -n 1 | tr 'A-F' 'a-f')"
if [[ -z "$version" || -z "$sha256" ]]; then
    echo "エラー: $ps1 から版とハッシュを読めません（書き方が変わった？）" >&2
    exit 1
fi

# Gradleが読むAARの名前は libs.versions.toml の版から作られるので、ずれていたら止める
toml_version="$(sed -n 's/^sherpa-onnx = "\([^"]*\)".*/\1/p' "$toml" | head -n 1)"
if [[ "$version" != "$toml_version" ]]; then
    echo "エラー: get-sherpa-onnx.ps1 の版（$version）と libs.versions.toml の sherpa-onnx（$toml_version）が違います" >&2
    exit 1
fi

aar="sherpa-onnx-static-link-onnxruntime-$version.aar"
url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/$aar"
dest="$repo/third_party/sherpa-onnx/$aar"

sha_of() { sha256sum "$1" | cut -d ' ' -f 1; }

if [[ -f "$dest" && "$(sha_of "$dest")" == "$sha256" ]]; then
    echo "AAR: third_party/sherpa-onnx/$aar（取得済み、ハッシュ一致）"
    exit 0
fi

mkdir -p "$(dirname "$dest")"
curl -fsSL --retry 3 -o "$dest.part" "$url"
actual="$(sha_of "$dest.part")"
if [[ "$actual" != "$sha256" ]]; then
    rm -f "$dest.part"
    echo "エラー: ハッシュが違います: $aar（想定 $sha256、実際 $actual）" >&2
    exit 1
fi
mv "$dest.part" "$dest"
echo "AAR: third_party/sherpa-onnx/$aar（ハッシュ一致）"
