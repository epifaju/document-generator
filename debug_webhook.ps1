$p = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = 'http://127.0.0.1:5681/webhook/document-generation'
# Capture all response info
$result = $wc.UploadString($url, $p)
Write-Output "=== BODY ==="
Write-Output $result
Write-Output "=== HEADERS ==="
# Try to get headers
try { 
    # n8n respondToWebhook might not expose headers directly
} catch { Write-Output "Headers: error" }
# Check result length
Write-Output "Length: $($result.Length)"
# Check if empty
if (-not $result -or $result -eq '') {
    Write-Output "RESULT IS EMPTY"
}