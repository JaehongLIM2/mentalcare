# Stops the background mentalcare instance started by start-background.ps1 and nothing else.
# Graceful first (POST /api/system/shutdown -> SQLite checkpoint, datasource close), force-kill as a fallback.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop.ps1 [-Port 8080] [-Quiet]
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [switch]$Quiet
)

$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.run\mentalcare.pid'

function Show-Message([string]$text, [string]$icon = 'Information') {
    if ($Quiet) { Write-Host $text; return }
    try {
        Add-Type -AssemblyName System.Windows.Forms | Out-Null
        [System.Windows.Forms.MessageBox]::Show($text, 'MentalCare', 'OK', $icon) | Out-Null
    } catch { Write-Host $text }
}

function Get-MentalCareProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*mentalcare.jar*" }
}

# Candidates: the PID we recorded (only if it still runs our jar) plus any other process running our jar.
$targets = @()
if (Test-Path $pidFile) {
    $savedPid = (Get-Content $pidFile | Select-Object -First 1) -as [int]
    if ($savedPid) {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid"
        if ($p -and $p.CommandLine -like "*mentalcare.jar*") { $targets += $p }
    }
}
$targets += Get-MentalCareProcesses
$targets = $targets | Sort-Object ProcessId -Unique

if (-not $targets) {
    Remove-Item $pidFile -Force
    Show-Message 'MentalCare is not running.'
    exit 0
}

# 1. graceful shutdown through the local API
try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$Port/api/system/shutdown" -TimeoutSec 5 | Out-Null } catch { }

# 2. wait up to 15 s, then force
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
    $still = $targets | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
    if (-not $still) { break }
    Start-Sleep -Milliseconds 500
}
$forced = 0
foreach ($t in $targets) {
    $proc = Get-Process -Id $t.ProcessId -ErrorAction SilentlyContinue
    if ($proc) { Stop-Process -Id $t.ProcessId -Force; $forced++ }
}
Remove-Item $pidFile -Force
if ($forced -gt 0) { Show-Message "MentalCare stopped ($forced process(es) had to be force-killed)." 'Warning' }
else { Show-Message 'MentalCare stopped.' }
exit 0
