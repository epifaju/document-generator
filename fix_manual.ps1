$wf = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json' | ConvertFrom-Json

# Fix FinalizeResponse - directly set the jsCode with clean strings
$node = $wf.nodes | Where-Object { $_.name -eq 'FinalizeResponse' }

# Build the complete clean jsCode for FinalizeResponse
# We'll replace just the SAFE_MESSAGES and ERROR_MESSAGES blocks using precise string replacement
$code = $node.parameters.jsCode

# The SAFE_MESSAGES block - find and replace precisely
$oldSafe = "const SAFE_MESSAGES = {"
$idx = $code.IndexOf($oldSafe)
if ($idx -ge 0) {
    # Find the end of this block
    $level = 0
    $inString = $false
    $escape = $false
    $endIdx = -1
    for ($i = $idx; $i -lt $code.Length; $i++) {
        $ch = $code[$i]
        if ($escape) { $escape = $false; continue }
        if ($ch -eq '\') { $escape = $true; continue }
        if ($ch -eq "'" -and -not $escape) { $inString = -not $inString; continue }
        if ($inString) { continue }
        if ($ch -eq '{') { $level++ }
        if ($ch -eq '}') { $level--; if ($level -eq 0) { $endIdx = $i; break } }
    }
    if ($endIdx -ge 0) {
        $newSafe = @"
const SAFE_MESSAGES = {
  MISSING_INFORMATION: 'Des informations supplémentaires sont nécessaires pour poursuivre la demande.',
  CLARIFICATION_LIMIT_REACHED: 'Nombre maximal de demandes de clarification atteint. Veuillez relancer une nouvelle demande.',
  REJECTED: 'La demande a été rejetée : correction impossible sur cette demande (statut terminal en itération 1).',
  GENERATION_FAILED: 'La génération du document a échoué. Vous pouvez relancer la demande.',
  UNSUPPORTED_DOCUMENT_TYPE: 'Seul le type de document ATTESTATION_CONCORDANCE est pris en charge en itération 1.',
  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',
  INVALID_REQUEST: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',
  AI_EXTRACTION_ERROR: 'L\'extraction automatique a échoué. Veuillez reformuler votre demande.',
  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incomplètes. Veuillez reformuler votre demande.',
  BACKEND_UNAVAILABLE: 'Le service de génération est momentanément indisponible. Veuillez réessayer plus tard.',
  INTERNAL_ERROR: 'Le traitement de la demande a échoué. Veuillez réessayer plus tard.'
};
"@
        $code = $code.Remove($idx, $endIdx - $idx + 1).Insert($idx, $newSafe)
        Write-Output "SAFE_MESSAGES replaced"
    }
}

# Fix ERROR_MESSAGES
$oldError = "const ERROR_MESSAGES = {"
$idx = $code.IndexOf("const ERROR_MESSAGES = {")
if ($idx -ge 0) {
    $level = 0
    $inString = $false
    $escape = $false
    $endIdx = -1
    for ($i = $idx; $i -lt $code.Length; $i++) {
        $ch = $code[$i]
        if ($escape) { $escape = $false; continue }
        if ($ch -eq '\') { $escape = $true; continue }
        if ($ch -eq "'" -and -not $escape) { $inString = -not $inString; continue }
        if ($inString) { continue }
        if ($ch -eq '{') { $level++ }
        if ($ch -eq '}') { $level--; if ($level -eq 0) { $endIdx = $i; break } }
    }
    if ($endIdx -ge 0) {
        $newError = @"
const ERROR_MESSAGES = {
  VALIDATION_ERROR: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',
  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',
  AI_EXTRACTION_ERROR: 'L\'extraction automatique a échoué. Veuillez reformuler votre demande.',
  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incomplètes. Veuillez reformuler votre demande.',
  BACKEND_UNAVAILABLE: 'Le service de génération est momentanément indisponible. Veuillez réessayer plus tard.',
  INTERNAL_ERROR: 'Le traitement de la demande a échoué. Veuillez réessayer plus tard.'
};
"@
        $code = $code.Remove($idx, $endIdx - $idx + 1).Insert($idx, $newError)
        Write-Output "ERROR_MESSAGES replaced"
    }
}

$node.parameters.jsCode = $code

# Fix InitOrchestration SAFE_INGRESS_MESSAGES
$init = $wf.nodes | Where-Object { $_.name -eq 'InitOrchestration' }
$icode = $init.parameters.jsCode
$oldIngress = "const SAFE_INGRESS_MESSAGES = {"
$idx = $icode.IndexOf($oldIngress)
if ($idx -ge 0) {
    $level = 0
    $inString = $false
    $escape = $false
    $endIdx = -1
    for ($i = $idx; $i -lt $icode.Length; $i++) {
        $ch = $icode[$i]
        if ($escape) { $escape = $false; continue }
        if ($ch -eq '\') { $escape = $true; continue }
        if ($ch -eq "'" -and -not $escape) { $inString = -not $inString; continue }
        if ($inString) { continue }
        if ($ch -eq '{') { $level++ }
        if ($ch -eq '}') { $level--; if ($level -eq 0) { $endIdx = $i; break } }
    }
    if ($endIdx -ge 0) {
        $newIngress = @"
const SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.'
};
"@
        $icode = $icode.Remove($idx, $endIdx - $idx + 1).Insert($idx, $newIngress)
        Write-Output "SAFE_INGRESS_MESSAGES replaced"
    }
}
$wf.nodes | Where-Object { $_.name -eq 'InitOrchestration' } | ForEach-Object { $_.parameters.jsCode = $icode }

# Fix Webhook notes
$webhook = $wf.nodes | Where-Object { $_.name -eq 'Webhook' }
$webhook.parameters.notes = $webhook.parameters.notes -replace "pos\u00C3\u0192\u00C2\u00A9", "posé"

# Write back without BOM
$json = $wf | ConvertTo-Json -Depth 20
$bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Manual replacement done"