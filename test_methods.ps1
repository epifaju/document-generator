$wc = New-Object System.Net.WebClient
# Test POST empty
try {
    $result = $wc.UploadString('http://127.0.0.1:5678/', '')
    Write-Output "5678 POST empty: Length=$($result.Length)"
} catch {
    Write-Output "5678 POST empty: ERROR ($($_.Exception.Message.Substring(0,30)))"
}
# Test POST with JSON body
$payload = @{message='test'} | ConvertTo-Json
try {
    $result = $wc.UploadString('http://127.0.0.1:5678/', $payload)
    Write-Output "5678 POST JSON: Length=$($result.Length)"
} catch {
    Write-Output "5678 POST JSON: ERROR ($($_.Exception.Message.Substring(0,30)))"
}
# Test the actual webhook
$webhookPayload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
try {
    $result = $wc.UploadString('http://127.0.0.1:5678/webhook/document-generation', $webhookPayload)
    Write-Output "5678 webhook: Length=$($result.Length)"
    Write-Output "5678 webhook raw: $($result.Substring(0,200))"
} catch {
    Write-Output "5678 webhook: ERROR ($($_.Exception.Message.Substring(0,30)))"
}