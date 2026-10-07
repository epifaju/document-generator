# Send synthetic webhook request
$payload = @{
    message = "Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau..."
} | ConvertTo-Json

$wc = New-Object System.Net.WebClient
$wc.Headers.Add("Content-Type", "application/json")
$url = "http://127.0.0.1:5681/webhook/document-generation"

Write-Output "Sending webhook to: $url"
Write-Output "Payload length: $($payload.Length)"

try {
    $result = $wc.UploadString($url, $payload)
    Write-Output "Webhook response: $result"
    
    # Try to parse as JSON
    try {
        $parsed = $result | ConvertFrom-Json
        Write-Output "Outcome: $($parsed.outcome)"
        Write-Output "correlationId: $($parsed.correlationId)"
        Write-Output "Nodes executed: custom parsing needed"
    } catch {
        Write-Output "Response not JSON: $result"
    }
} catch {
    Write-Output "Webhook error: $($_.Exception.Message)"
}