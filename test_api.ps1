# Try n8n API endpoints
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')

# Test various n8n API paths
$endpoints = @(
    "http://127.0.0.1:5681/.ext/n8n/nodes/describe?node=Webhook",
    "http://127.0.0.1:5681/n8n/nodes",
    "http://127.0.0.1:5681/health"
)

foreach ($ep in $endpoints) {
    Write-Output "Testing: $ep"
    try {
        $result = $wc.DownloadString($ep)
        Write-Output "  OK: $($result.Substring(0,100))"
    } catch {
        Write-Output "  ERROR: $($_.Exception.Message.Substring(0,40))"
    }
    Write-Output ""
}