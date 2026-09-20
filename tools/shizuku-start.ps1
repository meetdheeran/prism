# Starts (or restarts) the Shizuku server on the connected phone over USB adb.
# Non-root Shizuku does not survive a reboot on Android 12, so run this after every restart.
# It discovers the installed app's own native library path (Shizuku v13.6.0 method) instead of
# hard-coding it, then executes libshizuku.so and verifies the server process.
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "C:\Users\Dheer\Downloads\Shizuku-Setup\platform-tools\adb.exe" }

$devices = & $adb devices | Select-String -Pattern "`tdevice$"
if (-not $devices) {
  Write-Host "No authorized device. Unlock the phone, connect a USB data cable, enable USB debugging, and accept the prompt." -ForegroundColor Yellow
  & $adb devices -l
  exit 1
}

$path = (& $adb shell pm path moe.shizuku.privileged.api).Trim()
if (-not $path) { Write-Host "Shizuku is not installed. Run: adb install <shizuku apk>" -ForegroundColor Red; exit 1 }
$codePath = ($path -replace '^package:', '') -replace '/base\.apk$', ''
$abi = (& $adb shell getprop ro.product.cpu.abi).Trim()
$libDir = if ($abi -like 'arm64*') { 'arm64' } elseif ($abi -like 'x86_64') { 'x86_64' } elseif ($abi -like 'x86') { 'x86' } else { 'arm' }
$starter = "$codePath/lib/$libDir/libshizuku.so"
Write-Host "Starter: $starter"
& $adb shell "'$starter'"

Start-Sleep -Seconds 2
$ps = & $adb shell "ps -A | grep shizuku_server"
if ($ps) { Write-Host "Shizuku server is running:`n$ps" -ForegroundColor Green } else { Write-Host "Server did not start. Output above may say why." -ForegroundColor Red; exit 1 }
