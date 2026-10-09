$payload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = 'http://127.0.0.1:5681/webhook/document-generation'
$result = $wc.UploadString($url, $payload)
# Write raw result
Write-Output "===RAW RESULT==="
Write-Output $result
Write-Output "===END RAW==="
# Try to parse
Write-Output "===PARSE==="
try {
    $parsed = $result | ConvertFrom-Json
    Write-Output "Outcome: $($parsed.outcome)"
    Write-Output "correlationId: $($parsed.correlationId)"
    Write-Output "requestId: $($parsed.requestId)"
} catch {
    Write-Output "Not JSON or parse error"
}