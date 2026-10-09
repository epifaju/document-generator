$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

function FindBlockEnd($bytes, $startIdx) {
    $level = 0
    $inString = $false
    $escape = $false
    for ($i = $startIdx; $i -lt $bytes.Length; $i++) {
        $b = $bytes[$i]
        if ($escape) { $escape = $false; continue }
        if ($b -eq 92) { $escape = $true; continue }
        if ($b -eq 39 -and -not $escape) { $inString = -not $inString; continue }
        if ($inString) { continue }
        if ($b -eq 123) { $level++ }
        if ($b -eq 125) { $level--; if ($level -eq 0) { return $i } }
    }
    return -1
}

function ReplaceBlock($bytes, $searchText, $replacementText) {
    $searchBytes = [System.Text.Encoding]::UTF8.GetBytes($searchText)
    $idx = -1
    for ($i = 0; $i -le $bytes.Length - $searchBytes.Length; $i++) {
        $match = $true
        for ($j = 0; $j -lt $searchBytes.Length; $j++) {
            if ($bytes[$i + $j] -ne $searchBytes[$j]) { return $bytes }
        }
        $idx = $i
        break
    }
    if ($idx -lt 0) { return $bytes }

    $endIdx = FindBlockEnd $bytes $idx
    if ($idx -lt 0 -or $endIdx -lt 0) { return $bytes }

    $newBytes = [System.Text.Encoding]::UTF8.GetBytes($replacementText)
    $result = New-Object byte[] ($bytes.Length - ($endIdx - $idx + 1) + $newBytes.Length)
    [Array]::Copy($bytes, 0, $result, 0, $idx)
    [Array]::Copy([System.Text.Encoding]::UTF8.GetBytes($replacementText), 0, $result, $idx, $newBytes.Length)
    [Array]::Copy($bytes, $endIdx + 1, $result, $idx + $newBytes.Length, $bytes.Length - $endIdx - 1)
    return $result
}

$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

$bytes = ReplaceBlock $bytes "SAFE_MESSAGES" @"
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

$bytes = ReplaceBlock $bytes "ERROR_MESSAGES" @"
const ERROR_MESSAGES = {
  VALIDATION_ERROR: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',
  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',
  AI_EXTRACTION_ERROR: 'L\'extraction automatique a échoué. Veuillez reformuler votre demande.',
  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incomplètes. Veuillez reformuler votre demande.',
  BACKEND_UNAVAILABLE: 'Le service de génération est momentanément indisponible. Veuillez réessayer plus tard.',
  INTERNAL_ERROR: 'Le traitement de la demande a échoué. Veuillez réessayer plus tard.'
};
"@

$bytes = ReplaceBlock $bytes "SAFE_INGRESS_MESSAGES" @"
const SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.'
};
"@

[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Done"