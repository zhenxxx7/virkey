param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localTools = Join-Path $projectRoot '.tools'
$downloads = Join-Path $localTools 'downloads'
$sdkRoot = Join-Path $localTools 'android-sdk'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null

function Get-VerifiedArchive {
    param([string]$Url, [string]$Destination, [string]$Sha256)
    if ((Test-Path $Destination) -and (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -eq $Sha256) {
        return
    }
    Write-Host "Downloading $Url"
    & curl.exe --fail --location --retry 3 $Url -o $Destination
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
        throw "SHA-256 verification failed: $Destination"
    }
}

$jdkDirectory = Join-Path $localTools 'microsoft-jdk'
$java = Get-ChildItem -LiteralPath $jdkDirectory -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
    Select-Object -First 1
if (-not $java) {
    $jdkArchive = Join-Path $downloads 'microsoft-jdk17.zip'
    Get-VerifiedArchive `
        -Url 'https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip' `
        -Destination $jdkArchive `
        -Sha256 '3d9006956fc8af5601cd24ffc4f468bef48279c7ebd8171b9bdf90d0aabfbf1f'
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $jdkDirectory -Force
    $java = Get-ChildItem -LiteralPath $jdkDirectory -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Select-Object -First 1
}

$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkManager)) {
    $cliArchive = Join-Path $downloads 'android-cli.zip'
    Get-VerifiedArchive `
        -Url 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' `
        -Destination $cliArchive `
        -Sha256 '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $cliParent = Join-Path $sdkRoot 'cmdline-tools'
    Expand-Archive -LiteralPath $cliArchive -DestinationPath $cliParent -Force
    $extractedDirectory = [IO.Path]::GetFullPath((Join-Path $cliParent 'cmdline-tools'))
    $destinationDirectory = [IO.Path]::GetFullPath((Join-Path $cliParent 'latest'))
    $expectedParent = [IO.Path]::GetFullPath($cliParent) + [IO.Path]::DirectorySeparatorChar
    if (-not $extractedDirectory.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -or
        -not $destinationDirectory.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to move command-line tools outside the local SDK directory.'
    }
    Move-Item -LiteralPath $extractedDirectory -Destination $destinationDirectory
}

$previousJava = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $env:JAVA_HOME = $java.FullName
    $env:PATH = "$env:JAVA_HOME\bin;$previousPath"
    Write-Host 'The Android SDK installer may ask you to review and accept the Google SDK license.'
    Write-Host 'Please read the terms and answer the installer prompt yourself.'
    & $sdkManager "--sdk_root=$sdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$sdkRoot\platforms\android-36\android.jar") -or
        -not (Test-Path "$sdkRoot\build-tools\35.0.0\aapt2.exe")) {
        throw 'SDK setup is incomplete. If you declined the license, packages were not installed.'
    }
    Write-Host 'Local Android toolchain is ready. Build with: .\scripts\build.ps1'
} finally {
    $env:JAVA_HOME = $previousJava
    $env:PATH = $previousPath
}
