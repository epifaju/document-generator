# Phase H.3 - tests de regression du cycle de vie du serveur de test (Windows).
#
# NB ENCODING : ce fichier est en ASCII pur. Windows PowerShell 5.1 lit un
# fichier .ps1 sans BOM en ANSI : un tiret cote UTF-8 (U+2014 = octets E2 80 94)
# devient un guillemet CP1252 (U+201D), que PowerShell accepte comme delimiteur
# de chaine : le parse casse alors silencieusement. Pas de caractere non-ASCII.
#
# Cas L1..L7 (spec H.3) : chaque cas demarre, verifie puis NETTOIE dans son
# propre finally ; aucun cas ne laisse de JVM, de port occupe ou de filler.
# Les assertions sont manuelles (pas de Pester) et portent sur des comportements
# observes : port, PID, appartenances, codes de sortie, preuves KEY=VALUE.
#
#   L1  start -> health UP -> stop -> port 18099 ferme -> 0 JVM residuelle
#   L2  racine "shim" (javapath) + enfant java.exe : TOUS les deux arretes
#   L3  -SimulateTestFailure : exit != 0 MAIS cleanup complet (preuves)
#   L4  SERVER_PORT=18100 divergent : STARTUP_TIMEOUT (code 9), JVM observee,
#       cleanup interne : ports 18099/18100 libres, 0 JVM du projet
#   L5  port 18099 deja occupe : fail fast PORT_OCCUPIED (8), occupier NON tue,
#       et le gate enfant -ManageServer rend aussi exit 8 sans rien tuer
#   L6  JVM sans rapport (filler) jamais touchee
#   L7  gate complet via run_http_e2e.ps1 -ManageServer : exit 0,
#       HTTP_TESTS=11, SERVER_STOPPED=True, PORT libre, 0 JVM
#
# Usage :
#   $env:E2E_JDBC_PASSWORD='<mot de passe de test ephemere>'
#   powershell -ExecutionPolicy Bypass -File tests/e2e/lifecycle_tests.ps1
#
# Sortie : 0 = tous les cas PASS ; 1 = au moins un FAIL ; 3 = mot de passe
# absent ; 4 = prerequis (jar, template, stockage).

[CmdletBinding()]
param(
    [string]$JarPath = '',
    [string]$StorageRoot = (Join-Path $env:TEMP 'opencode\phaseh\storage'),
    [string]$TemplateFile = (Join-Path $env:TEMP 'opencode\phaseh\templates\attestation_concordance_v1.docx'),
    [string]$LogDirectory = (Join-Path $env:TEMP 'opencode\phaseh\logs'),
    [int]$Port = 18099,
    [int]$AltPort = 18100,
    [int]$StartupTimeoutSeconds = 60,
    [int]$L4StartupTimeoutSeconds = 15,
    [int]$GateTimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'ServerLifecycle.psm1') -Force -DisableNameChecking

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $script:RepoRoot 'backend\target\document-generator-1.0.0-SNAPSHOT.jar'
}
$script:JarPath = $JarPath
$script:DbPassword = $env:E2E_JDBC_PASSWORD
$script:StorageRoot = $StorageRoot
$script:TemplateFile = $TemplateFile
$script:TemplateDir = Split-Path -Parent $TemplateFile
$script:Port = $Port
$script:AltPort = $AltPort
$script:LogDirectory = $LogDirectory
$script:Checks = New-Object System.Collections.ArrayList

# ------------------------------------------------------------ assertions
function Add-Check {
    param([string]$Case, [string]$Label, $Ok, [string]$Detail = '')
    $okBool = [bool]$Ok
    [void]$script:Checks.Add([pscustomobject]@{ Case = $Case; Label = $Label; Ok = $okBool; Skip = $false; Detail = $Detail })
    if ($okBool) { Write-Host ("  PASS [{0}] {1}" -f $Case, $Label) -ForegroundColor Green }
    else { Write-Host ("  FAIL [{0}] {1} :: {2}" -f $Case, $Label, $Detail) -ForegroundColor Red }
}

function Add-Skip {
    param([string]$Case, [string]$Label, [string]$Why)
    [void]$script:Checks.Add([pscustomobject]@{ Case = $Case; Label = $Label; Ok = $true; Skip = $true; Detail = $Why })
    Write-Host ("  SKIP [{0}] {1} :: {2}" -f $Case, $Label, $Why) -ForegroundColor Yellow
}

