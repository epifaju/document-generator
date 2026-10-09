$json = Get-Content -Raw 'n8n/workflows/document-generation-v1.json'
$obj = $json | ConvertFrom-Json
$nodes = $obj.nodes
$found = $nodes | Where-Object { $_.name -eq 'InitOrchestration' }
if ($found) {
    Write-Output "FOUND InitOrchestration node"
    $jsCode = $found.parameters.jsCode
    Write-Output "Full jsCode length: $($jsCode.Length)"
    Write-Output "First 500 chars:"
    Write-Output $jsCode.Substring(0, 500)
    Write-Output "Last 100 chars:"
    Write-Output $jsCode.Substring($jsCode.Length - 100)
    # Check for good vs bad regex fragments
    $good = $jsCode -count '/\[xy\]/, function (c)'
    $bad = $jsCode -count '/\[xy\]function(c)'
    Write-Output "Good fragment count (.replace(/[xy]/, function (c) {): $good"
    Write-Output "Bad fragment count (.replace(/[xy]/function(c) {): $bad"
    # Compute SHA256 of the full file
    $hash = (Get-FileHash 'n8n/workflows/document-generation-v1.json' -Algorithm SHA256).Hash
    $hashLower = $hash.ToLower()
    Write-Output "Full file SHA256: $hashLower"
    # Check for crypto
    $hasCrypto = $jsCode -match 'require\(.crypto.'
    Write-Output "Contains require('crypto'): $hasCrypto"
} else {
    Write-Output "InitOrchestration node NOT found"
}