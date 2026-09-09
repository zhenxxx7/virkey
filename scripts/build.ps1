param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localTools = Join-Path $projectRoot '.tools'
$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
$previousGradle = $env:GRADLE_USER_HOME
$previousPath = $env:PATH

try {
    if (-not $env:JAVA_HOME) {
        $localJava = Get-ChildItem -Path "$localTools\microsoft-jdk", "$localTools\jdk" -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
            Select-Object -First 1
        if ($localJava) { $env:JAVA_HOME = $localJava.FullName }
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        throw 'JDK 17 is required. Set JAVA_HOME or run scripts\setup-toolchain.ps1.'
    }
    if (-not $env:ANDROID_HOME) {
        $env:ANDROID_HOME = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $localTools 'android-sdk' }
    }
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-36\android.jar")) {
        throw 'Android SDK platform 36 is missing. Run scripts\setup-toolchain.ps1 and complete the SDK license prompts.'
    }
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $localTools 'gradle-cache' }
    # PATH entries are not shell strings. Stray quotes in a host PATH can break
    # Java's inherited library path when Gradle launches test workers on Windows.
    $buildPath = ($previousPath -split ';' | ForEach-Object { $_.Trim().Trim('"') }) -join ';'
    $env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$buildPath"
    Push-Location $projectRoot
    try {
        & "$projectRoot\gradlew.bat" --console=plain @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
    $env:GRADLE_USER_HOME = $previousGradle
    $env:PATH = $previousPath
}
