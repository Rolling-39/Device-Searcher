# Build the debug APK.
#
# Requires JDK 17 -- the JBR bundled with Android Studio is fine.
# JAVA_HOME is respected if already set; otherwise a few common install
# locations are probed. (Comments are kept ASCII-only on purpose: a .ps1
# without a BOM is read as ANSI by Windows PowerShell 5.1, which would
# garble non-ASCII text.)
#
# Output goes both to the console and to build_log.txt (gitignored).

$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'),
        'C:\Program Files\Android\Android Studio\jbr',
        'E:\Android Studio\jbr'
    )
    $env:JAVA_HOME = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set and no Android Studio JBR was found. Install JDK 17 or set JAVA_HOME.'
}

Write-Host "JAVA_HOME = $env:JAVA_HOME"

$root = $PSScriptRoot
& (Join-Path $root 'gradlew.bat') assembleDebug 2>&1 |
    Tee-Object -FilePath (Join-Path $root 'build_log.txt')
