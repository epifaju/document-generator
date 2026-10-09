$wf = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json' | ConvertFrom-Json

# Fix FinalizeResponse
$node = $wf.nodes | Where-Object { $_.name -eq 'FinalizeResponse' }
$code = $node.parameters.jsCode

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

$newCode = $node.parameters.jsCode
$newCode = $newCode -replace "(?s)const SAFE_MESSAGES = \{.+?\n\};", $newSafe
$newCode = $newCode -replace "(?s)const ERROR_MESSAGES = \{.+?\n\};", $newError
$node.parameters.jsCode = $newCode

# Fix InitOrchestration
$init = $wf.nodes | Where-Object { $_.name -eq 'InitOrchestration' }
$newIngress = @"
const SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.'
};
"@
$init.parameters.jsCode = $init.parameters.jsCode -replace "(?s)const SAFE_INGRESS_MESSAGES = \{.+?\n\};", $newIngress

# Fix Webhook notes
$webhook = $wf.nodes | Where-Object { $_.name -eq 'Webhook' }
$webhook.parameters.notes = $webhook.parameters.notes -replace "pos\u00C3\u0192\u00C2\u00A9", "posé"

# Write back without BOM
$json = $wf | ConvertTo-Json -Depth 20
$bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Done"