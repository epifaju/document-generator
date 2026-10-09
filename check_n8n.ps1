Write-Output "=== N8N RUNTIME INSPECTION ==="
Write-Output ""

# Check port 5681 connectivity
Write-Output "-- Port 5681 connectivity --"
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$p = @{message='test message'} | ConvertTo-Json
try {
    $r = $wc.UploadString('http://127.0.0.1:5681/webhook/document-generation', $p)
    Write-Output "Webhook POST result length: $($r.Length)"
    Write-Output "Webhook POST result body: [$($r)]"
    if ($r.Length -gt 0) {
        Write-Output "SUCCESS: Webhook returned content"
    } else {
        Write-Output "RESULT: Empty body - need to investigate further"
    }
} catch {
    Write-Output "Webhook POST ERROR: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        Write-Output "HTTP Status: $($_.Exception.Response.StatusCode)"
    }
}

Write-Output ""
Write-Output "-- Port 5678 connectivity --"
try {
    $r2 = $wc.UploadString('http://127.0.0.1:5678/webhook/document-generation', $p)
    Write-Output "Webhook POST result length: $($r2.Length)"
    Write-Output "Webhook POST result body: [$($r2)]"
} catch {
    Write-Output "Webhook POST ERROR (port 5678): $($_.Exception.Message)"
}