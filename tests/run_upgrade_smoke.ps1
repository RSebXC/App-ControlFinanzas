param(
    [Parameter(Mandatory=$true)][string]$PreviousApk,
    [string]$Serial = 'emulator-5554',
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Use an emulator, never a physical phone.' }
$projectDir = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDir
if (-not (Test-Path -LiteralPath $PreviousApk)) { throw 'Previous APK not found.' }

function Invoke-TestAdb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    $output
}

& ./gradlew.bat --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed.' }

Invoke-TestAdb @('install','-r','-t',$PreviousApk)
Invoke-TestAdb @('install','-r','-t','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
$prepare = Invoke-TestAdb @('shell','am','instrument','-w','-e','allowSyntheticData','emulator-only','-e','mode','prepare','gt.finanzas.privadas.test/gt.finanzas.privadas.UpgradeSmokeTest')
$prepare
if (($prepare -join "`n") -notmatch 'PASS:') { throw 'Preparation failed. Use an empty disposable emulator.' }

Invoke-TestAdb @('install','-r','-t','app/build/outputs/apk/debug/app-debug.apk')
Invoke-TestAdb @('shell','pm','grant','gt.finanzas.privadas','android.permission.POST_NOTIFICATIONS')
$verify = Invoke-TestAdb @('shell','am','instrument','-w','-e','allowSyntheticData','emulator-only','-e','mode','verify','gt.finanzas.privadas.test/gt.finanzas.privadas.UpgradeSmokeTest')
$verify
if (($verify -join "`n") -notmatch 'PASS:') { throw 'Upgrade or interface check failed.' }

New-Item -ItemType Directory -Path build/qa -Force | Out-Null
Invoke-TestAdb @('pull','/sdcard/Android/data/gt.finanzas.privadas/files/qa/.','build/qa/')
