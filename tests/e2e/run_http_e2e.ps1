# Phase H-HTTP — gate E2E HTTP (preuve du vertical slice sur serveur réel).
#
# Deux modes :
#   - défaut (externe) : le serveur appartient à l'appelant ; le script
#     vérifie qu'il répond déjà (échec rapide et explicite sinon), exécute le
#     gate `HttpVerticalSliceIT` et NE touche à aucun processus (code 2/3/4/6/7).
#   - `-ManageServer` (Phase H.3) : LE SCRIPT EST PROPRIETAIRE du serveur :
#     START → attente bornée → TEST → collecte de preuves → STOP ARBRE →
#     VÉRIFICATION PORT FERMÉ → VÉRIFICATION AUCUNE JVM PROJET → code de sortie.
#     Les codes 8/9/10/11 documentent échec de démarrage/timeout/cleanup/PostgreSQL.
#
# Sortie finale : TEST_EXIT_CODE et CLEANUP_EXIT_CODE restent séparés ;
#   test OK  + cleanup OK  => 0
#   test KO  + cleanup OK  => != 0
#   test OK  + cleanup KO   => 10  (jamais transformé en succès)
#
# Usage :
#   powershell -ExecutionPolicy Bypass -File tests/e2e/run_http_e2e.ps1
#   powershell -ExecutionPolicy Bypass -File tests/e2e/run_http_e2e.ps1 `
#       -ManageServer -ManagePostgres -StorageRoot <s> -TemplateFile <t>
#
# Anti-boucle : toutes les attentes sont bornées (santé -HealthTimeoutSeconds,
# Maven -MavenTimeoutSeconds, libération de port 15 s dans le module).

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18099',
    [string]$JdbcUrl = 'jdbc:postgresql://127.0.0.1:5460/adgendoc',
    [string]$DbUser = 'adgendoc',
    [string]$DbPassword = $env:E2E_JDBC_PASSWORD,
    [string]$StorageRoot = (Join-Path $env:TEMP 'opencode\phaseh\storage'),
    [string]$TemplateFile = (Join-Path $env:TEMP 'opencode\phaseh\templates\attestation_concordance_v1.docx'),
    [string]$ReferenceTemplate = '',
    [string]$JarPath = '',
    [int]$HealthTimeoutSeconds = 60,
    [int]$MavenTimeoutSeconds = 900,
    [int]$StartupTimeoutSeconds = 60,
    [string]$LogDirectory = (Join-Path $env:TEMP 'opencode\phaseh\logs'),
    [hashtable]$ServerEnvironment = @{},
    [switch]$ManageServer,
    [switch]$ManagePostgres,
    [switch]$TeardownPostgres,
    [switch]$AdoptOrphan,
    [switch]$SimulateTestFailure,
    [switch]$CleanupStorage
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'ServerLifecycle.psm1') -Force -DisableNameChecking

if ([string]::IsNullOrWhiteSpace($ReferenceTemplate)) {
    $ReferenceTemplate = Join-Path $repoRoot 'templates\attestation_concordance_v1.docx'
}
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $repoRoot 'backend\target\document-generator-1.0.0-SNAPSHOT.jar'
}
$port = 18099
try { $port = ([uri]$BaseUrl).Port } catch { $port = 18099 }

$failed = $false
$failureCode = 0
$testExit = -1
$cleanupExit = 0
$session = $null
$serverStarted = $false
$cleanupResult = $null
$httpTests = $null

function Write-Step([string]$message) { Write-Host "==> $message" -ForegroundColor Cyan }

function Get-ExitCodeForLifecycle {
    param([string]$LifecycleCode)
    switch ($LifecycleCode) {
        'PORT_OCCUPIED' { return 8 }
        'PORT_FOREIGN' { return 8 }
        'JAR_MISSING' { return 4 }
        'STARTUP_TIMEOUT' { return 9 }
        'STARTUP_DIED' { return 9 }
        'LAUNCH_FAILED' { return 9 }
        'JAVA_NOT_FOUND' { return 9 }
        'PG_PASSWORD_MISSING' { return 11 }
        'PG_PASSWORD_INVALID' { return 11 }
        'DOCKER_UNAVAILABLE' { return 11 }
        'PG_START_FAILED' { return 11 }
        'PG_NOT_READY' { return 11 }
        'CONTAINER_IMAGE_MISMATCH' { return 11 }
        'NATIVE_LAUNCH_FAILED' { return 11 }
        default { return 1 }
    }
}

