$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'

# Replace the entire SAFE_MESSAGES block with clean UTF-8
$oldSafe = "SAFE_MESSAGES = {\n  MISSING_INFORMATION: 'Des informations suppl\u019F\u009Fmentaires sont n\u019F\u009Fcessaires pour poursuivre la demande.',\n  CLARIFICATION_LIMIT_REACHED: 'Nombre maximal de demandes de clarification atteint. Veuillez relancer une nouvelle demande.',\n  REJECTED: 'La demande a \u01F9\u009Ft\u01F9\u009F rejet\u01F9\u009Fe : correction impossible sur cette demande (statut terminal en it\u01F9\u009Fration 1).',\n  GENERATION_FAILED: 'La g\u01F9\u009Fn\u01F9\u009Fration du document a \u01F9\u009Fchou\u01F9\u009Fe. Vous pouvez relancer la demande.',\n  UNSUPPORTED_DOCUMENT_TYPE: 'Seul le type de document ATTESTATION_CONCORDANCE est pris en charge en it\u01F9\u009Fration 1.',\n  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',\n  INVALID_REQUEST: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',\n  AI_EXTRACTION_ERROR: 'L\u01F9\u009Fxtraction automatique a \u01F9\u009Fchou\u01F9\u009Fe. Veuillez reformuler votre demande.',\n  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incompl\u01F9\u009Ftes. Veuillez reformuler votre demande.',\n  BACKEND_UNAVAILABLE: 'Le service de g\u01F9\u009Fn\u01F9\u009Fration est momentan\u01F9\u009Fment indisponible. Veuillez r\u01F9\u009Fessayer plus tard.',\n  INTERNAL_ERROR: 'Le traitement de la demande a \u01F9\u009Fchou\u01F9\u009Fe. Veuillez r\u01F9\u009Fessayer plus tard.'\n};"

$newSafe = @"
SAFE_MESSAGES = {
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

$raw2 = $raw.Replace($oldSafe, $newSafe)

# Also replace ERROR_MESSAGES
$oldError = "ERROR_MESSAGES = {\n  VALIDATION_ERROR: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',\n  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',\n  AI_EXTRACTION_ERROR: 'L\u01F9\u009Fxtraction automatique a \u01F9\u009Fchou\u01F9\u009Fe. Veuillez reformuler votre demande.',\n  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incompl\u01F9\u009Ftes. Veuillez reformuler votre demande.',\n  BACKEND_UNAVAILABLE: 'Le service de g\u01F9\u009Fn\u01F9\u009Fration est momentan\u01F9\u009Fment indisponible. Veuillez r\u01F9\u009Fessayer plus tard.',\n  INTERNAL_ERROR: 'Le traitement de la demande a \u01F9\u009Fchou\u01F9\u009Fe. Veuillez r\u01F9\u009Fessayer plus tard.'\n};"

$newError = @"
ERROR_MESSAGES = {
  VALIDATION_ERROR: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',
  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',
  AI_EXTRACTION_ERROR: 'L\'extraction automatique a échoué. Veuillez reformuler votre demande.',
  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incomplètes. Veuillez reformuler votre demande.',
  BACKEND_UNAVAILABLE: 'Le service de génération est momentanément indisponible. Veuillez réessayer plus tard.',
  INTERNAL_ERROR: 'Le traitement de la demande a échoué. Veuillez réessayer plus tard.'
};
"@

$raw2 = $raw2.Replace($oldError, $newError)

# Also fix the InitOrchestration SAFE_INGRESS_MESSAGES
$oldIngress = "SAFE_INGRESS_MESSAGES = {\n  UNEXPECTED_FIELDS: 'La requ\u00C3\u0192\u00C6\u2019\u00C3\u201A\u00C2\u00A2te contient des champs non autoris\u00C3\u0192\u00C6\u2019\u00C3\u201A\u00C2\u00A9s.',\n  INVALID_REQUEST_ID: 'Le champ \u00C3\u0192\u00E2\u20AC\u0161\u00C2\u00AB requestId \u00C3\u0192\u00E2\u20AC\u0161\u00C2\u00BB est absent ou invalide.',\n  MESSAGE_REQUIRED: 'Le champ \u00C3\u0192\u00E2\u20AC\u0161\u00C2\u00AB message \u00C3\u0192\u00E2\u20AC\u0161\u00C2\u00BB est obligatoire.',\n  MESSAGE_LENGTH_INVALID: 'Le champ \u00C3\u0192\u00E2\u20AC\u0161\u00C2\u00AB message \u00C3\u0192\u00E2\u20AC\u0161\u00C2\u00BB doit contenir entre 1 et 4000 caract\u00C3\u0192\u00C6\u2019\u00C3\u201A\u00C2\u00A8res.'\n};"

$newIngress = @"
SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.'
};
"@

$raw2 = $raw2.Replace($oldIngress, $newIngress)

# Also fix the Webhook notes
$oldWebhookNotes = "Correlation-ID de sortie pos\u00C3\u0192\u00C2\u00A9 par RespondToWebhook (1.1). L'option responseHeaders du noeud Webhook a ete SUPPRIMEE : a ce noeud \$json n'est pas encore l'item d'ingress, l'expression evaluait une chaine vide et l'en-tete etait donc mort. Webhook -\u003E InitOrchestration -\u003E IFIngressAccepted : un ingress rejete va directement a FinalizeResponse (0 appel LLM, 0 appel backend, 0 persistance)."
$newWebhookNotes = "Correlation-ID de sortie posé par RespondToWebhook (1.1). L'option responseHeaders du noeud Webhook a ete SUPPRIMEE : a ce noeud \$json n'est pas encore l'item d'ingress, l'expression evaluait une chaine vide et l'en-tete etait donc mort. Webhook -> InitOrchestration -> IFIngressAccepted : un ingress rejete va directement a FinalizeResponse (0 appel LLM, 0 appel backend, 0 persistance)."
$raw2 = $raw2.Replace($oldWebhookNotes, $newWebhookNotes)

# Write back without BOM
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw2)
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Major French text blocks replaced"