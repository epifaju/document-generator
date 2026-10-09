$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

# Search for SAFE_MESSAGES
$searchBytes = [System.Text.Encoding]::UTF8.GetBytes("SAFE_MESSAGES")
$idx = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes[$j]) { $match = $false; break }
    }
    if ($match) { $idx = $i; break }
}
Write-Output ("SAFE_MESSAGES found at: " + $idx)

# Search for ERROR_MESSAGES
$searchBytes2 = [System.Text.Encoding]::UTF8.GetBytes("ERROR_MESSAGES")
$idx2 = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes2.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes2.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes2[$j]) { $match = $false; break }
    }
    if ($match) { $idx2 = $i; break }
}
Write-Output ("ERROR_MESSAGES found at: " + $idx2)

# Search for SAFE_INGRESS_MESSAGES
$searchBytes3 = [System.Text.Encoding]::UTF8.GetBytes("SAFE_INGRESS_MESSAGES")
$idx3 = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes3.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes3.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes3[$j]) { $match = $false; break }
    }
    if ($match) { $idx3 = $i; break }
}
Write-Output ("SAFE_INGRESS_MESSAGES found at: " + $idx3)