Write-Output "=== N8N REST API TESTS ==="
Write-Output ""

$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')

# Define endpoints array using Add method
$endpoints = New-Object System.Collections.ArrayList
$endpoints.Add("http://127.0.0.1:5681/api/v1/workflows")
$endpoints.Add("http://127.0.0.1:5681/api/v1/workflows/1oVzZvEXnWWY3R9F")
$endpoints.Add("http://127.0.0.1:5681/api/v1/executions")
$endpoints.Add("http://127.0.0.1:5681/rest/workflows")

# Loop through endpoints
for ($i = 0; $i -lt $endpoints.Count; $i++) {
    $ep = $endpoints[$i]
    Write-Output "Trying: $ep"
    try {
        $r = $wc.DownloadString($ep)
        Write-Output "SUCCESS: Length=" + $r.Length
        Write-Output "First 200: " + $r.Substring(0,200)
    } catch {
        Write-Output "ERROR: " + $_.Exception.Message.Substring(0,60)
    }
    Write-Output ""
}