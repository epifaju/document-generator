# ServerLifecycle.psm1 — Phase H.3 : cycle de vie Windows du serveur de test.
#
# Proprietaire du processus Spring Boot lance par les gates :
#   Start-TestServer -> attente bornee -> Stop-TestServer -> verification port + JVM
#
# Regles (AGENTS.md §13, phase H.3) :
#   - AUCUNE attente sans deadline explicite ;
#   - AUCUN kill fonde uniquement sur un port : le proprietaire d'un port qui
#     n'appartient PAS a la session est signale, jamais tue ;
#   - AUCUN kill en bloc sur java.exe : uniquement l'arbre de la session
#     (descendance du PID racine) + preuves fortes (CommandLine terminant par
#     le jar du projet) ;
#   - les processus Java preexistants au demarrage de la session sont
#     enregistres et EXCLUS du kill set (L5/L6) ;
#   - idempotence : Stop-TestServer appele deux fois retourne le meme resultat ;
#   - le module THROW (code dans $ex.Data['LifecycleCode']), il n'appelle
#     jamais exit : seul le script d'entree sort avec un code.

Set-Variable -Name ModuleRepoRoot -Value (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Option Constant

function New-LifecycleError {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Code, [Parameter(Mandatory = $true)][string]$Message)
    $ex = New-Object System.Exception($Message)
    $ex.Data['LifecycleCode'] = $Code
    return $ex
}

function Get-LifecycleCode {
    [CmdletBinding()]
    param($ErrorRecord)
    if ($null -eq $ErrorRecord) { return $null }
    $ex = $ErrorRecord.Exception
    if ($null -eq $ex) { return $null }
    for ($i = 0; $i -lt 5; $i++) {
        if ($null -eq $ex) { break }
        $code = $ex.Data['LifecycleCode']
        if ($null -ne $code -and "$code" -ne '') { return [string]$code }
        $ex = $ex.InnerException
    }
    return $null
}

function Protect-Secret {
    [CmdletBinding()]
    param([string]$Text, [string]$Secret)
    if ([string]::IsNullOrEmpty($Secret)) { return $Text }
    return ($Text -replace [regex]::Escape($Secret), '***')
}

# ---------------------------------------------------------------------------
# Arbre de processus Windows
# ---------------------------------------------------------------------------

function Get-SafeProcessStartTime {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    try { return (Get-Process -Id $ProcessId -ErrorAction Stop).StartTime } catch { return $null }
}

function Get-DescendantRecords {
    <# Descendance directe du PID racine, profondeur MAX inclusive.
       Un seul snapshot Win32_Process : la reponse est coherente (pas de TOCTOU
       interne). Retourne toujours un tableau (,[]) — jamais $null. #>
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][int]$RootPid, [int]$MaxDepth = 10, $Snapshot = $null)
    $ErrorActionPreference = 'Stop'
    if ($null -eq $Snapshot) { $Snapshot = @(Get-CimInstance -ClassName Win32_Process) }
    $byParent = @{}
    foreach ($proc in $Snapshot) {
        $ppid = [int]$proc.ParentProcessId
        if (-not $byParent.ContainsKey($ppid)) { $byParent[$ppid] = New-Object System.Collections.ArrayList }
        [void]$byParent[$ppid].Add($proc)
    }
    $out = New-Object System.Collections.ArrayList
    $seen = @{}
    $seen[$RootPid] = $true
    $queue = New-Object System.Collections.Queue
    $queue.Enqueue(@{ Pid = $RootPid; Depth = 0 })
    while ($queue.Count -gt 0) {
        $cur = $queue.Dequeue()
        if ($cur.Depth -ge $MaxDepth) { continue }
        if (-not $byParent.ContainsKey([int]$cur.Pid)) { continue }
        foreach ($child in $byParent[[int]$cur.Pid]) {
            $cid = [int]$child.ProcessId
            if ($seen.ContainsKey($cid)) { continue }
            $seen[$cid] = $true
            [void]$out.Add([pscustomobject]@{
                    Pid         = $cid
                    Depth       = ($cur.Depth + 1)
                    Name        = [string]$child.Name
                    CommandLine = $(if ($child.CommandLine) { [string]$child.CommandLine } else { $null })
                })
            $queue.Enqueue(@{ Pid = $cid; Depth = ($cur.Depth + 1) })
        }
    }
    # Pas d'empilement (,) : l'appelant entoure par @() ; un tableau vide est
    # alors un pipeline vide (et non un objet imbrique dont .Pid vaudrait nul).
    foreach ($rec in $out) { $rec }
}

function Get-ChildProcessIds {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][int]$RootPid, [int]$MaxDepth = 10)
    $records = @(Get-DescendantRecords -RootPid $RootPid -MaxDepth $MaxDepth)
    # Pas d'empilement (,) : l'appelant doit entourer par @() ; un tableau vide
    # voyage alors comme pipeline vide et non comme objet imbrique.
    foreach ($rec in $records) { [int]$rec.Pid }
}

