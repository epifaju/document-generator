$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

# Find SAFE_MESSAGES start
$searchBytes = [System.Text.Encoding]::UTF8.GetBytes("SAFE_MESSAGES")
$idx = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes[$j]) { $match = $false; break }
    }
    if ($match) { $idx = $i; break }
}

if ($idx -ge 0) {
    # Find the end of SAFE_MESSAGES block (matching closing };)
    $level = 0
    $inString = $false
    $escape = $false
    $endIdx = -1
    for ($i = $idx; $i -lt $bytes.Length; $i++) {
        $b = $bytes[$i]
        if ($escape) { $escape = $false; continue }
        if ($b -eq 92) { $escape = $true; continue }  # backslash
        if ($b -eq 39 && !$escape) { $inString = !$inString; continue }  # single quote
        if ($inString) { continue }
        if ($b -eq 123) { $level++ }  # {
        if ($b -eq 125) { $level--; if ($level -eq 0) { $endIdx = $i; break } }  # }
    }
    if ($endIdx -ge 0) {
        Write-Output ("SAFE_MESSAGES block: $idx to $endIdx (length " + ($endIdx - $idx + 1) + ")")
        
        # Build replacement bytes for clean SAFE_MESSAGES
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
        $newSafeBytes = [System.Text.Encoding]::UTF8.GetBytes($newSafe)
        
        # Replace in byte array
        $newBytes = New-Object byte[] ($bytes.Length - ($endIdx - $idx + 1) + $newSafeBytes.Length)
        [Array]::Copy($bytes, 0, $newBytes, 0, $idx)
        [Array]::Copy($newSafeBytes, 0, $newBytes, $idx, $newSafeBytes.Length)
        [Array]::Copy($bytes, $endIdx + 1, $newBytes, $idx + $newSafeBytes.Length, $bytes.Length - $endIdx - 1)
        $bytes = $newBytes
        Write-Output "SAFE_MESSAGES replaced"
    }
}

# Now find and replace ERROR_MESSAGES
$searchBytes = [System.Text.Encoding]::UTF8.GetBytes("ERROR_MESSAGES")
$idx = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes[$j]) { $match = $false; break }
    }
    if ($match) { $idx = $i; break }
}

if ($idx -ge 0) {
    $level = 0
    $inString = $false
    $escape = $false
    $endIdx = -1
    for ($i = $idx; $i -lt $bytes.Length; $i++) {
        $b = $bytes[$i]
        if ($escape) { $escape = $false; continue }
        if ($b -eq 92) { $escape = $true; continue }
        if ($b -eq 39 && !$escape) { $inString = !$inString; continue }
        if ($inString) { continue }
        if ($b -eq 123) { $level++ }
        if ($b -eq 125) { $level--; if ($level -eq 0) { $endIdx = $i; break } }
    }
    if ($endIdx -ge 0) {
        Write-Output ("ERROR_MESSAGES block: $idx to $endIdx")
        
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
        $newErrorBytes = [System.Text.Encoding]::UTF8.GetBytes($newError)
        $newBytes = New-Object byte[] ($bytes.Length - ($endIdx - $idx + 1) + $newErrorBytes.Length)
        [Array]::Copy($bytes, 0, $newBytes, 0, $idx)
        [Array]::Copy($newErrorBytes, 0, $newBytes, $idx, $newErrorBytes.Length)
        [Array]::Copy($bytes, $endIdx + 1, $newBytes, $idx + $newErrorBytes.Length, $bytes.Length - $endIdx - 1)
        $bytes = $newBytes
        Write-Output "ERROR_MESSAGES replaced"
    }
}

# Also fix SAFE_INGRESS_MESSAGES in InitOrchestration
$searchBytes = [System.Text.Encoding]::UTF8.GetBytes("SAFE_INGRESS_MESSAGES")
$idx = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes[$j]) { $match = $false; break }
    }
    if ($match) { $idx = $i; break }
}

if ($idx -ge 0) {
    $level = 0
    $inString = $false
    $escape = $false
    $endIdx = -1
    for ($i = $idx; $i -lt $bytes.Length; $i++) {
        $b = $bytes[$i]
        if ($escape) { $escape = $false; continue }
        if ($b -eq 92) { $escape = $true; continue }
        if ($b -eq 39 && !$escape) { $inString = !$inString; continue }
        if ($inString) { continue }
        if ($b -eq 123) { $level++ }
        if ($b -eq 125) { $level--; if ($level -eq 0) { $endIdx = $i; break } }
    }
    if ($endIdx -ge 0) {
        Write-Output ("SAFE_INGRESS_MESSAGES block: $idx to $endIdx")
        
        $newIngress = @"
const SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.'
};
"@
        $newIngressBytes = [System.Text.Encoding]::UTF8.GetBytes($newIngress)
        $newBytes = New-Object byte[] ($bytes.Length - ($endIdx - $idx + 1) + $newIngressBytes.Length)
        [Array]::Copy($bytes, 0, $newBytes, 0, $idx)
        [Array]::Copy($newIngressBytes, 0, $newBytes, $idx, $newIngressBytes.Length)
        [Array]::Copy($bytes, $endIdx + 1, $newBytes, $idx + $newIngressBytes.Length, $bytes.Length - $endIdx - 1)
        $bytes = $newBytes
        Write-Output "SAFE_INGRESS_MESSAGES replaced"
    }
}

# Write back
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "All replacements done at byte level"