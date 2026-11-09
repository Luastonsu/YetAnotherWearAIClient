$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }

$secure = Read-Host -AsSecureString 'Enter keystore password'
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $env:YAWAC_STORE_PASSWORD = $plain
    $env:YAWAC_KEY_PASSWORD = $plain
    & (Join-Path $root 'gradlew.bat') :app:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    Remove-Item Env:\YAWAC_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\YAWAC_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (Test-Path -LiteralPath $apk) {
    $hash = Get-FileHash -LiteralPath $apk -Algorithm SHA256
    Write-Host "APK: $apk"
    Write-Host "SHA-256: $($hash.Hash)"
}