function Stop-ProcessTree {
    <# Arret d'une arborescence appartenant a l'appelant : descendance (du plus
       profond vers la racine) puis racine. Un seul snapshot sert a la fois a
       l'inventaire et au kill (pas de reutilisation de PID entre les deux). #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][int]$RootPid,
        [int]$MaxDepth = 10
    )
    $ErrorActionPreference = 'Stop'
    $killed = 0
    $notes = New-Object System.Collections.ArrayList
    $snapshot = @(Get-CimInstance -ClassName Win32_Process)
    $targets = @(Get-DescendantRecords -RootPid $RootPid -MaxDepth $MaxDepth -Snapshot $snapshot)
    $rootRec = $snapshot | Where-Object { [int]$_.ProcessId -eq $RootPid } | Select-Object -First 1
    $ordered = @($targets | Sort-Object -Property @{ Expression = { $_.Depth }; Descending = $true })
    $pidList = @($ordered | ForEach-Object { [int]$_.Pid }) + @($RootPid)
    foreach ($targetPid in $pidList) {
        if ($targetPid -le 0) { continue }
        try {
            Stop-Process -Id $targetPid -Force -ErrorAction Stop
            $killed++
        } catch {
            [void]$notes.Add("pid $targetPid : $($_.Exception.Message)")
        }
    }
    return [pscustomobject]@{ Killed = $killed; Detail = ($notes -join '; ') }
}

# ---------------------------------------------------------------------------
# Ports
# ---------------------------------------------------------------------------

function Get-PortListenerPid {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][int]$Port)
    $ErrorActionPreference = 'SilentlyContinue'
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    $ErrorActionPreference = 'Stop'
    if ($listeners.Count -eq 0) { return $null }
    return [int]($listeners | Select-Object -First 1).OwningProcess
}

function Test-PortFree {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][int]$Port)
    return ($null -eq (Get-PortListenerPid -Port $Port))
}

function Assert-PortFree {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][int]$Port, [string]$Phase = 'demarrage')
    $ownerPid = Get-PortListenerPid -Port $Port
    if ($null -eq $ownerPid) { return $true }
    $ownerName = 'inconnu'
    try { $ownerName = [string](Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $ownerPid").Name } catch { }
    throw (New-LifecycleError 'PORT_OCCUPIED' ("PORT_OCCUPIED port={0} ownerPid={1} ownerName={2} phase={3} : port deja occupe avant {4}, proprietaire NON tue (fail fast)." -f $Port, $ownerPid, $ownerName, $Phase, $Phase))
}

# ---------------------------------------------------------------------------
# Java / JVM du projet
# ---------------------------------------------------------------------------

function Resolve-TestJava {
    <# Preferer JAVA_HOME\bin\java.exe : c'est le JDK reel (pas le shim
       "javapath\java.exe" qui spawn un enfant java.exe — cause racine H.3). #>
    [CmdletBinding()]
    param()
    $ErrorActionPreference = 'Stop'
    $javaHome = $env:JAVA_HOME
    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        $candidate = Join-Path $javaHome 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    throw (New-LifecycleError 'JAVA_NOT_FOUND' 'JAVA_NOT_FOUND : aucun java.exe (JAVA_HOME ni PATH).')
}

function Get-ProjectJvmRecords {
    <# JVM du projet : Name -eq java.exe ET CommandLine se terminant par le
       CHEMIN COMPLET du jar (preuve forte de propriete : un jar homonyme
       d'un autre checkout ne matche jamais). Un CommandLine nul ne matche
       JAMAIS. #>
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$JarPath)
    $ErrorActionPreference = 'Stop'
    $out = New-Object System.Collections.ArrayList
    foreach ($proc in @(Get-CimInstance -ClassName Win32_Process)) {
        if ($proc.Name -ne 'java.exe') { continue }
        $cmdLine = $proc.CommandLine
        if ([string]::IsNullOrWhiteSpace($cmdLine)) { continue }
        if ($cmdLine.TrimEnd('"').EndsWith($JarPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            [void]$out.Add([pscustomobject]@{ Pid = [int]$proc.ProcessId; CommandLine = [string]$cmdLine })
        }
    }
    # Meme regle que Get-DescendantRecords : sortie pipeline, pas d'empilement.
    foreach ($rec in $out) { $rec }
}

function Test-ProjectJvmCount {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$JarPath)
    return @(Get-ProjectJvmRecords -JarPath $JarPath).Count
}

# ---------------------------------------------------------------------------
# Processus natifs bornes (docker, pg_isready...) — PS 5.1 n'a aucun timeout
# integre aux commandes natives.
# ---------------------------------------------------------------------------

