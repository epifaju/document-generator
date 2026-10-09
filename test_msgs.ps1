# Test with simpler message
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = 'http://127.0.0.1:5681/webhook/document-generation'

# Test 1: Simple ASCII message
$payload1 = @{message='Generate concordance attestation for Maria Gomes'} | ConvertTo-Json
Write-Output "Test 1: Simple message"
try {
    $wr = Invoke-WebRequest -Uri $url -Method Post -Body $payload1 -ContentType "application/json" -ErrorAction Stop
    Write-Output "  Status: $($wr.StatusCode) Length=$($wr.Content.Length)"
} catch {
    Write-Output "  ERROR: $($_.Exception.Message.Substring(0,50))"
}

# Test 2: The French message
$payload2 = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
Write-Output "Test 2: French message"
try {
    $wr = Invoke-WebRequest -Uri $url -Method Post -Body $payload2 -ContentType "application/json" -ErrorAction Stop
    Write-Output "  Status: $($wr.StatusCode) Length=$($wr.Content.Length)"
} catch {
    Write-Output "  ERROR: $($_.Exception.Message.Substring(0,50))"
}

# Test 3: Even simpler - just the required fields mindset
$payload3 = '{"message":"Generate attestation}"}'
Write-Output "Test 3: Minimal JSON"
try {
    $wr = Invoke-WebRequest -Uri $url -Method Post -Body $payload3 -ContentType "application/json" -ErrorAction Stop
    Write-Output "  Status: $($wr.StatusCode) Length=$($wr.Content.Length)"
} catch {
    Write-Output "  ERROR: $($_.Exception.Message.Substring(0,50))"
}