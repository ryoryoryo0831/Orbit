# PC で Orbit をビルドする（テスト・Lint・APK）。-Device を付けると、その端末に入れる。
#
#   .\pc-build.ps1                       # ビルドだけ
#   .\pc-build.ps1 -Device HA29XDDP      # ビルドしてタブレットに上書きインストール
#   .\pc-build.ps1 -Device emulator-5554 # ビルドしてエミュレーターに入れる（x86_64 版）
#   .\pc-build.ps1 -Quick -Device ...    # テストと Lint を省いて APK だけ作る
#
# - 署名は ..\signing の本物の鍵（GitHub と同じ）なので、スマホ・タブレットの Orbit に上書きできる。
# - 版番号は GitHub のいちばん新しいビルドの番号を借りて「1.0.N-pc」にする。
#   次に GitHub で作る版は N+1 になるので、PC の版を入れた端末にも「新しい版」がふつうに届く。
param(
    [string]$Device = "",
    [switch]$Quick
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

$signing = Join-Path $PSScriptRoot "..\signing"
$env:KEYSTORE_FILE = (Resolve-Path (Join-Path $signing "release.p12")).Path
$env:KEYSTORE_PASSWORD = (Get-Content (Join-Path $signing "password.txt") -Raw).Trim()

$n = gh run list --repo ryoryoryo0831/Orbit --workflow android.yml --limit 1 --json number -q ".[0].number"
if (-not $n) { throw "GitHub のビルド番号を取れませんでした（gh にログインしているか確かめてください）" }
$env:GITHUB_RUN_NUMBER = $n
$env:ORBIT_VERSION_SUFFIX = "-pc"
Write-Host "版：1.0.$n-pc"

[string[]]$tasks = if ($Quick) { @("assembleRelease") } else { @("testReleaseUnitTest", "lintRelease", "assembleRelease") }
& .\gradlew.bat @tasks
if ($LASTEXITCODE -ne 0) { throw "ビルドに失敗しました" }

if ($Device) {
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    $abi = (& $adb -s $Device shell getprop ro.product.cpu.abi).Trim()
    $apk = switch ($abi) {
        "arm64-v8a"   { "app-arm64-v8a-release.apk" }
        "armeabi-v7a" { "app-armeabi-v7a-release.apk" }
        "x86_64"      { "app-x86_64-release.apk" }
        default       { throw "この CPU（$abi）用の APK はありません" }
    }
    Write-Host "$Device（$abi）に $apk を入れます"
    & $adb -s $Device install -r "app\build\outputs\apk\release\$apk"
    if ($LASTEXITCODE -ne 0) { throw "インストールに失敗しました" }
}