function Get-CaseStatus {
    param([string]$Case)
    $items = @($script:Checks | Where-Object { $_.Case -eq $Case })
    if ($items.Count -eq 0) { return 'FAIL' }   # cas jamais execute = FAIL
    foreach ($item in $items) { if (-not $item.Ok) { return 'FAIL' } }
    foreach ($item in $items) { if ($item.Skip) { return 'SKIP' } }
    return 'PASS'
}

# ------------------------------------------------------------ utilitaires
function New-ServerEnvironment {
    param([int]$ServerPort)
    return @{
        SERVER_PORT                = [string]$ServerPort
        SPRING_DATASOURCE_URL      = 'jdbc:postgresql://127.0.0.1:5460/adgendoc'
        SPRING_DATASOURCE_USERNAME = 'adgendoc'
        SPRING_DATASOURCE_PASSWORD = $script:DbPassword
        MIGRATIONS_DIR             = (Join-Path $script:RepoRoot 'database\migrations')
        TEMPLATE_DIR               = $script:TemplateDir
        DOCUMENT_STORAGE_PATH      = $script:StorageRoot
    }
}

function Get-HealthText {
    param([int]$HPort)
    try {
        $resp = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/actuator/health" -f $HPort) -UseBasicParsing -TimeoutSec 3
        $text = ''
        if ($null -ne $resp.RawContentStream) {
            $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        }
        if ([string]::IsNullOrWhiteSpace($text)) { $text = [string]$resp.Content }
        if ($resp.StatusCode -eq 200) { return $text }
        return $null
    } catch { return $null }
}

