# Phase H-HTTP — gate E2E HTTP (preuve du vertical slice sur serveur réel).
#
# Ce script N'ordonne ni PostgreSQL ni Spring Boot : il vérifie que le serveur
# attendu répond déjà (échec rapide et explicite sinon), puis exécute le gate
# `HttpVerticalSliceIT`, qui parle en TCP réel au serveur et en JDBC réel à
# PostgreSQL.
#
# Usage :
#   powershell -ExecutionPolicy Bypass -File tests/e2e/run_http_e2e.ps1
#   ... -CleanupStorage   # supprime le stockage de test après vérification
#
# Anti-boucle : toutes les attentes sont bornées ; aucun processus n'est
# démarré, donc aucun processus n'a besoin d'être arrêté ici.

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18099',
    [string]$JdbcUrl = 'jdbc:postgresql://127.0.0.1:5460/adgendoc',
    [string]$DbUser = 'adgendoc',
    [string]$DbPassword = $env:E2E_JDBC_PASSWORD,
    [string]$StorageRoot = (Join-Path $env:TEMP 'opencode\phaseh\storage'),
    [string]$TemplateFile = (Join-Path $env:TEMP 'opencode\phaseh\templates\attestation_concordance_v1.docx'),
    [string]$ReferenceTemplate = '',
    [int]$HealthTimeoutSeconds = 60,
    [int]$MavenTimeoutSeconds = 900,
    [switch]$CleanupStorage
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($ReferenceTemplate)) {
    $ReferenceTemplate = Join-Path $repoRoot 'templates\attestation_concordance_v1.docx'
}
$failed = $false

function Write-Step([string]$message) { Write-Host "==> $message" -ForegroundColor Cyan }

# --- 1. Condition de succès / échec du serveur (borne : HealthTimeoutSeconds) ---
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
    Write-Host "ECHEC: serveur Spring Boot non sain sur $BaseUrl (aucun démarrage automatique)." -ForegroundColor Red
    exit 2
}
Write-Host "serveur UP: $BaseUrl"

# --- 2. Prérequis ---
if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    Write-Host "ECHEC: E2E_JDBC_PASSWORD absente (aucun credential n'est commité, AGENTS.md §13)." -ForegroundColor Red
    exit 3
}
foreach ($path in @($StorageRoot, $TemplateFile, $ReferenceTemplate)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "ECHEC: prerequis absent: $path" -ForegroundColor Red
        exit 4
    }
}

# Garde-fou : la copie servie par le serveur ne JAMAIS être le template versionné
# (le scénario "checksum invalide" altère la copie — jamais un fichier suivi par Git).
$templatePath   = (Resolve-Path -LiteralPath $TemplateFile).Path
$referencePath  = (Resolve-Path -LiteralPath $ReferenceTemplate).Path
if ($templatePath -eq $referencePath) {
    Write-Host "ECHEC: E2E_TEMPLATE_FILE et E2E_REFERENCE_TEMPLATE sont le meme fichier." -ForegroundColor Red
    exit 6
}
# Le scénario "checksum invalide" altère la copie : elle doit TOUJOURS être hors
# dépôt, sinon un fichier suivi par Git serait modifié pendant le test.
if ($templatePath.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Host "ECHEC: E2E_TEMPLATE_FILE est dans le depot: $templatePath" -ForegroundColor Red
    exit 7
}

# Auto-réparation : une exécution tuée en cours (Ctrl+C / timeout) peut laisser la
# copie altérée ; on la restaure depuis le template versionné avant de relancer.
$referenceSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $referencePath).Hash
$templateSha  = (Get-FileHash -Algorithm SHA256 -LiteralPath $templatePath).Hash
if ($templateSha -ne $referenceSha) {
    Write-Host "AVERTISSEMENT: copie de template alteree, restauration depuis le template versionne." -ForegroundColor Yellow
    Copy-Item -Force -LiteralPath $referencePath -Destination $templatePath
}

# --- 3. Gate E2E (bornes : sante HealthTimeoutSeconds + Maven MavenTimeoutSeconds) ---
Write-Step 'mvn -o -f backend/pom.xml test -Dtest=HttpVerticalSliceIT'
$env:E2E_BASE_URL = $BaseUrl
$env:E2E_JDBC_URL = $JdbcUrl
$env:E2E_JDBC_USER = $DbUser
$env:E2E_JDBC_PASSWORD = $DbPassword
$env:E2E_STORAGE_ROOT = $StorageRoot
$env:E2E_TEMPLATE_FILE = $templatePath
$env:E2E_REFERENCE_TEMPLATE = $referencePath

try {
    # NB : Start-Process -PassThru (PowerShell 5.1) ne renseigne JAMAIS ExitCode
    # (toujours $null), ce qui ferait échouer à tort le gate. On passe par
    # System.Diagnostics.Process, dont le code de sortie est fiable.
    $mvnInfo = New-Object System.Diagnostics.ProcessStartInfo
    $mvnInfo.FileName = (Join-Path $env:SystemRoot 'System32\cmd.exe')
    $mvnInfo.Arguments = '/c mvn -o -f backend\pom.xml test -Dtest=HttpVerticalSliceIT'
    $mvnInfo.WorkingDirectory = $repoRoot
    $mvnInfo.UseShellExecute = $false
    $mvnInfo.CreateNoWindow = $false
    $mvn = [System.Diagnostics.Process]::Start($mvnInfo)
    if (-not $mvn.WaitForExit($MavenTimeoutSeconds * 1000)) {
        try { $mvn.Kill($true) } catch { try { $mvn.Kill() } catch { } }
        $failed = $true
        Write-Host "ECHEC: Maven depasse $MavenTimeoutSeconds s (processus tue)." -ForegroundColor Red
    } elseif ($mvn.ExitCode -ne 0) {
        $failed = $true
        Write-Host "ECHEC: Maven code $($mvn.ExitCode)" -ForegroundColor Red
    }
} catch {
    $failed = $true
    Write-Host "ECHEC: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Remove-Item Env:E2E_JDBC_PASSWORD -ErrorAction SilentlyContinue
}

# --- 4. Nettoyage optionnel du stockage de test ---
if ($CleanupStorage) {
    Write-Step "Nettoyage stockage de test: $StorageRoot"
    $testZone    = (Join-Path $env:TEMP 'opencode\phaseh')
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

if ($failed) { Write-Host 'GATE HTTP E2E: ECHEC' -ForegroundColor Red; exit 1 }
Write-Host 'GATE HTTP E2E: SUCCES' -ForegroundColor Green
exit 0
