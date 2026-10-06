# Build the debug APK.
#
# JDK: Android Studio's bundled JBR is preferred (JDK 17/21). A JAVA_HOME that
# is already set is only used as a fallback -- on a machine that also has a
# much newer standalone JDK (e.g. JDK 25), trusting JAVA_HOME first makes
# Gradle emit "restricted method ... has been called" warnings and is generally
# not what an Android build wants.
#
# Comments are kept ASCII-only on purpose: a .ps1 without a BOM is read as ANSI
# by Windows PowerShell 5.1, which would garble non-ASCII text.
#
# Output goes both to the console and to build_log.txt (gitignored).
#
# Note: do NOT set $ErrorActionPreference = 'Stop'. Gradle writes progress to
# stderr, and under Windows PowerShell 5.1 a 'Stop' preference turns native
# stderr into a terminating error, killing the build. Check $LASTEXITCODE.

$root = $PSScriptRoot

function Find-Jbr {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'),
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
        'C:\Program Files\Android\Android Studio\jbr',
        'E:\Android Studio\jbr'
    )
    return $candidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
}

$jbr = Find-Jbr
if ($jbr) {
    $env:JAVA_HOME = $jbr
    Write-Host "JAVA_HOME = $env:JAVA_HOME  (Android Studio JBR)"
} elseif ($env:JAVA_HOME) {
    Write-Host "JAVA_HOME = $env:JAVA_HOME  (existing; no Android Studio JBR found)"
} else {
    Write-Error 'No Android Studio JBR found and JAVA_HOME is not set. Install JDK 17 or set JAVA_HOME.'
    exit 1
}

# Gradle resolves the build root from the working directory, not from where
# gradlew.bat lives -- so the location has to be set even though the wrapper is
# invoked by absolute path.
Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') assembleDebug 2>&1 |
        Tee-Object -FilePath (Join-Path $root 'build_log.txt')
} finally {
    Pop-Location
}

exit $LASTEXITCODE
