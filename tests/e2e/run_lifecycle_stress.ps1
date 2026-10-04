# Phase H.3 - L8 : stress du cycle de vie du serveur (3 cycles consecutifs).
#
# NB ENCODING : fichier ASCII pur obligatoire (voir lifecycle_tests.ps1) :
# PowerShell 5.1 lit les .ps1 sans BOM en ANSI et un tiret cote UTF-8 (U+2014)
# decoupe en guillemet CP1252 casse le parse.
#
# Chaque cycle execute le gate complet en mode gere :
#   START -> WAIT -> TEST (11 tests) -> STOP ARBRE -> PORT VERIFIE -> JVM VERIFIE
# et imprime un bloc de preuves machine-parseable :
#   RUN=n HTTP_TESTS=.. TEST_EXIT=.. SERVER_STOPPED=.. PORT_18099_FREE=..
#   PROJECT_JVM_LEFT=.. RESULT=PASS|FAIL
# puis verifie l'etat final des ports et des JVM.
#
# Sortie : 0 = les N cycles passent et l'etat final est propre ; 1 = au moins
# un cycle KO ; 3 = mot de passe de test absent ; 4 = prerequis.

[CmdletBinding()]
param(
    [int]$Runs = 3,
    [string]$StorageRoot = (Join-Path $env:TEMP 'opencode\phaseh\storage'),
    [string]$TemplateFile = (Join-Path $env:TEMP 'opencode\phaseh\templates\attestation_concordance_v1.docx'),
    [string]$LogDirectory = (Join-Path $env:TEMP 'opencode\phaseh\logs'),
    [string]$JarPath = '',
    [int]$Port = 18099,
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'ServerLifecycle.psm1') -Force -DisableNameChecking

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $repoRoot 'backend\target\document-generator-1.0.0-SNAPSHOT.jar'
}
$gate = Join-Path $PSScriptRoot 'run_http_e2e.ps1'

if ([string]::IsNullOrWhiteSpace($env:E2E_JDBC_PASSWORD)) {
    Write-Host 'ECHEC: E2E_JDBC_PASSWORD absente (aucun credential est commite, AGENTS.md section 13).' -ForegroundColor Red
    exit 3
}
if (-not (Test-Path -LiteralPath $gate)) { Write-Host "ECHEC: gate absent: $gate" -ForegroundColor Red; exit 4 }
if (-not (Test-Path -LiteralPath $JarPath)) { Write-Host "ECHEC: jar absent: $JarPath" -ForegroundColor Red; exit 4 }
if (-not (Test-Path -LiteralPath $TemplateFile)) { Write-Host "ECHEC: template absent: $TemplateFile" -ForegroundColor Red; exit 4 }
if (-not (Test-Path -LiteralPath $StorageRoot)) { New-Item -ItemType Directory -Force -Path $StorageRoot | Out-Null }
if (-not (Test-Path -LiteralPath $LogDirectory)) { New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null }

Write-Host "Lifecycle stress - $Runs cycles, port $Port, gate $gate" -ForegroundColor Cyan

$gateArgs = @('-ManageServer', '-ManagePostgres', '-StorageRoot', $StorageRoot, '-TemplateFile', $TemplateFile)
$allPass = $true
for ($run = 1; $run -le $Runs; $run++) {
    $outFile = Join-Path $LogDirectory ("lifecycle-stress-run{0}.log" -f $run)
    $started = Get-Date
    $runResult = Invoke-PowerShellBounded -ScriptPath $gate -ScriptArgs $gateArgs `
        -TimeoutSeconds $TimeoutSeconds -OutFile $outFile
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    $ev = Get-EvidenceBlock -LogPath $outFile
    $testExit = $ev['TEST_EXIT_CODE']
    $serverStopped = $ev['SERVER_STOPPED']
    $portFree = $ev["PORT_${Port}_FREE"]
    $jvmLeft = $ev['PROJECT_JVM_LEFT']
    $httpTests = $ev['HTTP_TESTS']
    $exitCode = $runResult.ExitCode

    $ok = (-not $runResult.TimedOut) -and ($null -ne $exitCode) -and ([int]$exitCode -eq 0) -and
    ($testExit -eq '0') -and ($serverStopped -eq 'True') -and ($portFree -eq 'True') -and
    ($jvmLeft -eq '0') -and ($httpTests -eq '11') -and
    (Test-PortFree -Port $Port) -and ((Test-ProjectJvmCount -JarPath $JarPath) -eq 0)
    if (-not $ok) { $allPass = $false }

    $verdict = 'FAIL'
    if ($ok) { $verdict = 'PASS' }
    Write-Host ("RUN={0} HTTP_TESTS={1} TEST_EXIT={2} SERVER_STOPPED={3} PORT_{4}_FREE={5} PROJECT_JVM_LEFT={6} GATE_EXIT={7} ELAPSED={8}s RESULT={9}" -f `
            $run, $httpTests, $testExit, $serverStopped, $Port, $portFree, $jvmLeft, $exitCode, $elapsed, $verdict)
    if ($runResult.TimedOut) { Write-Host ("RUN={0} DETAIL=timeout {1}s (arbre du gate tue)" -f $run, $TimeoutSeconds) -ForegroundColor Red }
    if (-not $ok -and (Test-Path -LiteralPath $outFile)) {
        Write-Host ("RUN={0} LOG={1}" -f $run, $outFile) -ForegroundColor Yellow
        Get-Content -LiteralPath $outFile | Select-Object -Last 25 | ForEach-Object { Write-Host "  | $_" }
    }
}

$finalPortFree = Test-PortFree -Port $Port
$finalJvms = Test-ProjectJvmCount -JarPath $JarPath
Write-Host "STRESS_FINAL_PORT_${Port}_FREE=$finalPortFree"
Write-Host "STRESS_FINAL_PROJECT_JVM_LEFT=$finalJvms"

if ($allPass -and $finalPortFree -and $finalJvms -eq 0) {
    Write-Host 'LIFECYCLE_STRESS_RESULT=PASS' -ForegroundColor Green
    exit 0
}
Write-Host 'LIFECYCLE_STRESS_RESULT=FAIL' -ForegroundColor Red
exit 1
