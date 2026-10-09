# Try various n8n endpoints
$urls = @(
    "http://127.0.0.1:5681/",
    "http://127.0.0.1:5681/webhook",
    "http://127.0.0.1:5681/api",
    "http://127.0.0.1:5678/",
    "http://127.0.0.1:5678/webhook",
    "http://127.0.0.1:5678/api"
)

$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')

foreach ($u in $urls) {
    try {
        $result = $wc.UploadString($u, "GET")
        Write-Output "[$u] Length=$($result.Length) Body=$($result.Substring(0,100))"
    } catch {
        Write-Output "[$u] ERROR: $($_.Exception.Message.Substring(0,50))"
    }
}