try {
    # ------------------------------------------------------------------ 1. états
    if ($ManageServer) {
        # Mode géré : les prérequis sont vérifiés AVANT tout lancement de JVM
        # (aucun processus créé pour un prérequis manquant).
        if ([string]::IsNullOrWhiteSpace($DbPassword)) {
            Write-Host "ECHEC: E2E_JDBC_PASSWORD absente (aucun credential n'est commite, AGENTS.md section 13)." -ForegroundColor Red
            $failureCode = 3
        }
        foreach ($path in @($StorageRoot, $TemplateFile, $ReferenceTemplate)) {
            if ($failureCode -eq 0 -and -not (Test-Path -LiteralPath $path)) {
                Write-Host "ECHEC: prerequis absent: $path" -ForegroundColor Red
                $failureCode = 4
            }
        }
        if ($failureCode -eq 0 -and -not (Test-Path -LiteralPath $JarPath)) {
            Write-Host "ECHEC: prerequis absent: $JarPath" -ForegroundColor Red
            $failureCode = 4
        }
        if ($failureCode -eq 0) {
            $templatePath = (Resolve-Path -LiteralPath $TemplateFile).Path
            $referencePath = (Resolve-Path -LiteralPath $ReferenceTemplate).Path
            if ($templatePath -eq $referencePath) {
                Write-Host "ECHEC: E2E_TEMPLATE_FILE et E2E_REFERENCE_TEMPLATE sont le meme fichier." -ForegroundColor Red
                $failureCode = 6
            } elseif ($templatePath.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
                Write-Host "ECHEC: E2E_TEMPLATE_FILE est dans le depot: $templatePath" -ForegroundColor Red
                $failureCode = 7
            }
        }
        if ($failureCode -eq 0) {
            # Auto-réparation : une exécution tuée en cours peut laisser la copie
            # altérée ; on la restaure depuis le template versionné.
            $referenceSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $referencePath).Hash
            $templateSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $templatePath).Hash
            if ($templateSha -ne $referenceSha) {
                Write-Host "AVERTISSEMENT: copie de template alteree, restauration depuis le template versionne." -ForegroundColor Yellow
                Copy-Item -Force -LiteralPath $referencePath -Destination $templatePath
            }
        }
        if ($failureCode -eq 0 -and $ManagePostgres) {
            try {
                Write-Step 'PostgreSQL de test (docker exec pg_isready borne)'
                [void](Ensure-TestPostgres -Password $DbPassword -TimeoutSeconds 60)
            } catch {
                $failureCode = Get-ExitCodeForLifecycle -LifecycleCode (Get-LifecycleCode -ErrorRecord $_)
                Write-Host "ECHEC: $($_.Exception.Message)" -ForegroundColor Red
            }
        }
        if ($failureCode -eq 0) {
            # NB : nom local distinct du paramètre $ServerEnvironment — PowerShell
            # est insensible à la casse, une réaffectation écraserait le paramètre.
            $srvEnv = @{
                SERVER_PORT              = [string]$port
                SPRING_DATASOURCE_URL    = $JdbcUrl
                SPRING_DATASOURCE_USERNAME = $DbUser
                SPRING_DATASOURCE_PASSWORD = $DbPassword
                MIGRATIONS_DIR           = (Join-Path $repoRoot 'database\migrations')
                TEMPLATE_DIR             = (Split-Path -Parent $templatePath)
                DOCUMENT_STORAGE_PATH    = $StorageRoot
            }
            foreach ($key in @($ServerEnvironment.Keys)) { $srvEnv[$key] = $ServerEnvironment[$key] }
            Write-Step "Start-TestServer port=$port (timeout ${StartupTimeoutSeconds}s, logs hors depot)"
            try {
                Start-TestServer -JarPath $JarPath -Port $port -Environment $srvEnv `
                    -StartupTimeoutSeconds $StartupTimeoutSeconds -LogDirectory $LogDirectory `
                    -SessionRef ([ref]$session) -AdoptOrphan:$AdoptOrphan | Out-Null
                $serverStarted = ($null -ne $session)
            } catch {
                $serverStarted = ($null -ne $session)
                $failureCode = Get-ExitCodeForLifecycle -LifecycleCode (Get-LifecycleCode -ErrorRecord $_)
                Write-Host "ECHEC: $($_.Exception.Message)" -ForegroundColor Red
            }
            if ($failureCode -eq 0) { Write-Host "serveur UP: $BaseUrl" }
        }
    } else {
        # Mode externe (hérité) : ordre et codes 2/3/4/6/7 inchangés.
        Write-Step "Health check $BaseUrl (timeout ${HealthTimeoutSeconds}s)"
        $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
        $healthy = $false
        while ((Get-Date) -lt $deadline) {
            try {
                $response = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 3
                $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
                if ($response.StatusCode -eq 200 -and $text -match '"status"\s*:\s*"UP"') { $healthy = $true; break }
            } catch { Start-Sleep -Seconds 2 }
        }
        if (-not $healthy) {
            Write-Host "ECHEC: serveur Spring Boot non sain sur $BaseUrl (aucun demarrage automatique)." -ForegroundColor Red
            $failureCode = 2
        } else {
            Write-Host "serveur UP: $BaseUrl"
        }
        if ($failureCode -eq 0 -and [string]::IsNullOrWhiteSpace($DbPassword)) {
            Write-Host "ECHEC: E2E_JDBC_PASSWORD absente (aucun credential n'est commite, AGENTS.md section 13)." -ForegroundColor Red
            $failureCode = 3
        }
        foreach ($path in @($StorageRoot, $TemplateFile, $ReferenceTemplate)) {
            if ($failureCode -eq 0 -and -not (Test-Path -LiteralPath $path)) {
                Write-Host "ECHEC: prerequis absent: $path" -ForegroundColor Red
                $failureCode = 4
            }
        }
        if ($failureCode -eq 0) {
            $templatePath = (Resolve-Path -LiteralPath $TemplateFile).Path
            $referencePath = (Resolve-Path -LiteralPath $ReferenceTemplate).Path
            if ($templatePath -eq $referencePath) {
                Write-Host "ECHEC: E2E_TEMPLATE_FILE et E2E_REFERENCE_TEMPLATE sont le meme fichier." -ForegroundColor Red
                $failureCode = 6
            } elseif ($templatePath.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
                Write-Host "ECHEC: E2E_TEMPLATE_FILE est dans le depot: $templatePath" -ForegroundColor Red
                $failureCode = 7
            }
        }
        if ($failureCode -eq 0) {
            $referenceSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $referencePath).Hash
            $templateSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $templatePath).Hash
            if ($templateSha -ne $referenceSha) {
                Write-Host "AVERTISSEMENT: copie de template alteree, restauration depuis le template versionne." -ForegroundColor Yellow
                Copy-Item -Force -LiteralPath $referencePath -Destination $templatePath
            }
        }
        if ($failureCode -eq 0 -and $ManagePostgres) {
            try {
                Write-Step 'PostgreSQL de test (docker exec pg_isready borne)'
                [void](Ensure-TestPostgres -Password $DbPassword -TimeoutSeconds 60)
            } catch {
                $failureCode = Get-ExitCodeForLifecycle -LifecycleCode (Get-LifecycleCode -ErrorRecord $_)
                Write-Host "ECHEC: $($_.Exception.Message)" -ForegroundColor Red
            }
        }
    }

    # ------------------------------------------------------------------ 2. gate
    if ($failureCode -eq 0) {
        if ($SimulateTestFailure) {
            $failed = $true
            $testExit = 1
            Write-Host 'ECHEC: simulation dechec de test (-SimulateTestFailure) : Maven non execute.' -ForegroundColor Red
        } else {
            Write-Step 'mvn -o -f backend/pom.xml test -Dtest=HttpVerticalSliceIT'
            $env:E2E_BASE_URL = $BaseUrl
            $env:E2E_JDBC_URL = $JdbcUrl
            $env:E2E_JDBC_USER = $DbUser
            $env:E2E_JDBC_PASSWORD = $DbPassword
            $env:E2E_STORAGE_ROOT = $StorageRoot
            $env:E2E_TEMPLATE_FILE = $templatePath
            $env:E2E_REFERENCE_TEMPLATE = $referencePath
            if (-not (Test-Path -LiteralPath $LogDirectory)) { New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null }
            $mavenLog = Join-Path $LogDirectory ("maven-{0}-{1}.log" -f $port, ([guid]::NewGuid().ToString('N').Substring(0, 6)))

            # NB : Start-Process -PassThru (PowerShell 5.1) ne renseigne JAMAIS
            # ExitCode. On passe par System.Diagnostics.Process, code fiable.
            # La sortie Maven est redirigée vers un fichier (pas de pipe vers
            # l'orchestrateur : pas d'attente de EOF infinie).
            try {
                $mvnInfo = New-Object System.Diagnostics.ProcessStartInfo
                $mvnInfo.FileName = (Join-Path $env:SystemRoot 'System32\cmd.exe')
                $mvnInfo.Arguments = '/c mvn -o -f backend\pom.xml test -Dtest=HttpVerticalSliceIT > "' + $mavenLog + '" 2>&1'
                $mvnInfo.WorkingDirectory = $repoRoot
                $mvnInfo.UseShellExecute = $false
                $mvnInfo.CreateNoWindow = $false
                $mvn = [System.Diagnostics.Process]::Start($mvnInfo)
                if ($null -eq $mvn) { throw 'Maven launch failed' }
                if (-not $mvn.WaitForExit($MavenTimeoutSeconds * 1000)) {
                    # H.3 M5 : tuer l'ARBRE cmd → mvn → JVM (PS 5.1 n'a pas Process.Kill(bool))
                    $tree = Stop-ProcessTree -RootPid $mvn.Id
                    $failed = $true
                    Write-Host "ECHEC: Maven depasse $MavenTimeoutSeconds s (arbre tue, $($tree.Killed) processus)." -ForegroundColor Red
                } elseif ($mvn.ExitCode -ne 0) {
                    $failed = $true
                    Write-Host "ECHEC: Maven code $($mvn.ExitCode)" -ForegroundColor Red
                }
            } catch {
                $failed = $true
                Write-Host "ECHEC: $($_.Exception.Message)" -ForegroundColor Red
            }

            if (Test-Path -LiteralPath $mavenLog) {
                $mavenLines = @(Get-Content -LiteralPath $mavenLog)
                $summary = @($mavenLines | Where-Object { $_ -match 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+\s*$' })
                if ($summary.Count -gt 0) {
                    $lastSummary = $summary[$summary.Count - 1]
                    Write-Host $lastSummary
                    if ($lastSummary -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)') {
                        $httpTests = [int]$Matches[1]
                        if ([int]$Matches[2] -ne 0 -or [int]$Matches[3] -ne 0) { $failed = $true }
                    }
                }
                if ($failed) {
                    $mavenLines | Select-Object -Last 40 | ForEach-Object { Write-Host $_ }
                } else {
                    $mavenLines | Where-Object { $_ -match 'BUILD SUCCESS|BUILD FAILURE' } | Select-Object -Last 1 | ForEach-Object { Write-Host $_ }
                }
            } elseif (-not $failed) {
                $failed = $true
                Write-Host "ECHEC: log Maven absent: $mavenLog" -ForegroundColor Red
            }
            if ($failed) { $testExit = 1 } else { $testExit = 0 }
        }
    }
} catch {
    if ($failureCode -eq 0) {
        $failureCode = Get-ExitCodeForLifecycle -LifecycleCode (Get-LifecycleCode -ErrorRecord $_)
    }
    if ($testExit -lt 0) { $testExit = -1 }
    Write-Host "ECHEC: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ScriptStackTrace) {
        Write-Host "TRACE: $($_.ScriptStackTrace -replace '\r?\n', ' <- ')" -ForegroundColor DarkGray
    }
} finally {
    # ---------------- 3. STOP ARBRE + VÉRIFICATIONS (toujours exécuté) -------
    if ($null -ne $session) {
        try {
            $cleanupResult = Stop-TestServer -Session $session -PortReleaseSeconds 15
            if (Test-TestServerSessionClean -Result $cleanupResult) { $cleanupExit = 0 }
            else { $cleanupExit = 1 }
        } catch {
            $cleanupExit = 1
            # Inconnu = N/A : jamais de valeur optimiste presentee comme un fait
            # dans les preuves (les sorties N/A restent parsees par le module).
            $cleanupResult = [pscustomobject]@{
                Stopped = $false; PortFree = $false
                PortOwnerIsForeign = $null; LineageJvmLeft = $null
                Detail = $_.Exception.Message
            }
        }
    } elseif ($serverStarted) {
        # Jamais de faux succès : démarrage réussi mais session perdue = cleanup
        # impossible => échec explicite.
        $cleanupExit = 1
        Write-Host 'ECHEC: session serveur introuvable apres demarrage (cleanup impossible).' -ForegroundColor Red
    } else {
        $cleanupExit = 0
    }
    if ($ManagePostgres -and $TeardownPostgres) {
        try { [void](Stop-TestPostgres -TimeoutSeconds 30) } catch { Write-Host "AVERTISSEMENT: arret PostgreSQL: $($_.Exception.Message)" -ForegroundColor Yellow }
    }
    Remove-Item Env:E2E_JDBC_PASSWORD -ErrorAction SilentlyContinue
}

