Write-Output "=== PHASE 1: CONTAINER & PERSISTENCE INSPECTION ==="
Write-Output ""

# Try n8n internal API endpoints for workflow state and persistence
$endpoints = @(
    "http://127.0.0.1:5681/.ext/n8n/nodes/describe?node=Webhook",
    "http://127.0.0.1:5681/n8n/nodes",
    "http://127.0.0.1:5681/.ext/n8n/workflows/document-generation-v1",
    "http://127.0.0.1:5681/.ext/n8n/workflows",
    "http://127.0.0.1:5681/.ext/n8n/executions",
    "http://127.0.0.1:5681/.ext/n8n/settings",
    "http://127.0.0.1:5681/.ext/n8n/nodes/node-types",
    "http://127.0.0.1:5681/.ext/n8n/nodes/describe?node=InitOrchestration"
)

# Loop through each endpoint using ForEach-Object
$endpoints | ForEach-Object {
    Write-Output "Testing: $_"
    try {
        $wc2 = New-Object System.Net.WebClient
        $result = $wc2.DownloadString($_)
        Write-Output "  RESPONSE LENGTH: $($result.Length)"
        # Show first 200 chars
        Write-Output "  FIRST 200: $($result.Substring(0,200))"
    } catch {
        Write-Output "  ERROR: $($_.Exception.Message.Substring(0,60))"
    }
    Write-Output ""
}

Write-Output "-- Checking n8n version and config --"
# n8n root endpoint
try {
    $r = (New-Object System.Net.WebClient).DownloadString('http://127.0.0.1:5681/')
    Write-Output "Root response length: $($r.Length)"
} catch {
    Write-Output "Root endpoint error: $($_.Exception.Message.Substring(0,60))"
}

Write-Output "-- Checking n8n info endpoint --"
try {
    $r = (New-Object System.Net.WebClient).DownloadString('http://127.0.0.1:5681/api/v1/info')
    Write-Output "Info response length: $($r.Length)"
    Write-Output "First 300: $($r.Substring(0,300))"
} catch {
    Write-Output "Info endpoint error: $($_.Exception.Message.Substring(0,60))"
}