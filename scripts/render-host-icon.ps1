[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

# Convert the existing Android vector logo; no external graphics tools needed.
# Windows PowerShell's STA thread is required by WPF's renderer.
if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    throw 'Run with Windows PowerShell: powershell.exe -NoProfile -STA -File scripts\render-host-icon.ps1'
}
Add-Type -AssemblyName PresentationCore, WindowsBase
$projectRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $projectRoot 'app\src\main\res\drawable\ic_launcher.xml'
$iconPath = Join-Path $projectRoot 'windows\Virkey.Host\Assets\virkey.ico'
$previewPath = Join-Path $projectRoot 'docs\previews\virkey-logo.png'
[xml]$vector = Get-Content -Raw -Encoding utf8 -LiteralPath $source
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$viewportWidth = [double]::Parse($vector.vector.GetAttribute('viewportWidth', $androidNamespace), [Globalization.CultureInfo]::InvariantCulture)
$viewportHeight = [double]::Parse($vector.vector.GetAttribute('viewportHeight', $androidNamespace), [Globalization.CultureInfo]::InvariantCulture)
if ($viewportWidth -le 0 -or $viewportHeight -le 0) { throw 'Invalid vector viewport.' }
foreach ($child in $vector.vector.ChildNodes) {
    if ($child.NodeType -eq [Xml.XmlNodeType]::Element -and $child.LocalName -ne 'path') {
        throw "Unsupported vector element: $($child.LocalName)"
    }
}

$sizes = @(16, 20, 24, 32, 40, 48, 64, 128, 256)
$frames = [Collections.Generic.List[byte[]]]::new()
foreach ($size in $sizes) {
    $visual = [Windows.Media.DrawingVisual]::new()
    $drawing = $visual.RenderOpen()
    try {
        $drawing.PushTransform([Windows.Media.ScaleTransform]::new($size / $viewportWidth, $size / $viewportHeight))
        foreach ($path in $vector.vector.path) {
            $geometry = [Windows.Media.PathGeometry]::CreateFromGeometry(
                [Windows.Media.Geometry]::Parse($path.GetAttribute('pathData', $androidNamespace)))
            $geometry.FillRule = [Windows.Media.FillRule]::Nonzero
            $color = [Windows.Media.ColorConverter]::ConvertFromString($path.GetAttribute('fillColor', $androidNamespace))
            $brush = [Windows.Media.SolidColorBrush]::new($color)
            $drawing.DrawGeometry($brush, $null, $geometry)
        }
        $drawing.Pop()
    } finally { $drawing.Close() }
    $bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new($size, $size, 96, 96, [Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $output = [IO.MemoryStream]::new()
    try { $encoder.Save($output); $frames.Add($output.ToArray()) }
    finally { $output.Dispose() }
}

New-Item -ItemType Directory -Path (Split-Path $iconPath -Parent) -Force | Out-Null
$file = [IO.File]::Create($iconPath)
$writer = [IO.BinaryWriter]::new($file)
try {
    $writer.Write([uint16]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($index = 0; $index -lt $sizes.Count; $index++) {
        $dimension = if ($sizes[$index] -eq 256) { 0 } else { $sizes[$index] }
        $writer.Write([byte]$dimension)
        $writer.Write([byte]$dimension)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]32)
        $writer.Write([uint32]$frames[$index].Length)
        $writer.Write([uint32]$offset)
        $offset += $frames[$index].Length
    }
    foreach ($frame in $frames) { $writer.Write($frame) }
} finally { $writer.Dispose(); $file.Dispose() }
[IO.File]::WriteAllBytes($previewPath, $frames[$frames.Count - 1])
Write-Host "Generated Virkey ICO: $($sizes -join ', ') px. Source: $source"