# ---------------------------------------------------- 4. nettoyage du stockage
if ($CleanupStorage) {
    Write-Step "Nettoyage stockage de test: $StorageRoot"
    $testZone = (Join-Path $env:TEMP 'opencode\phaseh')
    $resolvedOut = Resolve-Path -LiteralPath $StorageRoot -ErrorAction SilentlyContinue
    if ($resolvedOut -and
        $resolvedOut.Path.StartsWith($testZone, [StringComparison]::OrdinalIgnoreCase) -and
        $resolvedOut.Path -like "*\storage") {
        Remove-Item -Recurse -Force -LiteralPath $resolvedOut.Path -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $resolvedOut.Path | Out-Null
        Write-Host "stockage de test vide"
    } else {
        Write-Host "REFUS de supprimer un chemin hors zone de test: $StorageRoot" -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------ 5. preuves + exit
$jarFull = [System.IO.Path]::GetFullPath($JarPath)
Write-Host ''
Write-Host "TEST_EXIT_CODE=$testExit"
Write-Host "CLEANUP_EXIT_CODE=$cleanupExit"
if ($null -ne $session) {
    Write-Host "SERVER_STOPPED=$([bool]$cleanupResult.Stopped)"
    Write-Host "PORT_${port}_FREE=$([bool]$cleanupResult.PortFree)"
    if ($null -eq $cleanupResult.PortOwnerIsForeign) { Write-Host 'PORT_OWNER_FOREIGN=N/A' }
    else { Write-Host "PORT_OWNER_FOREIGN=$([bool]$cleanupResult.PortOwnerIsForeign)" }
    if ($null -eq $cleanupResult.LineageJvmLeft) { Write-Host 'PROJECT_JVM_LEFT=N/A' }
    else { Write-Host "PROJECT_JVM_LEFT=$([int]$cleanupResult.LineageJvmLeft)" }
} else {
    Write-Host 'SERVER_STOPPED=N/A'
    Write-Host "PORT_${port}_FREE=$(Test-PortFree -Port $port)"
    Write-Host 'PORT_OWNER_FOREIGN=N/A'
    Write-Host "PROJECT_JVM_LEFT=$(Test-ProjectJvmCount -JarPath $jarFull)"
}
if ($null -eq $httpTests) { Write-Host 'HTTP_TESTS=NA' } else { Write-Host "HTTP_TESTS=$httpTests" }

if ($failureCode -ne 0) { $final = $failureCode }
elseif ($testExit -gt 0) { $final = 1 }
elseif ($testExit -lt 0) { $final = 1 }
elseif ($cleanupExit -ne 0) { $final = 10 }
else { $final = 0 }

if ($final -eq 0) {
    Write-Host 'GATE HTTP E2E: SUCCES' -ForegroundColor Green
} else {
    if ($failureCode -eq 0 -and $testExit -eq 0 -and $cleanupExit -ne 0) {
        Write-Host "ECHEC: tests OK mais cleanup KO (port ou JVM residuel) -> code 10." -ForegroundColor Red
    }
    Write-Host 'GATE HTTP E2E: ECHEC' -ForegroundColor Red
}
exit $final
