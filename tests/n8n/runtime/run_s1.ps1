# Usage : envoie le scénario S1 au webhook document-generation et affiche outcome, correlationId et requestId. Base n8n : $env:N8N_BASE_URL (défaut http://127.0.0.1:5678).
# G6 : exécution soumise à accord explicite.
$n8nBase = if ($env:N8N_BASE_URL) { $env:N8N_BASE_URL } else { 'http://127.0.0.1:5678' }
$n8nBase = $n8nBase.TrimEnd('/')
$n8nHost = ([Uri]$n8nBase).Host
if ($n8nHost -ne '127.0.0.1' -and $n8nHost -ne 'localhost' -and $env:N8N_BASE_URL_ALLOW_REMOTE -ne '1') {
    throw "Refus : N8N_BASE_URL doit viser 127.0.0.1 ou localhost. Pour un autre hôte, définir N8N_BASE_URL_ALLOW_REMOTE=1."
}
$payload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = "$n8nBase/webhook/document-generation"
Write-Output "Sending webhook to: $url"
Write-Output "Payload length: $($payload.Length)"
$result = $wc.UploadString($url, $payload)
Write-Output "Webhook raw result: $result"
Write-Output "=== PARSE ==="
try {
    $parsed = $result | ConvertFrom-Json
    Write-Output "Outcome: $($parsed.outcome)"
    Write-Output "correlationId: $($parsed.correlationId)"
    Write-Output "requestId: $($parsed.requestId)"
} catch {
    Write-Output "Response not JSON"
}