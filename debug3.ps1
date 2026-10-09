Write-Output "=== N8N PERSISTED STATE INSPECTION ==="
Write-Output ""

# Check n8n config directory
Write-Output "-- Checking n8n config directory --"
$home = $env:HOMEPATH + '\.n8n'
if (Test-Path $home) {
    Write-Output "n8n data directory EXISTS: $home"
    Get-ChildItem $home | ForEach-Object { Write-Output "  " + $_.Name }
} else {
    Write-Output "n8n data directory NOT FOUND: $home"
}

Write-Output ""
$appdata = $env:APPDATA
$n8n_config = Join-Path $appdata "n8n"
if (Test-Path $n8n_config) {
    Write-Output "n8n config at APPDATA EXISTS: $n8n_config"
    Get-ChildItem $n8n_config | ForEach-Object { Write-Output "  " + $_.Name }
} else {
    Write-Output "n8n config at APPDATA NOT FOUND"
}

Write-Output ""
Write-Output "-- Trying n8n http API endpoints --"
$apis = @(
    "http://127.0.0.1:5681/.ext/n8n/nodes/describe?node=Webhook",
    "http://127.0.0.1:5681/n8n/nodes",
    "http://127.0.0.1:5681/v1/workflows",
    "http://127.0.0.1:5681/api/workflows"
)

# Loop through APIs using ForEach-Object
$apis | ForEach-Object {
    Write-Output "Testing: $_"
    try {
        $wc2 = New-Object System.Net.WebClient
        $content = $wc2.DownloadString($_)
        Write-Output "  RESPONSE LENGTH: $($content.Length)"
        Write-Output "  FIRST 200 CHARS: $($content.Substring(0,200))"
    } catch {
        Write-Output "  ERROR: $($_.Exception.Message.Substring(0,60))"
    }
}