# Stops the background mentalcare instance of THIS repository and nothing else.
# Graceful first (POST /api/system/shutdown -> SQLite checkpoint, datasource close), force-kill as a fallback.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop.ps1 [-Port <n>] [-Quiet]
# The port defaults to the one recorded by start-background.ps1 (.run\mentalcare.pid), then 8080.
[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$Quiet
)

$ErrorActionPreference = 'SilentlyContinue'
$root = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
$jar = Join-Path $root 'backend\target\mentalcare.jar'
$pidFile = Join-Path $root '.run\mentalcare.pid'

function Show-Message([string]$text, [string]$icon = 'Information') {
    if ($Quiet) { Write-Host $text; return }
    try {
        Add-Type -AssemblyName System.Windows.Forms | Out-Null
        [System.Windows.Forms.MessageBox]::Show($text, 'MentalCare', 'OK', $icon) | Out-Null
    } catch { Write-Host $text }
}

function Test-OurCommandLine([string]$cmd) {
    if (-not $cmd) { return $false }
    return $cmd.IndexOf("`"$jar`"", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
           $cmd.IndexOf(" $jar ", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
           $cmd.TrimEnd().EndsWith(" $jar", [StringComparison]::OrdinalIgnoreCase)
}

function Get-MentalCareProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
        Where-Object { Test-OurCommandLine $_.CommandLine }
}

# Candidates: the PID we recorded (only if it still runs our jar) plus any other process running our jar.
$targets = @()
if (Test-Path $pidFile) {
    $lines = @(Get-Content $pidFile)
    $savedPid = $lines[0] -as [int]
    if ($Port -eq 0 -and $lines.Count -ge 2) { $Port = $lines[1] -as [int] }
    if ($savedPid) {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid"
        if ($p -and (Test-OurCommandLine $p.CommandLine)) { $targets += $p }
    }
}
if ($Port -eq 0) { $Port = 8080 }
$targets += Get-MentalCareProcesses
$targets = $targets | Sort-Object ProcessId -Unique

if (-not $targets) {
    Remove-Item $pidFile -Force
    Show-Message 'MentalCare is not running.'
    exit 0
}

# 1. graceful shutdown through the local API (only if the port belongs to one of our targets)
$owner = (Get-NetTCPConnection -LocalPort $Port -State Listen | Select-Object -First 1).OwningProcess
if ($owner -and ($targets | Where-Object { $_.ProcessId -eq $owner })) {
    try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$Port/api/system/shutdown" -TimeoutSec 5 | Out-Null } catch { }
}

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
