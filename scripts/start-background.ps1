# Starts mentalcare in the background (javaw.exe, no console window), waits until it is healthy,
# then opens the browser. Safe to run repeatedly: a running instance is reused, never duplicated.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-background.ps1 [-NoBrowser] [-Port 8080]
#
# Nothing here reads or writes counseling data or credentials; only env vars and a PID/log file under .run\.
[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [int]$Port = 8080,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'backend\target\mentalcare.jar'
$runDir = Join-Path $root '.run'
$pidFile = Join-Path $runDir 'mentalcare.pid'
$logFile = Join-Path $runDir 'mentalcare.log'
$url = "http://127.0.0.1:$Port"

function Show-Message([string]$text, [string]$title = 'MentalCare', [string]$icon = 'Information') {
    try {
        Add-Type -AssemblyName System.Windows.Forms | Out-Null
        [System.Windows.Forms.MessageBox]::Show($text, $title, 'OK', $icon) | Out-Null
    } catch {
        Write-Host "$title - $text"
    }
}

function Get-MentalCareProcess {
    # Our process = a java/javaw whose command line names our jar (never matches other Java apps).
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*mentalcare.jar*" } |
        Select-Object -First 1
}

function Test-Health {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri "$url/api/health" -TimeoutSec 3
        return ($r.StatusCode -eq 200 -and $r.Content -match '"status"\s*:\s*"UP"')
    } catch { return $false }
}

function Open-Browser {
    if (-not $NoBrowser) { Start-Process $url }
}

New-Item -ItemType Directory -Force $runDir | Out-Null

# 1. already running? (PID file first, then any process running our jar)
$existing = $null
if (Test-Path $pidFile) {
    $savedPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1) -as [int]
    if ($savedPid) {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid" -ErrorAction SilentlyContinue
        if ($p -and $p.CommandLine -like "*mentalcare.jar*") { $existing = $p } else { Remove-Item $pidFile -Force }
    }
}
if (-not $existing) { $existing = Get-MentalCareProcess }
if ($existing) {
    Set-Content -Path $pidFile -Value $existing.ProcessId -Encoding ascii
    if (Test-Health) { Open-Browser; exit 0 }
    # process exists but not answering yet: fall through to the wait loop
}

# 2. port used by something else?
if (-not $existing) {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $other = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue
        if (-not ($other -and $other.CommandLine -like "*mentalcare.jar*")) {
            Show-Message "Port $Port is already in use by another program (PID $($listener.OwningProcess)). Stop it or change the port." 'MentalCare' 'Error'
            exit 2
        }
    }
}

# 3. start a new instance
if (-not $existing) {
    if (-not (Test-Path $jar)) {
        Show-Message "backend\target\mentalcare.jar was not found.`nRun scripts\build.cmd first." 'MentalCare' 'Error'
        exit 3
    }
    # .env (optional): KEY=VALUE lines, '#' comments
    $envFile = Join-Path $root '.env'
    if (Test-Path $envFile) {
        Get-Content $envFile | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
                $k, $v = $line.Split('=', 2)
                if ($k.Trim()) { [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), 'Process') }
            }
        }
    }
    if (-not $env:MENTALCARE_DATA_DIR) { $env:MENTALCARE_DATA_DIR = Join-Path $root 'data' }
    if (-not $env:JAVA_HOME -and (Test-Path "$env:USERPROFILE\tools\jdk-21")) { $env:JAVA_HOME = "$env:USERPROFILE\tools\jdk-21" }
    $javaw = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javaw.exe' } else { 'javaw.exe' }
    if ($env:JAVA_HOME -and -not (Test-Path $javaw)) {
        Show-Message "javaw.exe not found under JAVA_HOME ($env:JAVA_HOME)." 'MentalCare' 'Error'
        exit 4
    }

    $args = @('-jar', "`"$jar`"", "--server.port=$Port", "--logging.file.name=`"$logFile`"")
    $proc = Start-Process -FilePath $javaw -ArgumentList $args -WorkingDirectory $root -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $proc.Id -Encoding ascii
}

# 4. wait for health
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if (Test-Health) { Open-Browser; exit 0 }
    $alive = Get-MentalCareProcess
    if (-not $alive) {
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        Show-Message "MentalCare exited before becoming ready. See .run\mentalcare.log" 'MentalCare' 'Error'
        exit 5
    }
    Start-Sleep -Seconds 1
}
Show-Message "MentalCare did not answer within $TimeoutSeconds seconds. See .run\mentalcare.log" 'MentalCare' 'Warning'
exit 6
