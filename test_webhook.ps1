# Send webhook request with synthetic data
$body = @{
    message = "Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau..."
} | ConvertTo-Json

$wc = New-Object System.Net.WebClient
$wc.Headers.Add("Content-Type", "application/json")
$url = "http://127.0.0.1:5681/webhook/document-generation"
try {
    $result = $wc.UploadString($url, $body)
    Write-Output "Webhook raw result: [$result]"
    # Try to parse as JSON
    try {
        $parsed = $result | ConvertFrom-Json
        Write-Output "Parsed result type: $($parsed.GetType().Name)"
        Write-Output "outcome: $($parsed.outcome)"
        Write-ExecutionId: $($parsed.correlationId)"
    } catch {
        Write-Output "Result not JSON: $result"
    }
} catch {
    $err = $_.Exception.Message
    Write-Output "Webhook error: $err"
}