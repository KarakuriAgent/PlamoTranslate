<#
.SYNOPSIS
  英語の画面翻訳を評価するための合成画面（1216×2688、REDMAGICの画面と同じ大きさ）と正解を作る。

.DESCRIPTION
  アプリの設定画面・課金の案内・警告文・チャット・小さな注記・通知・漫画風の大文字のセリフを並べる。
  各まとまりの位置と文字（正解）を truth.json に書く。本物のスクリーンショットではないので、
  実機の英語画面での確認と合わせて使う（docs/05_画面翻訳.md「評価」）。

.EXAMPLE
  ./tools/make-en-screen.ps1 -Out tools/testdata/ocr/en-synthetic
#>
param([string]$Out = "tools/testdata/ocr/en-synthetic")

Add-Type -AssemblyName System.Drawing
# .NET の保存はPowerShellの現在地を見ないので、絶対パスにしておく
$Out = [IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
New-Item -ItemType Directory -Force $Out | Out-Null
$W = 1216; $H = 2688
$bmp = New-Object Drawing.Bitmap $W, $H
$g = [Drawing.Graphics]::FromImage($bmp)
$g.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$g.Clear([Drawing.Color]::FromArgb(250, 250, 250))
$black = [Drawing.Brushes]::Black
$gray = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(110, 110, 110))
$white = [Drawing.Brushes]::White
$truth = [ordered]@{}
$items = New-Object System.Collections.ArrayList

function Put([string]$id, [string]$text, [string]$fontName, [float]$size, [Drawing.FontStyle]$style, $brush, [int]$x, [int]$y, [int]$w) {
    $font = New-Object Drawing.Font $fontName, $size, $style, ([Drawing.GraphicsUnit]::Pixel)
    $layout = New-Object Drawing.RectangleF $x, $y, $w, 2000
    $measured = $g.MeasureString($text, $font, $w)
    $g.DrawString($text, $font, $brush, $layout)
    $box = @($x, $y, [int]($x + $measured.Width), [int]($y + $measured.Height))
    [void]$items.Add([ordered]@{ id = $id; box = $box; text = $text })
    return [int]($y + $measured.Height)
}

# 上部のアプリバー
$g.FillRectangle((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(33, 99, 196))), 0, 120, $W, 150)
[void](Put "E1" "Settings" "Segoe UI" 56 ([Drawing.FontStyle]::Bold) $white 48 160 600)
$y = 320
$y = Put "E2" "Account" "Segoe UI" 40 ([Drawing.FontStyle]::Bold) $black 48 $y 1100
$y = Put "E3" "Change password" "Segoe UI" 46 ([Drawing.FontStyle]::Regular) $black 48 ($y + 30) 1100
$y = Put "E4" "Last changed 3 months ago" "Segoe UI" 34 ([Drawing.FontStyle]::Regular) $gray 48 ($y + 6) 1100
$y = Put "E5" "Your subscription will renew on December 1, 2026. You can cancel anytime from the billing page." "Segoe UI" 42 ([Drawing.FontStyle]::Regular) $black 48 ($y + 60) 1100
# ボタン
$g.FillRectangle((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(200, 40, 40))), 48, ($y + 50), 640, 110)
[void](Put "E6" "Cancel subscription" "Segoe UI" 44 ([Drawing.FontStyle]::Bold) $white 90 ($y + 80) 600)
$y = $y + 220
$y = Put "E7" "This action cannot be undone. All saved data will be permanently deleted." "Arial" 40 ([Drawing.FontStyle]::Italic) $black 48 $y 1100
# チャットの吹き出し
$g.FillRectangle((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(225, 235, 255))), 300, ($y + 70), 860, 230)
$y = Put "E8" "Are you coming to the party tonight? Let me know by 6 pm!" "Segoe UI" 44 ([Drawing.FontStyle]::Regular) $black 330 ($y + 100) 800
# 漫画風の大文字のセリフ
$g.FillEllipse($white, 120, ($y + 180), 700, 380)
$g.DrawEllipse((New-Object Drawing.Pen ([Drawing.Color]::Black), 5), 120, ($y + 180), 700, 380)
$y2 = Put "E9" "WAIT! DON'T GO! I STILL HAVE SOMETHING TO TELL YOU!" "Comic Sans MS" 46 ([Drawing.FontStyle]::Bold) $black 230 ($y + 270) 480
$y = [Math]::Max($y2, $y + 560)
$y = Put "E10" "Battery low: 15% remaining. Connect your charger soon." "Segoe UI" 38 ([Drawing.FontStyle]::Regular) $black 48 ($y + 80) 1100
$y = Put "E11" "Terms of Service  |  Privacy Policy  |  Help Center" "Segoe UI" 26 ([Drawing.FontStyle]::Regular) $gray 48 ($y + 80) 1100

$g.Dispose()
$name = "en_settings_chat.png"
$bmp.Save((Join-Path $Out $name), [Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

$truth[$name] = [ordered]@{
    note    = "合成した英語の画面（tools/make-en-screen.ps1）。各まとまりの位置と正しい文字"
    area    = @(0, 100, $W, $H)
    bubbles = $items
}
$truth | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Out "truth.json") -Encoding utf8
"作成: $(Join-Path $Out $name)（$($items.Count)まとまり）"
