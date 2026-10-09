# Try various n8n endpoints with different methods
$urls = @{
    "5681_get" = "http://127.0.0.1:5681/";
    "5681_post_empty" = "http://127.0.0.1:5681/";
    "5678_get" = "http://127.0.0.1:5678/";
}

# Use Invoke-WebRequest for GET
try {
    $wr = Invoke-WebRequest -Uri $urls."5681_get" -Method Get -ErrorAction Stop
    Write-Output "5681 GET: Status=$($wr.StatusCode) Length=$($wr.Content.Length)"
} catch {
    Write-Output "5681 GET: ERROR"
}

# Use WebClient UploadString for POST (defaults to POST)
$wc = New-Object System.Net.WebClient
try {
    $result = $wc.UploadString("http://127.0.0.1:5681/", "")
    Write-Output "5681 POST empty: Length=$($result.Length)"
} catch {
    Write-Output "5681 POST empty: ERROR"
}

# Try POST with JSON body
$payload = @{message='test'} | ConvertTo-Json
try {
    $result = $wc.UploadString("http://127.0.0.1:5681/", $payload)
    Write-Output "5681 POST JSON: Length=$($result.Length)"
} catch {
    Write-Output "5681 POST JSON: ERROR"
}