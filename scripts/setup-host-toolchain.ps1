[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = Join-Path $projectRoot '.tools\dotnet'
if (Test-Path (Join-Path $sdkRoot 'sdk')) { return }
$metadata = Invoke-RestMethod 'https://builds.dotnet.microsoft.com/dotnet/release-metadata/8.0/releases.json'
$sdk = $metadata.releases[0].sdk
$archive = $sdk.files | Where-Object { $_.rid -eq 'win-x64' -and $_.name -like '*.zip' } | Select-Object -First 1
if (-not $archive -or -not $archive.url.StartsWith('https://builds.dotnet.microsoft.com/')) { throw 'Unexpected SDK download metadata.' }
$download = Join-Path $projectRoot '.tools\downloads\dotnet-sdk-win-x64.zip'
New-Item -ItemType Directory -Path (Split-Path $download -Parent) -Force | Out-Null
Invoke-WebRequest -Uri $archive.url -OutFile $download -UseBasicParsing
if ((Get-FileHash -LiteralPath $download -Algorithm SHA512).Hash -ne $archive.hash) { throw 'SDK checksum mismatch.' }
New-Item -ItemType Directory -Path $sdkRoot -Force | Out-Null
Expand-Archive -LiteralPath $download -DestinationPath $sdkRoot -Force
Write-Host "Installed verified .NET SDK $($sdk.version) locally."
