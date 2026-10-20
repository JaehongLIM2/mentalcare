# Starts mentalcare in the background (javaw.exe, no console window), waits until it is healthy,
# then opens the browser. Safe to run repeatedly: a running instance is reused, never duplicated.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-background.ps1 [-NoBrowser] [-Port 8080]
#
# Nothing here reads or writes counseling data or credentials; only env vars and a PID/log file under .run\.
# Only processes running THIS repository's jar (exact absolute path) are ever considered "ours".
[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [int]$Port = 8080,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$portExplicit = $PSBoundParameters.ContainsKey('Port')
$root = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
$jar = Join-Path $root 'backend\target\mentalcare.jar'
# the app-image built by scripts\package-windows.cmd runs the backend inside MentalCare.exe itself
$exe = Join-Path $root 'dist\MentalCare\MentalCare.exe'
$runDir = Join-Path $root '.run'
$pidFile = Join-Path $runDir 'mentalcare.pid'
$logFile = Join-Path $runDir 'mentalcare.log'
function Get-Url { "http://127.0.0.1:$script:Port" }

function Show-Message([string]$text, [string]$title = 'MentalCare', [string]$icon = 'Information') {
    try {
        Add-Type -AssemblyName System.Windows.Forms | Out-Null
        [System.Windows.Forms.MessageBox]::Show($text, $title, 'OK', $icon) | Out-Null
    } catch {
        Write-Host "$title - $text"
    }
}

function Test-OurCommandLine([string]$cmd) {
    # exact absolute jar path, case-insensitive; a copy elsewhere or "mentalcare.jar.backup" never matches
    if (-not $cmd) { return $false }
    return $cmd.IndexOf("`"$jar`"", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
           $cmd.IndexOf(" $jar ", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
           $cmd.TrimEnd().EndsWith(" $jar", [StringComparison]::OrdinalIgnoreCase)
}

function Test-OurProcess($proc) {
    if (-not $proc) { return $false }
    return (Test-OurCommandLine $proc.CommandLine) -or ($proc.ExecutablePath -and $proc.ExecutablePath -ieq $exe)
}

function Get-MentalCareProcess {
    $all = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe' OR Name = 'MentalCare.exe'" |
        Where-Object { Test-OurProcess $_ })
    # MentalCare.exe runs the JVM in a child MentalCare.exe; that child owns the port, so skip the parent
    $all | Where-Object { $id = $_.ProcessId; -not ($all | Where-Object { $_.ParentProcessId -eq $id }) } |
        Select-Object -First 1
}

function Test-Health {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri "$(Get-Url)/api/health" -TimeoutSec 3
        return ($r.StatusCode -eq 200 -and $r.Content -match '"status"\s*:\s*"UP"')
    } catch { return $false }
}

function Open-Browser {
    if (-not $NoBrowser) { Start-Process (Get-Url) }
}

function Get-ListeningPort([int]$processId) {
    (Get-NetTCPConnection -State Listen -OwningProcess $processId -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -in @('127.0.0.1','0.0.0.0','::1','::') } | Select-Object -First 1).LocalPort
}

function Test-PortOwnedBy([int]$port, [int]$processId) {
    $owner = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
    return ($owner -eq $processId)
}

function Write-PidFile([int]$processId) {
    # line 1: PID, line 2: port, line 3: jar path (the stop script validates all three)
    Set-Content -Path $pidFile -Value @($processId, $Port, $jar) -Encoding UTF8
}

New-Item -ItemType Directory -Force $runDir | Out-Null

# Serialize the whole start procedure per repository (two double-clicks must not start two JVMs).
$mutexName = 'Local\MentalCare-' + [BitConverter]::ToString([Security.Cryptography.SHA1]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToLowerInvariant()))).Replace('-', '').Substring(0, 16)
$mutex = New-Object System.Threading.Mutex($false, $mutexName)
if (-not $mutex.WaitOne(60000)) {
    Show-Message 'Another MentalCare start is still in progress.' 'MentalCare' 'Warning'
    exit 7
}
try {
    # 1. already running? (PID file first, then any process running our jar)
    $existing = $null
    $lines = @()
    if (Test-Path $pidFile) {
        $lines = @(Get-Content $pidFile -ErrorAction SilentlyContinue)
        $savedPid = $lines[0] -as [int]
        if ($savedPid) {
            $p = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid" -ErrorAction SilentlyContinue
            if (Test-OurProcess $p) { $existing = $p } else { Remove-Item $pidFile -Force }
        }
    }
    if (-not $existing) { $existing = Get-MentalCareProcess }
    if ($existing) {
        # keep the port the instance is actually using (socket first, then the PID file) unless -Port was explicit
        $recorded = if ($lines.Count -ge 2) { $lines[1] -as [int] } else { 0 }
        $actual = Get-ListeningPort $existing.ProcessId
        if (-not $portExplicit) {
            if ($actual) { $script:Port = [int]$actual } elseif ($recorded) { $script:Port = $recorded }
        } elseif ($actual -and $actual -ne $Port) {
            Show-Message "MentalCare is already running on port $actual (PID $($existing.ProcessId)); -Port $Port was ignored." 'MentalCare' 'Information'
            $script:Port = [int]$actual
        }
        Write-PidFile $existing.ProcessId
        if ((Test-Health) -and (Test-PortOwnedBy $Port $existing.ProcessId)) { Open-Browser; exit 0 }
        # process exists but not answering yet: fall through to the wait loop
    }

    # 2. port used by something else?
    if (-not $existing) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            $other = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue
            if (-not (Test-OurProcess $other)) {
                Show-Message "Port $Port is already in use by another program (PID $($listener.OwningProcess)). Stop it or use -Port." 'MentalCare' 'Error'
                exit 2
            }
        }
    }

    # 3. start a new instance
    $started = $null
    if (-not $existing) {
        if (-not (Test-Path $jar)) {
            Show-Message "backend\target\mentalcare.jar was not found.`nRun scripts\build.cmd first." 'MentalCare' 'Error'
            exit 3
        }
        # .env (optional): KEY=VALUE lines, '#' comments, UTF-8 (with or without BOM)
        $envFile = Join-Path $root '.env'
        if (Test-Path $envFile) {
            Get-Content $envFile -Encoding UTF8 | ForEach-Object {
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

        # The OAuth redirect_uri follows the port unless the user pinned MENTALCARE_PUBLIC_URL explicitly.
        $args = @('-Dfile.encoding=UTF-8', '-jar', "`"$jar`"", "--server.port=$Port", "--logging.file.name=`"$logFile`"")
        if (-not $env:MENTALCARE_PUBLIC_URL) { $args += "--openai.siwc.public-base-url=http://127.0.0.1:$Port" }
        $started = Start-Process -FilePath $javaw -ArgumentList $args -WorkingDirectory $root -WindowStyle Hidden -PassThru
        Write-PidFile $started.Id
    }

    # 4. wait for health; the process we wait for must be the one we started (or found)
    $watchPid = if ($started) { $started.Id } else { $existing.ProcessId }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Health) {
            $owner = (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
            if ($owner -and $owner -ne $watchPid) {
                Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
                Show-Message "Port $Port answered, but from PID $owner instead of the MentalCare process $watchPid." 'MentalCare' 'Warning'
                exit 8
            }
            Open-Browser; exit 0
        }
        if (-not (Get-Process -Id $watchPid -ErrorAction SilentlyContinue)) {
            Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
            Show-Message "MentalCare exited before becoming ready. See .run\mentalcare.log" 'MentalCare' 'Error'
            exit 5
        }
        Start-Sleep -Seconds 1
    }
    Show-Message "MentalCare did not answer within $TimeoutSeconds seconds. See .run\mentalcare.log" 'MentalCare' 'Warning'
    exit 6
} finally {
    $mutex.ReleaseMutex() | Out-Null
    $mutex.Dispose()
}
