# Usage : vérifie que n8n, son webhook, le backend sur 127.0.0.1:8080 et Ollama sur 127.0.0.1:11434 répondent. Base n8n : $env:N8N_BASE_URL (défaut http://127.0.0.1:5678).
# G6 : exécution soumise à accord explicite.
$n8nBase = if ($env:N8N_BASE_URL) { $env:N8N_BASE_URL } else { 'http://127.0.0.1:5678' }
$n8nBase = $n8nBase.TrimEnd('/')
$n8nHost = ([Uri]$n8nBase).Host
if ($n8nHost -ne '127.0.0.1' -and $n8nHost -ne 'localhost' -and $env:N8N_BASE_URL_ALLOW_REMOTE -ne '1') {
    throw "Refus : N8N_BASE_URL doit viser 127.0.0.1 ou localhost. Pour un autre hôte, définir N8N_BASE_URL_ALLOW_REMOTE=1."
}
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')

Write-Output "=== INFRASTRUCTURE REACHABILITY ==="
Write-Output ""

# n8n (base $env:N8N_BASE_URL)
Write-Output "-- n8n ($n8nBase) --"
try {
    $r = $wc.UploadString("$n8nBase/", '')
    Write-Output "Length: $($r.Length) Body: $($r)"
} catch {
    Write-Output "Error: $($_.Exception.Message.Substring(0,30))"
}

# webhook document-generation sur la même base
Write-Output "-- webhook document-generation ($n8nBase) --"
try {
    $r = $wc.UploadString("$n8nBase/webhook/document-generation", '')
    Write-Output "Length: $($r.Length) Body: $($r.Substring(0,200))"
} catch {
    Write-Output "Error: $($_.Exception.Message.Substring(0,30))"
}

# Port 8080 (backend)
Write-Output "-- Port 8080 (backend actuator health) --"
try {
    $r = $wc.UploadString('http://127.0.0.1:8080/actuator/health', '')
    Write-Output "Length: $($r.Length) Body: $($r.Substring(0,200))"
} catch {
    Write-Output "Error: $($_.Exception.Message.Substring(0,30))"
}

# Port 11434 (ollama)
Write-Output "-- Port 11434 (ollama api/tags) --"
try {
    $r = $wc.UploadString('http://127.0.0.1:11434/api/tags', '{"model":"llama3.1","stream":false}')
    Write-Output "Length: $($r.Length) Models: $($r.Substring(0,300))"
} catch {
    Write-Output "Error: $($_.Exception.Message.Substring(0,30))"
}