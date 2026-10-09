$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')

Write-Output "=== INFRASTRUCTURE REACHABILITY ==="
Write-Output ""

# Port 5678 (n8n)
Write-Output "-- Port 5678 (n8n) --"
try {
    $r = $wc.UploadString('http://127.0.0.1:5678/', '')
    Write-Output "SUCCESS - Length: $($r.Length)"
} catch [System.Net.WebException] {
    Write-ERROR "WEBEXCEPTION - Status: $($_.Exception.Response.StatusCode)" 
    Write-ERROR "Message: $($_.Exception.Message.Substring(0,50))"
} catch {
    Write-ERROR "OTHER - $($_.Exception.Message.Substring(0,30))"
}

Write-Output ""

# Port 5681 (webhook)
Write-Output "-- Port 5681 (document-generation webhook) --"
try {
    $r = $wc.UploadString('http://127.0.0.1:5681/webhook/document-generation', '')
    Write-Output "SUCCESS - Length: $($r.Length) Body (first 200): $($r.Substring(0,200))"
} catch [System.Net.WebException] {
    Write-ERROR "WEBEXCEPTION - Status: $($_.Exception.Response.StatusCode)"
    Write-ERROR "Message: $($_.Exception.Message.Substring(0,50))"
} catch {
    Write-ERROR "OTHER - $($_.Exception.Message.Substring(0,30))"
}

Write-Output ""

# Port 8080 (backend)
Write-Output "-- Port 8080 (backend actuator health) --"
try {
    $r = $wc.UploadString('http://127.0.0.1:8080/actuator/health', '')
    Write-Output "SUCCESS - Length: $($r.Length) Body (first 200): $($r.Substring(0,200))"
} catch [System.Net.WebException] {
    Write-ERROR "WEBEXCEPTION - Status: $($_.Exception.Response.StatusCode)"
    Write-ERROR "Message: $($_.Exception.Message.Substring(0,50))"
} catch {
    Write-ERROR "OTHER - $($_.Exception.Message.Substring(0,30))"
}

Write-Output ""

# Port 11434 (ollama)
Write-Output "-- Port 11434 (ollama api/tags) --"
try {
    $r = $wc.UploadString('http://127.0.0.1:11434/api/tags', '{"model":"llama3.1","stream":false}')
    Write-Output "SUCCESS - Length: $($r.Length) Models (first 300): $($r.Substring(0,300))"
} catch [System.Net.WebException] {
    Write-ERROR "WEBEXCEPTION - Status: $($_.Exception.Response.StatusCode)"
    Write-ERROR "Message: $($_.Exception.Message.Substring(0,50))"
} catch {
    Write-ERROR "OTHER - $($_.Exception.Message.Substring(0,30))"
}