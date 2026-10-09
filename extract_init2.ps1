$json = Get-Content -Raw 'n8n/workflows/document-generation-v1.json'
$obj = $json | ConvertFrom-Json
$nodes = $obj.nodes
$found = $nodes | Where-Object { $_.name -eq 'InitOrchestration' }
if ($found) {
    Write-Output "FOUND InitOrchestration node"
    $jsCode = $found.parameters.jsCode
    Write-Output "Full jsCode length: $($jsCode.Length)"
    Write-Output "First 500 chars:"
    # Replace newlines for display
    $display = $jsCode.Substring(0, 500).Replace("`n", "`n")
    Write-Output $display
    Write-Output "Last 100 chars:"
    Write-Output $jsCode.Substring($jsCode.Length - 100).Replace("`n", "`n")
    # Check for good vs bad regex fragments using Select-String
    $goodMatches = (Select-String -Path 'n8n/workflows/document-generation-v1.json' -Pattern '/\[xy\]/, function \(c' -Count).Path
    # Actually let's just search the jsCode string
    $goodCount = 0
    $badCount = 0
    if ($jsCode -match '/\[xy\]/, function \(c') { $goodCount++ }
    if ($jsCode -match '/\[xy\]function\(c\)') { $badCount++ }
    Write-Output "Good fragment count (.replace(/[xy]/, function (c) {): $goodCount"
    Write-Output "Bad fragment count (.replace(/[xy]/function(c) {): $badCount"
    # Compute SHA256 of the full file
    $hash = (Get-FileHash 'n8n/workflows/document-generation-v1.json' -Algorithm SHA256).Hash
    $hashLower = $hash.ToLower()
    Write-Output "Full file SHA256: $hashLower"
    # Check for crypto
    if ($jsCode -match 'require\(.crypto') { Write-Output "Contains require('crypto'): True" } else { Write-Output "Contains require('crypto'): False" }
} else {
    Write-Output "InitOrchestration node NOT found"
}