function Invoke-NativeBounded {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [int]$TimeoutSeconds = 60
    )
    $ErrorActionPreference = 'Stop'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = $null
    # Les arguments peuvent contenir POSTGRES_PASSWORD=... : redaction avant
    # tout message d'erreur (ces messages sont imprimes par les appelants).
    $safeArgs = $Arguments -replace '(POSTGRES_PASSWORD=)\S+', '$1***'
    try { $proc = [System.Diagnostics.Process]::Start($psi) } catch {
        throw (New-LifecycleError 'NATIVE_LAUNCH_FAILED' ("NATIVE_LAUNCH_FAILED {0} {1} : {2}" -f $FilePath, $safeArgs, $_.Exception.Message))
    }
    if ($null -eq $proc) {
        throw (New-LifecycleError 'NATIVE_LAUNCH_FAILED' ("NATIVE_LAUNCH_FAILED {0} {1}" -f $FilePath, $safeArgs))
    }
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $launchedUtc = [DateTime]::UtcNow
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        [void](Stop-ProcessTree -RootPid $proc.Id)
        return [pscustomobject]@{ ExitCode = $null; TimedOut = $true; StdOut = ''; StdErr = "timeout ${TimeoutSeconds}s" }
    }
    try { [void]$outTask.Wait(5000) } catch { }   # AggregateException si la tache est en erreur
    try { [void]$errTask.Wait(5000) } catch { }
    # Jamais de lecture bloquante : si un petit-fils a herite des pipes et les
    # tient ouverts, .Result n'est lu que lorsque la tache est terminee ; chaque
    # flux non termine est signale distinctement (aucun ecrasement).
    $stdout = ''; $stderr = ''
    if ($outTask.IsCompleted) { try { $stdout = [string]$outTask.Result } catch { } }
    else { $stdout = '<stdout non termine (pipe herite)>' }
    if ($errTask.IsCompleted) { try { $stderr = [string]$errTask.Result } catch { } }
    else { $stderr = '<stderr non termine (pipe herite)>' }
    return [pscustomobject]@{ ExitCode = $proc.ExitCode; TimedOut = $false; StdOut = $stdout; StdErr = $stderr; ElapsedSeconds = [int]([DateTime]::UtcNow - $launchedUtc).TotalSeconds }
}

# ---------------------------------------------------------------------------
# Scripts PowerShell enfants bornes (gates, stress) : redirection fichier +
# borne d'execution, kill d'arbre en cas de depassement.
# ---------------------------------------------------------------------------

function Invoke-PowerShellBounded {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$ScriptArgs = @(),
        [int]$TimeoutSeconds = 900,
        [Parameter(Mandatory = $true)][string]$OutFile
    )
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path -LiteralPath $ScriptPath)) {
        throw (New-LifecycleError 'SCRIPT_MISSING' ("SCRIPT_MISSING : {0}" -f $ScriptPath))
    }
    $logDir = Split-Path -Parent $OutFile
    if ($logDir -and -not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
    $argLine = '-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $ScriptPath
    foreach ($a in $ScriptArgs) {
        if ($null -eq $a) { continue }
        # Toujours guilleme : aucun metacaractere cmd (& | < > ^ espace) ne
        # peut fuir de l'argument, meme sans espace dans la valeur.
        $argLine += ' "{0}"' -f ("$a" -replace '"', '\"')
    }
    $cmd = 'powershell.exe {0} > "{1}" 2>&1' -f $argLine, $OutFile
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = '/c ' + $cmd
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $launchedUtc = [DateTime]::UtcNow
    $proc = [System.Diagnostics.Process]::Start($psi)
    if ($null -eq $proc) {
        throw (New-LifecycleError 'NATIVE_LAUNCH_FAILED' ("NATIVE_LAUNCH_FAILED {0}" -f $ScriptPath))
    }
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        [void](Stop-ProcessTree -RootPid $proc.Id)
        return [pscustomobject]@{ ExitCode = $null; TimedOut = $true; OutFile = $OutFile; ElapsedSeconds = $TimeoutSeconds }
    }
    $elapsed = [int]([DateTime]::UtcNow - $launchedUtc).TotalSeconds
    return [pscustomobject]@{ ExitCode = $proc.ExitCode; TimedOut = $false; OutFile = $OutFile; ElapsedSeconds = $elapsed }
}

