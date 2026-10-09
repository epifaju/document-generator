$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'

# Sequential replacements for corrupted French text -> proper UTF-8
$raw2 = $raw

# Fix é characters (corrupted as \u01F9\u009F or similar)
$raw2 = $raw2 -replace "suppl\u01F9\u009Fmentaires", "supplémentaires"
$raw2 = $raw2 -replace "n\u01F9\u009Fcessaires", "nécessaires"
$raw2 = $raw2 -replace "a \u01F9\u009Ft\u01F9\u009F rejet\u01F9\u009Fe", "a été rejetée"
$raw2 = $raw2 -replace "it\u01F9\u009Fration", "itération"
$raw2 = $raw2 -replace "La g\u01F9\u009Fn\u01F9\u009Fration", "La génération"
$raw2 = $raw2 -replace "\u01F9\u009Fchou\u01F9\u009Fe", "échouée"
$raw2 = $raw2 -replace "\u01F9\u009Fchou\u01F9\u009F", "échou"
$raw2 = $raw2 -replace "Le service de g\u01F9\u009Fn\u01F9\u009Fration", "Le service de génération"
$raw2 = $raw2 -replace "Le traitement de la demande a \u01F9\u009Fchou\u01F9\u009Fe", "Le traitement de la demande a échoué"
$raw2 = $raw2 -replace "momentan\u01F9\u009Fment", "momentanément"
$raw2 = $raw2 -replace "Veuillez r\u01F9\u009Fessayer", "Veuillez réessayer"
$raw2 = $raw2 -replace "statut terminal en it\u01F9\u009Fration", "statut terminal en itération"
$raw2 = $raw2 -replace "La g\u01F9\u009Fn\u01F9\u009Fration du document a \u01F9\u009Fchou\u01F9\u009Fe", "La génération du document a échoué"
$raw2 = $raw2 -replace "Vous pouvez relancer la demande", "Vous pouvez relancer la demande"
$raw2 = $raw2 -replace "Seul le type de document ATTESTATION_CONCORDANCE est pris en charge en it\u01F9\u009Fration", "Seul le type de document ATTESTATION_CONCORDANCE est pris en charge en itération"
$raw2 = $raw2 -replace "Demande introuvable", "Demande introuvable"
$raw2 = $raw2 -replace "Veuillez relancer une nouvelle demande", "Veuillez relancer une nouvelle demande"
$raw2 = $raw2 -replace "Votre message ne respecte pas le format attendu", "Votre message ne respecte pas le format attendu"
$raw2 = $raw2 -replace "Veuillez le reformuler", "Veuillez le reformuler"
$raw2 = $raw2 -replace "L\u01F9\u009Fxtraction automatique a \u01F9\u009Fchou\u01F9\u009Fe", "L'extraction automatique a échoué"
$raw2 = $raw2 -replace "Veuillez reformuler votre demande", "Veuillez reformuler votre demande"
$raw2 = $raw2 -replace "Les informations extraites sont invalides ou incompl\u01F9\u009Ftes", "Les informations extraites sont invalides ou incomplètes"
$raw2 = $raw2 -replace "Le service de g\u01F9\u009Fn\u01F9\u009Fration", "Le service de génération"
$raw2 = $raw2 -replace "momentan\u01F9\u009Fment", "momentanément"
$raw2 = $raw2 -replace "Veuillez r\u01F9\u009Fessayer plus tard", "Veuillez réessayer plus tard"
$raw2 = $raw2 -replace "Le traitement de la demande a \u01F9\u009Fchou\u01F9\u009Fe", "Le traitement de la demande a échoué"
$raw2 = $raw2 -replace "Informations manquantes pour g\u01F9\u009Fn\u01F9\u009Fr\u01F9\u009Fer l\u01F9\u009F", "Informations manquantes pour générer l'"
$raw2 = $raw2 -replace "attestation de concordance", "attestation de concordance"
$raw2 = $raw2 -replace "Merci de les fournir", "Merci de les fournir"

# Also fix the mojibake patterns with ǟ
$raw2 = $raw2 -replace "ǟ\u009F\u0083\u003FT\u01F9\u009Fs\u009F\u009Fe", "é"
$raw2 = $raw2 -replace "ǟ\u009F\u0083\u003FT\u01F9\u009Fs\u009F\u009F", "é"
$raw2 = $raw2 -replace "ǟ\u009F\u0083\u003FT\u01F9\u009F", "é"
$raw2 = $raw2 -replace "ǟ\u009F\u0083", "é"

# Write back without BOM
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw2)
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "French text fixes applied"