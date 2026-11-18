$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle exited with code $LASTEXITCODE"
    }

    $apk = 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Gradle completed but did not produce $apk"
    }

    New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
    $dist = 'dist\blockmsg-by-illiuoner-en-debug.apk'
    Copy-Item -LiteralPath $apk -Destination $dist -Force
    Get-Item -LiteralPath $dist
} finally {
    Pop-Location
}
