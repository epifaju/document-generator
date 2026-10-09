$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

# Find the SAFE_MESSAGES block by searching for the key bytes
# Search for "SAFE_MESSAGES" in bytes
$searchText = "SAFE_MESSAGES"
$searchBytes = [System.Text.Encoding]::UTF8.GetBytes($searchText)
$idx = -1
for ($i = 0; $i -le $bytes.Length - $searchBytes.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $searchBytes.Length; $j++) {
        if ($bytes[$i + $j] -ne $searchBytes[$j]) { $match = $false; break }
    }
    if ($match) { $idx = $i; break }
}

if ($idx -ge 0) {
    Write-Output ("Found SAFE_MESSAGES at byte offset: $idx")
    # Show surrounding bytes
    $start = [Math]::Max(0, $idx - 20)
    $len = 800
    $segment = $bytes[$start..($start + $len - 1)]
    Write-Output ([System.Text.Encoding]::UTF8.GetString($segment))
} else {
    Write-Output "SAFE_MESSAGES not found in bytes"
}