function Get-EvidenceBlock {
    <# Parse les lignes KEY=VALUE des gates (preuves machine-parseables).
       Retourne un hashtabule — jamais $null. #>
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$LogPath)
    $h = @{}
    if ([string]::IsNullOrWhiteSpace($LogPath)) { return $h }
    if (-not (Test-Path -LiteralPath $LogPath)) { return $h }
    foreach ($line in @(Get-Content -LiteralPath $LogPath -ErrorAction SilentlyContinue)) {
        if ($line -match '^(TEST_EXIT_CODE|CLEANUP_EXIT_CODE|SERVER_STOPPED|PORT_\d+_FREE|PORT_OWNER_FOREIGN|PROJECT_JVM_LEFT|HTTP_TESTS)=(.*)$') {
            $h[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $h
}

# ---------------------------------------------------------------------------
# PostgreSQL de test (specification epinglee — voir docs §11.5)
# ---------------------------------------------------------------------------

$script:PgContainerName = 'adgendoc-pg-h2'
$script:PgImage = 'postgres:16-alpine'
$script:PgPublish = '127.0.0.1:5460'
$script:PgDb = 'adgendoc'
$script:PgUser = 'adgendoc'

function Ensure-TestPostgres {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Password, [int]$TimeoutSeconds = 60)
    $ErrorActionPreference = 'Stop'
    if ([string]::IsNullOrWhiteSpace($Password)) {
        throw (New-LifecycleError 'PG_PASSWORD_MISSING' 'PG_PASSWORD_MISSING : aucun mot de passe de test fourni (jamais de valeur par defaut, jamais loggue).')
    }
    if ($Password -notmatch '^[A-Za-z0-9._\-]+$') {
        # Charset impose : la valeur est interpolee dans la ligne `docker run`
        # (pas d'espace, pas de metacaractere = pas d'injection d'argument).
        throw (New-LifecycleError 'PG_PASSWORD_INVALID' 'PG_PASSWORD_INVALID : mot de passe hors du charset [A-Za-z0-9._-] (jamais loggue).')
    }
    $daemon = Invoke-NativeBounded -FilePath 'docker' -Arguments 'info' -TimeoutSeconds 20
    if ($daemon.TimedOut -or $daemon.ExitCode -ne 0) {
        throw (New-LifecycleError 'DOCKER_UNAVAILABLE' 'DOCKER_UNAVAILABLE : docker info borne 20s sans succes.')
    }
    $inspect = Invoke-NativeBounded -FilePath 'docker' -Arguments ("ps -a --filter name={0} --format {{{{.Names}}}}" -f $script:PgContainerName) -TimeoutSeconds 20
    $exists = ($inspect.StdOut -split "`r?`n") -contains $script:PgContainerName
    if (-not $exists) {
        $runArgs = 'run -d --name {0} -e POSTGRES_DB={1} -e POSTGRES_USER={2} -e POSTGRES_PASSWORD={3} -p {4}:5432 {5}' -f `
            $script:PgContainerName, $script:PgDb, $script:PgUser, $Password, $script:PgPublish, $script:PgImage
        $run = Invoke-NativeBounded -FilePath 'docker' -Arguments $runArgs -TimeoutSeconds $TimeoutSeconds
        if ($run.TimedOut -or $run.ExitCode -ne 0) {
            $detail = Protect-Secret -Text ($run.StdErr + $run.StdOut) -Secret $Password
            throw (New-LifecycleError 'PG_START_FAILED' ("PG_START_FAILED : {0}" -f $detail.Trim()))
        }
    } else {
        # Meme garde que Stop-TestPostgres : jamais de demarrage d'un conteneur
        # qui n'est pas celui du projet (AGENTS.md section 10).
        $img = Invoke-NativeBounded -FilePath 'docker' `
            -Arguments ("inspect -f {{{{.Config.Image}}}} {0}" -f $script:PgContainerName) -TimeoutSeconds 20
        $image = ''
        if (-not $img.TimedOut -and $img.ExitCode -eq 0) { $image = $img.StdOut.Trim() }
        if ($image -ne $script:PgImage) {
            throw (New-LifecycleError 'CONTAINER_IMAGE_MISMATCH' ("CONTAINER_IMAGE_MISMATCH : conteneur {0} image '{1}' attendue '{2}' : NON demarre, NON supprime." -f $script:PgContainerName, $image, $script:PgImage))
        }
        $start = Invoke-NativeBounded -FilePath 'docker' -Arguments ("start {0}" -f $script:PgContainerName) -TimeoutSeconds $TimeoutSeconds
        if ($start.TimedOut -or ($start.ExitCode -ne 0 -and $start.StdErr -notmatch 'already running')) {
            $detail = Protect-Secret -Text ($start.StdErr + $start.StdOut) -Secret $Password
            throw (New-LifecycleError 'PG_START_FAILED' ("PG_START_FAILED : {0}" -f $detail.Trim()))
        }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $ready = Invoke-NativeBounded -FilePath 'docker' `
            -Arguments ("exec {0} pg_isready -U {1} -d {2}" -f $script:PgContainerName, $script:PgUser, $script:PgDb) -TimeoutSeconds 15
        if (-not $ready.TimedOut -and $ready.ExitCode -eq 0) { return $true }
        Start-Sleep -Seconds 2
    }
    throw (New-LifecycleError 'PG_NOT_READY' ("PG_NOT_READY : pg_isready sans succes pendant {0}s." -f $TimeoutSeconds))
}

function Stop-TestPostgres {
    [CmdletBinding()]
    param([int]$TimeoutSeconds = 30)
    $ErrorActionPreference = 'Stop'
    # Jamais de suppression a l'aveugle (AGENTS.md section 10) : verifier que
    # le conteneur est bien celui du projet avant docker rm -f.
    $inspect = Invoke-NativeBounded -FilePath 'docker' `
        -Arguments ("inspect -f {{{{.Config.Image}}}} {0}" -f $script:PgContainerName) -TimeoutSeconds 20
    if ($inspect.TimedOut -or $inspect.ExitCode -ne 0) { return $false }
    $image = $inspect.StdOut.Trim()
    if ($image -ne $script:PgImage) {
        Write-Warning ("Stop-TestPostgres : image '{0}' attendue '{1}' : conteneur NON supprime." -f $image, $script:PgImage)
        return $false
    }
    $rm = Invoke-NativeBounded -FilePath 'docker' -Arguments ("rm -f {0}" -f $script:PgContainerName) -TimeoutSeconds $TimeoutSeconds
    return ($rm.TimedOut -eq $false -and $rm.ExitCode -eq 0)
}

# ---------------------------------------------------------------------------
# Session serveur
# ---------------------------------------------------------------------------

function New-TestServerSession {
    [CmdletBinding()]
    param([int]$Port, [string]$JarPath, [string]$JavaExe, [string]$OutLog, [string]$ErrLog)
    return [pscustomobject]@{
        RootPid            = 0
        RootStartTime      = $null
        StartTime          = $null
        Port               = $Port
        JarPath            = $JarPath
        JavaExe            = $JavaExe
        OutLog             = $OutLog
        ErrLog             = $ErrLog
        ListeningPid       = $null
        DiscoveredJvms     = (New-Object System.Collections.ArrayList)  # @{Pid;StartTime}
        RecordedPorts      = (New-Object System.Collections.ArrayList)
        PreExistingJvms    = (New-Object System.Collections.ArrayList)  # PID java.exe deja presents avant start
        Adopted            = $false
        Stopped            = $false
        StopResult         = $null
        StartupObservations = 0
    }
}

function Update-SessionJvms {
    <# Inventaire des JVM du projet appartenant a la session : la racine ELLE-MEME
       (c'est souvent le JVM, sans enfant) + sa descendance, + les ports TCP
       qu'elles ecoutent (L4 : SERVER_PORT divergent). #>
    [CmdletBinding()]
    param($Session)
    $ErrorActionPreference = 'Stop'
    $candidates = New-Object System.Collections.ArrayList
    if ([int]$Session.RootPid -gt 0) {
        $rootProc = Get-CimInstance -ClassName Win32_Process -Filter ("ProcessId = {0}" -f [int]$Session.RootPid) -ErrorAction SilentlyContinue
        if ($null -ne $rootProc) {
            [void]$candidates.Add([pscustomobject]@{
                    Pid         = [int]$rootProc.ProcessId
                    Name        = [string]$rootProc.Name
                    CommandLine = $(if ($rootProc.CommandLine) { [string]$rootProc.CommandLine } else { $null })
                })
        }
    }
    foreach ($desc in @(Get-DescendantRecords -RootPid ([int]$Session.RootPid))) { [void]$candidates.Add($desc) }
    $observed = 0
    foreach ($rec in $candidates) {
        if ($rec.Name -ne 'java.exe') { continue }
        if ([int]$rec.Pid -le 0) { continue }
        $observed++
        $known = $false
        foreach ($jvm in $Session.DiscoveredJvms) { if ([int]$jvm.Pid -eq [int]$rec.Pid) { $known = $true; break } }
        if (-not $known) {
            [void]$Session.DiscoveredJvms.Add(@{ Pid = [int]$rec.Pid; StartTime = (Get-SafeProcessStartTime -ProcessId ([int]$rec.Pid)) })
        }
        $conns = @(Get-NetTCPConnection -OwningProcess ([int]$rec.Pid) -State Listen -ErrorAction SilentlyContinue)
        foreach ($conn in $conns) {
            $lp = [int]$conn.LocalPort
            if (-not $Session.RecordedPorts.Contains($lp)) { [void]$Session.RecordedPorts.Add($lp) }
        }
    }
    $Session.StartupObservations = $observed
}

function Start-TestServer {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$JarPath,
        [int]$Port = 18099,
        [hashtable]$Environment = @{},
        [int]$StartupTimeoutSeconds = 60,
        [string]$LogDirectory = (Join-Path $env:TEMP 'opencode\phaseh\logs'),
        [System.Management.Automation.PSReference]$SessionRef = $null,
        [string]$JavaPath = '',
        [switch]$AdoptOrphan
    )
    $ErrorActionPreference = 'Stop'

    $jarFull = [System.IO.Path]::GetFullPath($JarPath)
    if (-not (Test-Path -LiteralPath $jarFull)) {
        throw (New-LifecycleError 'JAR_MISSING' ("JAR_MISSING : {0}" -f $jarFull))
    }

    # --- pre-existants : JVM du projet deja presentes = jamais tuees (L5/L6) ---
    $preExisting = New-Object System.Collections.ArrayList
    foreach ($rec in @(Get-ProjectJvmRecords -JarPath $jarFull)) {
        if ([int]$rec.Pid -gt 0) { [void]$preExisting.Add([int]$rec.Pid) }
    }

    # --- fail fast sur le port : on ne tue JAMAIS le proprietaire ---
    $ownerPid = Get-PortListenerPid -Port $Port
    $adoptedPid = $null
    if ($null -ne $ownerPid) {
        $ownerName = 'inconnu'; $ownerCmd = ''
        try {
            $ownerProc = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $ownerPid"
            if ($null -ne $ownerProc) { $ownerName = [string]$ownerProc.Name; if ($ownerProc.CommandLine) { $ownerCmd = [string]$ownerProc.CommandLine } }
        } catch { }
        # Adoption sur preuve forte : chemin COMPLET du jar uniquement (un jar
        # homonyme d'un autre checkout n'est jamais adopte ni tue).
        $ownerIsProject = ($ownerName -eq 'java.exe') -and
        (-not [string]::IsNullOrWhiteSpace($ownerCmd)) -and
        ($ownerCmd.TrimEnd('"').EndsWith($jarFull, [System.StringComparison]::OrdinalIgnoreCase))
        if ($AdoptOrphan -and $ownerIsProject) {
            $adoptedPid = $ownerPid
        } else {
            throw (New-LifecycleError 'PORT_OCCUPIED' ("PORT_OCCUPIED port={0} ownerPid={1} ownerName={2} : port deja occupe avant demarrage, proprietaire NON tue (fail fast)." -f $Port, $ownerPid, $ownerName))
        }
    }

    if (-not (Test-Path -LiteralPath $LogDirectory)) { New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null }
    $tag = '{0}-{1}-{2}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Port, ([guid]::NewGuid().ToString('N').Substring(0, 6))
    $outLog = Join-Path $LogDirectory "server-$tag.out.log"
    $errLog = Join-Path $LogDirectory "server-$tag.err.log"
    $javaExe = Resolve-TestJava
    if (-not [string]::IsNullOrWhiteSpace($JavaPath)) {
        # Surcharge volontaire (tests L2) : permet de reproduire le lanceur
        # « javapath » qui spawn un java.exe enfant (cause racine H.3).
        if (-not (Test-Path -LiteralPath $JavaPath)) {
            throw (New-LifecycleError 'JAVA_NOT_FOUND' ("JAVA_NOT_FOUND : {0}" -f $JavaPath))
        }
        $javaExe = (Resolve-Path -LiteralPath $JavaPath).Path
    }

    $session = New-TestServerSession -Port $Port -JarPath $jarFull -JavaExe $javaExe -OutLog $outLog -ErrLog $errLog
    foreach ($prePid in $preExisting) { [void]$session.PreExistingJvms.Add($prePid) }
    # Session publiee AVANT toute attente : [ref] fonctionne a travers les
    # frontieres de module (un -Scope 1 resterait dans la session du module).
    if ($null -ne $SessionRef) { $SessionRef.Value = $session }

    try {
        if ($null -ne $adoptedPid) {
            # Orphelin adopte : la session devient responsable de son arret.
            $session.Adopted = $true
            $session.RootPid = [int]$adoptedPid
            $session.RootStartTime = Get-SafeProcessStartTime -ProcessId ([int]$adoptedPid)
            $session.StartTime = Get-Date
            $session.ListeningPid = [int]$adoptedPid
            if (-not $session.RecordedPorts.Contains($Port)) { [void]$session.RecordedPorts.Add($Port) }
        } else {
            # Snapshot puis restauration de l'environnement : l'enfant recoit les
            # valeurs au moment du CreateProcess uniquement.
            $saved = @{}
            foreach ($key in $Environment.Keys) {
                $exists = Test-Path -LiteralPath "Env:$key"
                $value = $null
                if ($exists) { $value = (Get-Item -LiteralPath "Env:$key").Value }
                $saved[$key] = @{ Exists = $exists; Value = $value }
            }
            try {
                foreach ($key in $Environment.Keys) { Set-Item -LiteralPath "Env:$key" -Value ([string]$Environment[$key]) }
                $argList = '-jar "{0}"' -f $jarFull
                # Redirection FICHIERS : pas d'héritage du pipe de l'orchestrateur
                # (une attente de EOF sur ce pipe fige OpenCode — cause H.3).
                $launched = Start-Process -FilePath $javaExe -ArgumentList $argList `
                    -WorkingDirectory $script:ModuleRepoRoot `
                    -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
                    -PassThru -NoNewWindow
                if ($null -eq $launched) {
                    throw (New-LifecycleError 'LAUNCH_FAILED' 'LAUNCH_FAILED : Start-Process sans processus resultat.')
                }
                $session.RootPid = [int]$launched.Id
                $session.RootStartTime = Get-SafeProcessStartTime -ProcessId $session.RootPid
                $session.StartTime = Get-Date
            } catch {
                if ($null -eq $session.RootPid -or $session.RootPid -eq 0) {
                    throw (New-LifecycleError 'LAUNCH_FAILED' ("LAUNCH_FAILED : {0}" -f $_.Exception.Message))
                }
                throw
            } finally {
                foreach ($key in $saved.Keys) {
                    if ($saved[$key].Exists) { Set-Item -LiteralPath "Env:$key" -Value $saved[$key].Value }
                    else { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
                }
            }
        }

        # --- attente de sante, bornee par StartupTimeoutSeconds ---
        $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
        $healthy = $false
        $failureCode = 'STARTUP_TIMEOUT'
        $failureMessage = ("STARTUP_TIMEOUT : aucun /actuator/health UP sur {0} en {1}s." -f $Port, $StartupTimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            Update-SessionJvms -Session $session
            if ($session.RootPid -gt 0 -and $session.StartupObservations -eq 0) {
                $rootAlive = $null -ne (Get-SafeProcessStartTime -ProcessId $session.RootPid)
                if (-not $rootAlive -and -not $session.Adopted) {
                    $failureCode = 'STARTUP_DIED'
                    $failureMessage = ("STARTUP_DIED : le processus racine {0} a quitte avant tout /actuator/health (voir {1})." -f $session.RootPid, $errLog)
                    break
                }
            }
            try {
                $resp = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/actuator/health" -f $Port) -UseBasicParsing -TimeoutSec 3
                # PS 5.1 : .Content peut etre un tableau d'octets sur ce type de
                # reponse (l'octet cast en chaine donne « 123 34 115 ... ») :
                # on lit donc RawContentStream comme le gate historique.
                $text = ''
                if ($null -ne $resp.RawContentStream) {
                    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
                }
                if ([string]::IsNullOrWhiteSpace($text)) { $text = [string]$resp.Content }
                if ($resp.StatusCode -eq 200 -and $text -match '"status"\s*:\s*"UP"') { $healthy = $true; break }
            } catch { }
            Start-Sleep -Seconds 2
        }
        if (-not $healthy) { throw (New-LifecycleError $failureCode $failureMessage) }

        # --- le listener DOIT appartenir a la session (TOCTOU / port volé) ---
        Update-SessionJvms -Session $session
        $listeningPid = Get-PortListenerPid -Port $Port
        if ($null -eq $listeningPid) {
            throw (New-LifecycleError 'STARTUP_DIED' ("STARTUP_DIED : health UP mais aucun listener sur {0}." -f $Port))
        }
        $lineage = @([int]$session.RootPid)
        foreach ($jvm in $session.DiscoveredJvms) { $lineage += [int]$jvm.Pid }
        if ($lineage -notcontains [int]$listeningPid) {
            throw (New-LifecycleError 'PORT_FOREIGN' ("PORT_FOREIGN port={0} ownerPid={1} : le listener n'appartient pas a la session, NON tue." -f $Port, $listeningPid))
        }
        $session.ListeningPid = [int]$listeningPid
        if (-not $session.RecordedPorts.Contains($Port)) { [void]$session.RecordedPorts.Add($Port) }
        Update-SessionJvms -Session $session
        return $session
    } catch {
        # B2 : TOUT chemin d'erreur apres creation de la session nettoie la session.
        try { [void](Stop-TestServer -Session $session) } catch { }
        throw
    }
}

function Stop-TestServer {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Session,
        [int]$GracefulSeconds = 0,
        [int]$PortReleaseSeconds = 15
    )
    $ErrorActionPreference = 'Stop'
    if ($Session.Stopped -and $null -ne $Session.StopResult) { return $Session.StopResult }

    $notes = New-Object System.Collections.ArrayList
    $jarPath = [string]$Session.JarPath
    $killEntries = New-Object System.Collections.ArrayList  # @{Pid;StartTime;Depth;Why}

    function Add-KillEntry([int]$PidToAdd, $StartTime, [int]$Depth, [string]$Why) {
        if ($PidToAdd -le 0) { return }  # garde : aucun PID fantome (0/negatif)
        foreach ($existing in $killEntries) { if ([int]$existing.Pid -eq $PidToAdd) { return } }
        [void]$killEntries.Add(@{ Pid = $PidToAdd; StartTime = $StartTime; Depth = $Depth; Why = $Why })
    }

    # 1) PID enregistres pendant le demarrage (racine, JVM decouvertes, listener)
    $rootStartTime = $Session.RootStartTime
    Add-KillEntry -PidToAdd ([int]$Session.RootPid) -StartTime $rootStartTime -Depth 0 -Why 'root'
    foreach ($jvm in @($Session.DiscoveredJvms)) { Add-KillEntry -PidToAdd ([int]$jvm.Pid) -StartTime $jvm.StartTime -Depth 1 -Why 'jvm-decouverte' }
    if ($null -ne $Session.ListeningPid) { Add-KillEntry -PidToAdd ([int]$Session.ListeningPid) -StartTime $null -Depth 1 -Why 'listener' }

    # 2) descendance fraiche — seulement si la racine existe toujours AVEC la
    #    meme heure de demarrage (garde anti-reutilisation de PID, M3)
    $rootValid = $false
    if ([int]$Session.RootPid -gt 0) {
        $currentStart = Get-SafeProcessStartTime -ProcessId ([int]$Session.RootPid)
        if ($null -ne $currentStart) {
            if ($null -eq $rootStartTime -or $currentStart -eq $rootStartTime) {
                $rootValid = $true
                foreach ($desc in @(Get-DescendantRecords -RootPid ([int]$Session.RootPid))) {
                    $descStart = Get-SafeProcessStartTime -ProcessId ([int]$desc.Pid)
                    Add-KillEntry -PidToAdd ([int]$desc.Pid) -StartTime $descStart -Depth ([int]$desc.Depth) -Why 'descendance'
                }
            } else {
                [void]$notes.Add("racine $($Session.RootPid) reutilisee (StartTime different) : descendance non ajoutee")
            }
        }
    }

    # 3) preuve forte : java.exe dont le CommandLine se termine par le jar du
    #    projet, sauf les JVM preexistantes a la session (jamais tuees)
    foreach ($rec in @(Get-ProjectJvmRecords -JarPath $jarPath)) {
        if ($Session.PreExistingJvms -contains [int]$rec.Pid) {
            [void]$notes.Add("jvm preexistante non tuee pid=$($rec.Pid)")
            continue
        }
        Add-KillEntry -PidToAdd ([int]$rec.Pid) -StartTime $null -Depth 5 -Why 'cmdline-jar'
    }

    # 4) validation d'identite : StartTime doit toujours correspondre (PID reuse)
    $validated = New-Object System.Collections.ArrayList
    foreach ($entry in $killEntries) {
        if ($null -ne $entry.StartTime) {
            $now = Get-SafeProcessStartTime -ProcessId ([int]$entry.Pid)
            if ($null -ne $now -and $now -ne $entry.StartTime) {
                [void]$notes.Add("pid $($entry.Pid) reutilise (StartTime different) : exclu du kill")
                continue
            }
        }
        [void]$validated.Add($entry)
    }

    # 5) arret : plus profond d'abord, puis racine ; agrège les erreurs
    $killFailures = 0
    foreach ($entry in @($validated | Sort-Object -Property @{ Expression = { $_.Depth }; Descending = $true })) {
        $proc = Get-Process -Id ([int]$entry.Pid) -ErrorAction SilentlyContinue
        if ($null -eq $proc) { continue }
        try {
            Stop-Process -Id ([int]$entry.Pid) -Force -ErrorAction Stop
        } catch {
            # Course possible : le processus peut s'etre termine tout seul
            # entre le test d'existence et l'arret => ce n'est pas un echec.
            $stillThere = $null -ne (Get-Process -Id ([int]$entry.Pid) -ErrorAction SilentlyContinue)
            if ($stillThere) {
                $killFailures++
                [void]$notes.Add("kill pid $($entry.Pid) ($($entry.Why)) : $($_.Exception.Message)")
            } else {
                [void]$notes.Add("pid $($entry.Pid) ($($entry.Why)) deja termine avant l'arret")
            }
        }
    }

    # 6) verification du port (bornée PortReleaseSeconds) — preuve, pas d'arme
    $portFree = $false
    $deadline = (Get-Date).AddSeconds($PortReleaseSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Get-PortListenerPid -Port ([int]$Session.Port))) { $portFree = $true; break }
        Start-Sleep -Milliseconds 500
    }
    $portOwnerPid = Get-PortListenerPid -Port ([int]$Session.Port)
    $portOwnerIsForeign = $false
    if (-not $portFree) {
        $ownerInSet = $false
        foreach ($entry in $validated) { if ([int]$entry.Pid -eq [int]$portOwnerPid) { $ownerInSet = $true; break } }
        if ($ownerInSet) {
            [void]$notes.Add("port $($Session.Port) toujours tenu par le pid $portOwnerPid (session) apres kill")
        } else {
            $portOwnerIsForeign = $true
            [void]$notes.Add("port $($Session.Port) tenu par un pid etranger $portOwnerPid : NON tue")
        }
    }

    # 7) ports annexes enregistres (L4 : SERVER_PORT divergent)
    $extraPortsFree = $true
    foreach ($recordedPort in @($Session.RecordedPorts)) {
        if ([int]$recordedPort -eq [int]$Session.Port) { continue }
        if ($null -ne (Get-PortListenerPid -Port ([int]$recordedPort))) {
            $extraPortsFree = $false
            [void]$notes.Add("port $($recordedPort) toujours en ecoute")
        }
    }

    # 8) JVM residuelles appartenant a la session
    $lineageLeft = 0
    $globalLeft = 0
    foreach ($rec in @(Get-ProjectJvmRecords -JarPath $jarPath)) {
        if ($Session.PreExistingJvms -contains [int]$rec.Pid) { continue }
        $globalLeft++
        foreach ($entry in $validated) { if ([int]$entry.Pid -eq [int]$rec.Pid) { $lineageLeft++; break } }
    }
    if ($rootValid -and $null -ne (Get-SafeProcessStartTime -ProcessId ([int]$Session.RootPid))) { $lineageLeft++ }

    $result = [pscustomobject]@{
        Stopped            = ($killFailures -eq 0 -and $lineageLeft -eq 0)
        PortFree           = [bool]$portFree
        ExtraPortsFree     = [bool]$extraPortsFree
        LineageJvmLeft     = [int]$lineageLeft
        GlobalProjectJvmLeft = [int]$globalLeft
        PortOwnerIsForeign = [bool]$portOwnerIsForeign
        KilledPids         = @($validated | ForEach-Object { [int]$_.Pid })
        Detail             = ($notes -join ' | ')
    }
    $Session.Stopped = $true
    $Session.StopResult = $result
    return $result
}

function Test-TestServerSessionClean {
    [CmdletBinding()]
    param($Result)
    if ($null -eq $Result) { return $false }
    return ([bool]$Result.Stopped -and [bool]$Result.PortFree -and [bool]$Result.ExtraPortsFree -and [int]$Result.LineageJvmLeft -eq 0 -and -not [bool]$Result.PortOwnerIsForeign)
}

Export-ModuleMember -Function @(
    'New-LifecycleError', 'Get-LifecycleCode', 'Protect-Secret',
    'Get-DescendantRecords', 'Get-ChildProcessIds', 'Stop-ProcessTree',
    'Get-PortListenerPid', 'Test-PortFree', 'Assert-PortFree',
    'Resolve-TestJava', 'Get-ProjectJvmRecords', 'Test-ProjectJvmCount',
    'Invoke-NativeBounded', 'Invoke-PowerShellBounded', 'Get-EvidenceBlock',
    'Ensure-TestPostgres', 'Stop-TestPostgres',
    'New-TestServerSession', 'Start-TestServer', 'Stop-TestServer', 'Test-TestServerSessionClean'
)
