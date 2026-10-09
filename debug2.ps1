Write-Output "=== DETAILED N8N WEBHOOK DEBUG ==="
Write-Output ""

$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$p = @{message='test message'} | ConvertTo-Json

# Test 1: POST to 5681 webhook - get full error info
Write-Output "-- Test 1: POST /webhook/document-generation on port 5681 --"
try {
    $result = $wc.UploadString('http://127.0.0.1:5681/webhook/document-generation', $p)
    Write-Output "Body length: $($result.Length)"
    Write-Output "Body: $result"
} catch [System.Net.WebException] {
    Write-Output "WebException caught"
    if ($exception.Response) {
        Write-Output "Status Code: $($exception.Response.StatusCode)"
        Write-Output "Status Description: $($exception.Response.StatusDescription)"
        $stream = $exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader $stream, [System.Text.Encoding]::UTF8
        $errorBody = $reader.ReadToEnd()
        Write-Output "Error body: $errorBody"
    }
} catch {
    Write-Output "Other error: $($_.Exception.Message)"
}

Write-Output ""
Write-Output "-- Test 2: GET /webhook on port 5681 --"
try {
    $result2 = $wc.UploadString('http://127.0.0.1:5681/webhook', $p)
    Write-Output "Body length: $($result2.Length)"
} catch {
    Write-Output "Error: $($_.Exception.Message)"
}

Write-Output ""
Write-Output "-- Test 3: POST root on port 5681 --"
try {
    $result3 = $wc.UploadString('http://127.0.0.1:5681/', $p)
    Write-Output "Body length: $($result3.Length)"
} catch {
    Write-Output "Error: $($_.Exception.Message)"
}

Write-Output ""
Write-Output "-- Test 4: Invoke-WebRequest POST on port 5681 --"
try {
    $wr = Invoke-WebRequest -Uri 'http://127.0.0.1:5681/webhook/document-generation' -Method Post -Body $p -ContentType 'application/json' -ErrorAction Stop
    Write-Output "StatusCode: $($wr.StatusCode)"
    Write-Output "StatusDescription: $($wr.StatusDescription)"
    Write-Output "Content length: $($wr.Content.Length)"
    Write-Output "Content: $($wr.Content)"
} catch {
    Write-Output "Error: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        Write-Output "HTTP Status: $($_.Exception.Response.StatusCode)"
        Write-Output "Error body: $($_.Exception.Response.StatusDescription)"
    }
}

Write-Output ""
Write-Output "-- Test 5: Invoke-WebRequest GET on port 5681 --"
try {
    $wr2 = Invoke-WebRequest -Uri 'http://127.0.0.1:5681/' -Method Get -ErrorAction Stop
    Write-Output "StatusCode: $($wr2.StatusCode)"
} catch {
    Write-Output "Error: $($_.Exception.Message)"
}