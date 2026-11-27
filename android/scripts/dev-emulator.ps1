# Runs the app on a running emulator (or a USB device) against a server.js on
# this machine. Windows version of dev-emulator.sh.
#
# Needs: Node 22, JDK 17+ (JAVA_HOME, java on PATH, or the one inside Android
# Studio), the Android SDK (ANDROID_HOME, or the default
# %LOCALAPPDATA%\Android\Sdk), and an emulator already started.
#
#   powershell -ExecutionPolicy Bypass -File android\scripts\dev-emulator.ps1
#   powershell -ExecutionPolicy Bypass -File android\scripts\dev-emulator.ps1 -Port 3100
param([int]$Port = 3000)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Origin = "http://127.0.0.1:$Port"

$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb. Install the Android SDK platform-tools, or set ANDROID_HOME." }
$env:ANDROID_HOME = $Sdk

# Gradle needs a JDK. Android Studio ships one; use it when nothing else is set up.
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $Jbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not (Test-Path (Join-Path $Jbr 'bin\java.exe'))) { throw 'No JDK found. Install JDK 17 or newer, or set JAVA_HOME (Android Studio has one in its jbr folder).' }
    $env:JAVA_HOME = $Jbr
}

# adb writes to stderr when nothing is connected; under 'Stop', Windows PowerShell
# would turn that into its own error before the message below.
$ErrorActionPreference = 'Continue'
& $Adb get-state 2>$null | Out-Null
$connected = $LASTEXITCODE -eq 0
$ErrorActionPreference = 'Stop'
if (-not $connected) { throw 'No emulator or device is connected (adb devices).' }

# The emulator's own 127.0.0.1:Port now reaches this machine's Port.
& $Adb reverse "tcp:$Port" "tcp:$Port" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "adb reverse tcp:$Port failed; the app could not reach $Origin." }

function Test-Server {
    try { return (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "$Origin/health").StatusCode -eq 200 } catch { return $false }
}

if (-not (Test-Server)) {
    Write-Host "Starting server.js on port $Port"
    if (-not (Test-Path (Join-Path $Root 'node_modules'))) {
        Push-Location $Root
        try { npm ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' } } finally { Pop-Location }
    }
    $log = Join-Path $env:TEMP 'darkp2p-dev-server.log'
    # DOMAIN is only used for links the server prints; the app and the page talk to 127.0.0.1.
    $env:PORT = "$Port"
    $env:DOMAIN = 'localhost'
    $server = Start-Process node -ArgumentList 'server.js' -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    Set-Content (Join-Path $env:TEMP 'darkp2p-dev-server.pid') $server.Id
    $up = $false
    foreach ($i in 1..30) { Start-Sleep -Milliseconds 500; if (Test-Server) { $up = $true; break }; if ($server.HasExited) { break } }
    if (-not $up) { throw "server.js did not start; see $log and $log.err" }
    Write-Host "Server log: $log   (stop it with: Stop-Process -Id $($server.Id))"
}

Push-Location (Join-Path $Root 'android')
try {
    & .\gradlew.bat :app:installFullDebug "-Pdarkp2p.origin=$Origin"
    if ($LASTEXITCODE -ne 0) { throw 'The build or install failed.' }
} finally { Pop-Location }

& $Adb shell am start -n com.darkp2p.app/.MainActivity | Out-Null

Write-Host @"

The app is running. To test with the web page as the other device:
  1. In the app, tap "New room" and then "Copy" (or read the link on screen).
  2. Open the same link in a browser on this machine: $Origin/#<room>:<key>
Or the other way round: open $Origin/ in a browser, copy its link, and paste it into the app.

App output:  & '$Adb' logcat "--pid=`$(& '$Adb' shell pidof com.darkp2p.app)"
"@
