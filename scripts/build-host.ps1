[CmdletBinding()]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'setup-host-toolchain.ps1')
$sdkRoot = Join-Path $projectRoot '.tools\dotnet'
$oldRoot = $env:DOTNET_ROOT
$oldTelemetry = $env:DOTNET_CLI_TELEMETRY_OPTOUT
$oldPackages = $env:NUGET_PACKAGES
$oldCertificate = $env:DOTNET_GENERATE_ASPNET_CERTIFICATE
try {
    $env:DOTNET_ROOT = $sdkRoot
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
    $env:NUGET_PACKAGES = Join-Path $projectRoot '.tools\nuget-packages'
    $env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
    & (Join-Path $sdkRoot 'dotnet.exe') publish (Join-Path $projectRoot 'windows\Virkey.Host\Virkey.Host.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
    if ($LASTEXITCODE -ne 0) { throw 'Windows host publish failed.' }
    $published = Join-Path $projectRoot 'windows\build\bin\Virkey.Host\Release\net8.0-windows10.0.19041.0\win-x64\publish\Virkey.Host.exe'
    if (-not $SkipTests) {
        $testRun = Start-Process -FilePath $published -ArgumentList '--self-test' -Wait -PassThru -WindowStyle Hidden
        $report = Join-Path (Split-Path $published -Parent) 'host-test-results.txt'
        if (Test-Path -LiteralPath $report) { Get-Content -LiteralPath $report }
        if ($testRun.ExitCode -ne 0) { throw "Host self-test failed ($($testRun.ExitCode))." }
    }
    $artifacts = Join-Path $projectRoot '.tools\release-artifacts'
    New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
    Copy-Item -LiteralPath $published -Destination (Join-Path $artifacts 'virkey-host-0.3.0.exe') -Force
    Write-Host "Host ready: $artifacts\virkey-host-0.3.0.exe"
} finally {
    $env:DOTNET_ROOT = $oldRoot
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = $oldTelemetry
    $env:NUGET_PACKAGES = $oldPackages
    $env:DOTNET_GENERATE_ASPNET_CERTIFICATE = $oldCertificate
}