function Test-Dead {
    param([int]$ProcessId, [int]$TimeoutSeconds = 5)
    if ($ProcessId -le 0) { return $true }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Stop-SessionIfAny {
    param($Session)
    if ($null -eq $Session) { return }
    if ($Session.Stopped) { return }
    try { [void](Stop-TestServer -Session $Session) } catch {
        Write-Host "  AVERTISSEMENT: nettoyage session: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------------ L1
function Invoke-L1 {
    $session = $null
    try {
        $session = Start-TestServer -JarPath $script:JarPath -Port $script:Port `
            -Environment (New-ServerEnvironment -ServerPort $script:Port) `
            -StartupTimeoutSeconds $StartupTimeoutSeconds -LogDirectory $script:LogDirectory `
            -SessionRef ([ref]$session)
        Add-Check 'L1' 'session publiee au lancement' ($null -ne $session) 'session nulle'
        Add-Check 'L1' 'PID racine > 0' ([int]$session.RootPid -gt 0) "root=$($session.RootPid)"
        $health = Get-HealthText -HPort $script:Port
        Add-Check 'L1' 'GET /actuator/health = UP' ("$health" -match '"status"\s*:\s*"UP"') "reponse=$health"
        $listener = Get-PortListenerPid -Port $script:Port
        Add-Check 'L1' 'listener identifie' ($null -ne $listener) 'aucun listener'
        Add-Check 'L1' 'listener appartient a la session' ([int]$session.ListeningPid -eq [int]$listener) `
            "session=$($session.ListeningPid) listener=$listener"
        $result = Stop-TestServer -Session $session
        Add-Check 'L1' 'Stop-TestServer: Stopped' ([bool]$result.Stopped) $result.Detail
        Add-Check 'L1' 'Stop-TestServer: PortFree' ([bool]$result.PortFree) $result.Detail
        Add-Check 'L1' 'Stop-TestServer: aucune JVM de la session residuelle' ([int]$result.LineageJvmLeft -eq 0) `
            "residuelles=$($result.LineageJvmLeft)"
        Add-Check 'L1' 'port 18099 libre apres arret' (Test-PortFree -Port $script:Port)
        Add-Check 'L1' '0 JVM du projet apres arret' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
            "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
    } finally { Stop-SessionIfAny -Session $session }
}

# ------------------------------------------------------------------ L2
function Invoke-L2 {
    $session = $null
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    $shim = ''
    if ($null -ne $javaCmd) { $shim = [string]$javaCmd.Source }
    $real = Resolve-TestJava
    if ([string]::IsNullOrWhiteSpace($shim) -or ((Resolve-Path -LiteralPath $shim).Path -ieq $real)) {
        Add-Skip 'L2' 'shim java (javapath) distinct du JDK detecte' "java PATH='$shim' JAVA_HOME='$real' : arbre parent/enfant non reproductible ici"
        return
    }
    try {
        $session = Start-TestServer -JarPath $script:JarPath -Port $script:Port `
            -Environment (New-ServerEnvironment -ServerPort $script:Port) `
            -StartupTimeoutSeconds $StartupTimeoutSeconds -LogDirectory $script:LogDirectory `
            -SessionRef ([ref]$session) -JavaPath $shim
        $descRecs = @(Get-DescendantRecords -RootPid ([int]$session.RootPid))
        $descPids = @($descRecs | ForEach-Object { [int]$_.Pid })
        if ($descPids.Count -eq 0) {
            Add-Skip 'L2' 'shim spawn un java.exe enfant' 'aucun enfant observe sur cette machine'
            return
        }
        Add-Check 'L2' 'arbre shim -> enfant observe' ($descPids.Count -ge 1) "desc=$($descPids -join ',')"
        $javaChildren = @($descRecs | Where-Object { $_.Name -eq 'java.exe' })
        Add-Check 'L2' 'enfant java.exe reel present' ($javaChildren.Count -ge 1) `
            "enfants=$(($descRecs | ForEach-Object { $_.Name }) -join ',')"
        $listener = Get-PortListenerPid -Port $script:Port
        $listenerInLineage = ($null -ne $listener) -and (($descPids -contains [int]$listener) -or ([int]$session.RootPid -eq [int]$listener))
        Add-Check 'L2' 'listener = descendance du shim (cause racine H.3)' $listenerInLineage `
            "listener=$listener root=$($session.RootPid) desc=$($descPids -join ',')"
        $allPids = @([int]$session.RootPid) + $descPids
        $result = Stop-TestServer -Session $session
        Add-Check 'L2' 'Stop-TestServer: propre' (Test-TestServerSessionClean -Result $result) $result.Detail
        $alive = @()
        foreach ($pidToCheck in $allPids) {
            if (-not (Test-Dead -ProcessId $pidToCheck -TimeoutSeconds 5)) { $alive += $pidToCheck }
        }
        Add-Check 'L2' 'parent shim + descendants tous arretes' ($alive.Count -eq 0) "survivants=$($alive -join ',')"
        Add-Check 'L2' 'port 18099 libre' (Test-PortFree -Port $script:Port)
        Add-Check 'L2' '0 JVM du projet residuelle' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
            "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
    } finally { Stop-SessionIfAny -Session $session }
}

# ------------------------------------------------------------------ L3
function Invoke-L3 {
    $outFile = Join-Path $script:LogDirectory 'lifecycle-L3.log'
    $run = Invoke-PowerShellBounded -ScriptPath (Join-Path $PSScriptRoot 'run_http_e2e.ps1') `
        -ScriptArgs @('-ManageServer', '-ManagePostgres', '-SimulateTestFailure',
            '-StorageRoot', $script:StorageRoot, '-TemplateFile', $script:TemplateFile) `
        -TimeoutSeconds $GateTimeoutSeconds -OutFile $outFile
    Add-Check 'L3' 'execution bornee (pas de timeout)' (-not $run.TimedOut) "TimedOut=$($run.TimedOut)"
    Add-Check 'L3' 'code de sortie non nul (test KO signale)' (($null -ne $run.ExitCode) -and ([int]$run.ExitCode -ne 0)) `
        "exit=$($run.ExitCode)"
    $ev = Get-EvidenceBlock -LogPath $outFile
    Add-Check 'L3' 'preuve TEST_EXIT_CODE=1' ($ev['TEST_EXIT_CODE'] -eq '1') "got=$($ev['TEST_EXIT_CODE'])"
    Add-Check 'L3' 'preuve SERVER_STOPPED=True (cleanup malgre echec)' ($ev['SERVER_STOPPED'] -eq 'True') `
        "got=$($ev['SERVER_STOPPED'])"
    Add-Check 'L3' ("preuve PORT_{0}_FREE=True" -f $script:Port) ($ev["PORT_$($script:Port)_FREE"] -eq 'True') `
        "got=$($ev["PORT_$($script:Port)_FREE"])"
    Add-Check 'L3' 'preuve PROJECT_JVM_LEFT=0' ($ev['PROJECT_JVM_LEFT'] -eq '0') "got=$($ev['PROJECT_JVM_LEFT'])"
    Add-Check 'L3' 'verification directe: port libre' (Test-PortFree -Port $script:Port)
    Add-Check 'L3' 'verification directe: 0 JVM du projet' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
        "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
}

# ------------------------------------------------------------------ L4
function Invoke-L4 {
    $session = $null
    Add-Check 'L4' ("prerequis: port {0} libre" -f $script:AltPort) (Test-PortFree -Port $script:AltPort)
    try {
        $threw = $false
        $code = $null
        $message = ''
        try {
            # Le serveur demarre sur SERVER_PORT=18100 pendant que la session
            # surveille 18099 : aucun /actuator/health sur 18099 => timeout borne.
            $session = Start-TestServer -JarPath $script:JarPath -Port $script:Port `
                -Environment (New-ServerEnvironment -ServerPort $script:AltPort) `
                -StartupTimeoutSeconds $L4StartupTimeoutSeconds -LogDirectory $script:LogDirectory `
                -SessionRef ([ref]$session)
        } catch {
            $threw = $true
            $code = Get-LifecycleCode -ErrorRecord $_
            $message = $_.Exception.Message
        }
        Add-Check 'L4' 'demarrage en echec (timeout attendu)' $threw "aucune exception; session=$($null -ne $session)"
        Add-Check 'L4' 'code lifecycle STARTUP_TIMEOUT' ($code -eq 'STARTUP_TIMEOUT') "code=$code msg=$message"
        Add-Check 'L4' 'session observable (publiee avant attente)' ($null -ne $session) 'session nulle'
        if ($null -ne $session) {
            Add-Check 'L4' 'JVM observee pendant le demarrage (anti-vacuite)' ([int]$session.StartupObservations -gt 0) `
                "observations=$($session.StartupObservations) jvms=$($session.DiscoveredJvms.Count)"
            Add-Check 'L4' 'cleanup interne execute (session.Stopped)' ([bool]$session.Stopped) `
                "stopped=$($session.Stopped)"
        }
        Add-Check 'L4' ("port {0} (divergent) libre" -f $script:AltPort) (Test-PortFree -Port $script:AltPort)
        Add-Check 'L4' ("port {0} libre" -f $script:Port) (Test-PortFree -Port $script:Port)
        Add-Check 'L4' '0 JVM du projet residuelle' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
            "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
    } finally { Stop-SessionIfAny -Session $session }
}

# ------------------------------------------------------------------ L5
function Invoke-L5 {
    $occupier = $null
    $session = $null
    $occupierKilled = $false
    $listenerFile = Join-Path $script:LogDirectory 'lifecycle-L5-occupier.ps1'
    $occOut = Join-Path $script:LogDirectory 'lifecycle-L5-occupier.out.log'
    $occErr = Join-Path $script:LogDirectory 'lifecycle-L5-occupier.err.log'
    try {
        if (-not (Test-Path -LiteralPath $script:LogDirectory)) { New-Item -ItemType Directory -Force -Path $script:LogDirectory | Out-Null }
        $listenerBody = @'
$l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, __PORT__)
$l.Start()
Start-Sleep -Seconds 600
'@
        Set-Content -LiteralPath $listenerFile -Value ($listenerBody -replace '__PORT__', [string]$script:Port) -Encoding ASCII
        $occupier = Start-Process -FilePath 'powershell.exe' `
            -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $listenerFile) `
            -RedirectStandardOutput $occOut -RedirectStandardError $occErr -PassThru
        Add-Check 'L5' 'occupier de test cree' ($null -ne $occupier) 'Start-Process sans processus'
        $bound = $false
        $owner = $null
        $deadline = (Get-Date).AddSeconds(15)
        while ((Get-Date) -lt $deadline) {
            $owner = Get-PortListenerPid -Port $script:Port
            if ($null -ne $owner -and [int]$owner -eq [int]$occupier.Id) { $bound = $true; break }
            Start-Sleep -Milliseconds 250
        }
        Add-Check 'L5' 'occupier en ecoute sur le port cible' $bound "owner=$owner occupier=$($occupier.Id)"
        if (-not $bound) { return }
        $threw = $false
        $code = $null
        try {
            $session = Start-TestServer -JarPath $script:JarPath -Port $script:Port `
                -Environment (New-ServerEnvironment -ServerPort $script:Port) `
                -StartupTimeoutSeconds $StartupTimeoutSeconds -LogDirectory $script:LogDirectory `
                -SessionRef ([ref]$session)
        } catch {
            $threw = $true
            $code = Get-LifecycleCode -ErrorRecord $_
        }
        Add-Check 'L5' 'fail fast avant lancement' $threw 'aucune exception (le serveur aurait du refuser)'
        Add-Check 'L5' 'code lifecycle PORT_OCCUPIED (exite 8 en gate)' ($code -eq 'PORT_OCCUPIED') "code=$code"
        Add-Check 'L5' 'aucune session creee (echec avant lancement)' ($null -eq $session) 'session creee'
        Add-Check 'L5' 'occupier toujours vivant (proprietaire NON tue)' `
            ($null -ne (Get-Process -Id ([int]$occupier.Id) -ErrorAction SilentlyContinue))
        Add-Check 'L5' 'occupier toujours proprietaire du port' ((Get-PortListenerPid -Port $script:Port) -eq [int]$occupier.Id) `
            "owner=$(Get-PortListenerPid -Port $script:Port)"
        Add-Check 'L5' 'aucune JVM du projet lancee' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
            "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
        # Preuve au niveau du gate lui-meme : exit 8 (port occupe) pendant que
        # l'occupier tient toujours le port (jamais tue, ni par le module ni
        # par le gate). Le gate echoue avant tout lancement de JVM.
        $gateOut = Join-Path $script:LogDirectory 'lifecycle-L5-gate.log'
        $gateRun = Invoke-PowerShellBounded -ScriptPath (Join-Path $PSScriptRoot 'run_http_e2e.ps1') `
            -ScriptArgs @('-ManageServer', '-ManagePostgres',
                '-BaseUrl', ("http://127.0.0.1:{0}" -f $script:Port),
                '-StorageRoot', $script:StorageRoot, '-TemplateFile', $script:TemplateFile) `
            -TimeoutSeconds $GateTimeoutSeconds -OutFile $gateOut
        $gateEv = Get-EvidenceBlock -LogPath $gateOut
        Add-Check 'L5' 'gate: code de sortie 8 (port occupe)' ($gateRun.ExitCode -eq 8) `
            "exit=$($gateRun.ExitCode) timedOut=$($gateRun.TimedOut)"
        Add-Check 'L5' 'gate: preuve PORT non libre' ($gateEv["PORT_$($script:Port)_FREE"] -eq 'False') `
            "evidence=$($gateEv["PORT_$($script:Port)_FREE"])"
        Add-Check 'L5' 'gate: occupier toujours vivant apres execution du gate' `
            ($null -ne (Get-Process -Id ([int]$occupier.Id) -ErrorAction SilentlyContinue))
        Add-Check 'L5' 'gate: aucune JVM du projet lancee' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
            "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
    } finally {
        Stop-SessionIfAny -Session $session
        if ($null -ne $occupier) {
            [void](Stop-ProcessTree -RootPid ([int]$occupier.Id))
            $occupierKilled = Test-Dead -ProcessId ([int]$occupier.Id) -TimeoutSeconds 5
        }
    }
    Add-Check 'L5' 'occupier (proprietaire du test) arrete au nettoyage' $occupierKilled
    Add-Check 'L5' ("port {0} libre apres nettoyage occupier" -f $script:Port) (Test-PortFree -Port $script:Port)
}

# ------------------------------------------------------------------ L6
function Invoke-L6 {
    $filler = $null
    $session = $null
    $fillerDir = Join-Path $script:LogDirectory 'lifecycle-L6'
    $fillerKilled = $false
    try {
        New-Item -ItemType Directory -Force -Path $fillerDir | Out-Null
        $fillerSrc = Join-Path $fillerDir 'Filler.java'
        Set-Content -LiteralPath $fillerSrc -Encoding ASCII -Value @'
public class Filler {
    public static void main(String[] args) throws Exception {
        Thread.sleep(600000);
    }
}
'@
        $filler = Start-Process -FilePath (Resolve-TestJava) `
            -ArgumentList ('"{0}"' -f $fillerSrc) `
            -RedirectStandardOutput (Join-Path $fillerDir 'filler.out.log') `
            -RedirectStandardError (Join-Path $fillerDir 'filler.err.log') `
            -PassThru -NoNewWindow
        Start-Sleep -Seconds 3
        Add-Check 'L6' 'filler java demarre (hors projet)' ($null -ne (Get-Process -Id ([int]$filler.Id) -ErrorAction SilentlyContinue)) `
            "filler=$($filler.Id)"

        $session = Start-TestServer -JarPath $script:JarPath -Port $script:Port `
            -Environment (New-ServerEnvironment -ServerPort $script:Port) `
            -StartupTimeoutSeconds $StartupTimeoutSeconds -LogDirectory $script:LogDirectory `
            -SessionRef ([ref]$session)
        Add-Check 'L6' 'serveur demarre avec filler present' ($null -ne $session)
        $result = Stop-TestServer -Session $session
        Add-Check 'L6' 'arret de session propre' (Test-TestServerSessionClean -Result $result) $result.Detail
        Add-Check 'L6' 'filler exclu du kill set' (@($result.KilledPids) -notcontains [int]$filler.Id) `
            "killed=$(@($result.KilledPids) -join ',')"
        $fillerAlive = ($null -ne (Get-Process -Id ([int]$filler.Id) -ErrorAction SilentlyContinue))
        Add-Check 'L6' 'filler toujours vivant apres arret du serveur' $fillerAlive "filler=$($filler.Id)"
        Add-Check 'L6' 'port 18099 libre' (Test-PortFree -Port $script:Port)
        Add-Check 'L6' '0 JVM du projet residuelle' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
            "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
    } finally {
        Stop-SessionIfAny -Session $session
        if ($null -ne $filler) {
            [void](Stop-ProcessTree -RootPid ([int]$filler.Id))
            $fillerKilled = Test-Dead -ProcessId ([int]$filler.Id) -TimeoutSeconds 5
        }
    }
    Add-Check 'L6' 'filler arrete au nettoyage (mon propre processus)' $fillerKilled
}

# ------------------------------------------------------------------ L7
function Invoke-L7 {
    $outFile = Join-Path $script:LogDirectory 'lifecycle-L7.log'
    $run = Invoke-PowerShellBounded -ScriptPath (Join-Path $PSScriptRoot 'run_http_e2e.ps1') `
        -ScriptArgs @('-ManageServer', '-ManagePostgres',
            '-StorageRoot', $script:StorageRoot, '-TemplateFile', $script:TemplateFile) `
        -TimeoutSeconds $GateTimeoutSeconds -OutFile $outFile
    Add-Check 'L7' 'execution bornee (pas de timeout)' (-not $run.TimedOut) "TimedOut=$($run.TimedOut)"
    Add-Check 'L7' 'code de sortie 0' (($null -ne $run.ExitCode) -and ([int]$run.ExitCode -eq 0)) "exit=$($run.ExitCode)"
    $ev = Get-EvidenceBlock -LogPath $outFile
    Add-Check 'L7' 'preuve TEST_EXIT_CODE=0' ($ev['TEST_EXIT_CODE'] -eq '0') "got=$($ev['TEST_EXIT_CODE'])"
    Add-Check 'L7' 'preuve CLEANUP_EXIT_CODE=0' ($ev['CLEANUP_EXIT_CODE'] -eq '0') "got=$($ev['CLEANUP_EXIT_CODE'])"
    Add-Check 'L7' 'preuve SERVER_STOPPED=True' ($ev['SERVER_STOPPED'] -eq 'True') "got=$($ev['SERVER_STOPPED'])"
    Add-Check 'L7' ("preuve PORT_{0}_FREE=True" -f $script:Port) ($ev["PORT_$($script:Port)_FREE"] -eq 'True') `
        "got=$($ev["PORT_$($script:Port)_FREE"])"
    Add-Check 'L7' 'preuve PORT_OWNER_FOREIGN=False' ($ev['PORT_OWNER_FOREIGN'] -eq 'False') "got=$($ev['PORT_OWNER_FOREIGN'])"
    Add-Check 'L7' 'preuve PROJECT_JVM_LEFT=0' ($ev['PROJECT_JVM_LEFT'] -eq '0') "got=$($ev['PROJECT_JVM_LEFT'])"
    Add-Check 'L7' 'preuve HTTP_TESTS=11' ($ev['HTTP_TESTS'] -eq '11') "got=$($ev['HTTP_TESTS'])"
    Add-Check 'L7' 'verification directe: port libre' (Test-PortFree -Port $script:Port)
    Add-Check 'L7' 'verification directe: 0 JVM du projet' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
        "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"
}

# ================================================================ execution
Write-Host "Lifecycle tests - depot $script:RepoRoot" -ForegroundColor Cyan
Write-Host "Ports cibles : $Port (session), $AltPort (divergent L4) ; logs: $script:LogDirectory"

if ([string]::IsNullOrWhiteSpace($script:DbPassword)) {
    Write-Host 'ECHEC: E2E_JDBC_PASSWORD absente (aucun credential est commite, AGENTS.md section 13).' -ForegroundColor Red
    exit 3
}
if (-not (Test-Path -LiteralPath $script:JarPath)) {
    Write-Host "ECHEC: jar absent: $script:JarPath (mvn -o -f backend/pom.xml package -DskipTests)." -ForegroundColor Red
    exit 4
}
if (-not (Test-Path -LiteralPath $script:TemplateFile)) {
    Write-Host "ECHEC: copie de template hors depot absente: $script:TemplateFile" -ForegroundColor Red
    exit 4
}
New-Item -ItemType Directory -Force -Path $script:StorageRoot | Out-Null
New-Item -ItemType Directory -Force -Path $script:LogDirectory | Out-Null

try {
    [void](Ensure-TestPostgres -Password $script:DbPassword -TimeoutSeconds 60)
    Write-Host '==> PostgreSQL de test pret' -ForegroundColor Cyan
} catch {
    Write-Host "ECHEC: PostgreSQL de test: $($_.Exception.Message)" -ForegroundColor Red
    exit 11
}

$cases = [ordered]@{
    'L1' = 'start -> health -> stop -> port ferme'
    'L2' = 'arbre shim -> enfant java: tous arretes'
    'L3' = 'echec de test simule: cleanup complet (exit != 0)'
    'L4' = 'SERVER_PORT divergent: timeout borne + cleanup'
    'L5' = 'port occupe: fail fast, occupier non tue'
    'L6' = 'JVM sans rapport jamais touchee'
    'L7' = 'gate complet -ManageServer: 11 tests, exit 0'
}
foreach ($caseId in $cases.Keys) {
    Write-Host ''
    Write-Host ("=== {0} : {1} ===" -f $caseId, $cases[$caseId]) -ForegroundColor Cyan
    try {
        & "Invoke-$caseId"
    } catch {
        Add-Check $caseId 'aucune exception non geree dans le cas' $false `
            ("{0} @ {1}" -f $_.Exception.Message, ($_.InvocationInfo.PositionMessage -replace '\r?\n', ' '))
    }
}

# ---------------------------------------------------------- etat final
Write-Host ''
Write-Host '=== ETAT FINAL ===' -ForegroundColor Cyan
Add-Check 'FINAL' ("port {0} libre en fin de suite" -f $script:Port) (Test-PortFree -Port $script:Port)
Add-Check 'FINAL' ("port {0} libre en fin de suite" -f $script:AltPort) (Test-PortFree -Port $script:AltPort)
Add-Check 'FINAL' '0 JVM du projet en fin de suite' ((Test-ProjectJvmCount -JarPath $script:JarPath) -eq 0) `
    "count=$(Test-ProjectJvmCount -JarPath $script:JarPath)"

# ---------------------------------------------------------------- resume
$failCount = 0
$passCount = 0
$skipCount = 0
foreach ($item in $script:Checks) {
    if (-not $item.Ok) { $failCount++ }
    elseif ($item.Skip) { $skipCount++ }
    else { $passCount++ }
}
$statusLine = ($cases.Keys | ForEach-Object { '{0}={1}' -f $_, (Get-CaseStatus $_) }) -join ' '
$finalLine = ''
if ((Get-CaseStatus 'FINAL') -ne 'PASS') { $finalLine = ' FINAL=FAIL' }
Write-Host ''
Write-Host "LIFECYCLE_TESTS $statusLine$finalLine"
Write-Host "LIFECYCLE_TESTS_CHECKS=$($script:Checks.Count) PASS=$passCount FAIL=$failCount SKIP=$skipCount"
if ($failCount -eq 0) {
    Write-Host 'LIFECYCLE_TESTS_RESULT=PASS' -ForegroundColor Green
    exit 0
}
Write-Host 'LIFECYCLE_TESTS_RESULT=FAIL' -ForegroundColor Red
exit 1
