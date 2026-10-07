# Compute SHA256 and counts for repository InitOrchestration jsCode
$content = Get-Content 'n8n/workflows/document-generation-v1.json' -Raw
$json = $content | ConvertFrom-Json
foreach ($node in $json.nodes) {
    if ($node.name -eq 'InitOrchestration') {
        $js = $node.parameters.jsCode
        Write-Output 'REPO_INIT_SHA256: ' + ([System.BitConverter]::ToString(
            ([System.Security.Cryptography.SHA256]::Create()).ComputeHash(
                [System.Text.Encoding]::UTF8.GetBytes($js)))) -replace '-'
        Write-Output 'REPO_REQUIRE_CRYPTO: ' (($js -match 'require\(.crypto.\)'').Count)
        Write-Output 'REPO_GENERATE_UUID: ' (($js -match 'function generateUUID').Count)
        Write-Output 'REPO_GOOD_FRAG: ' (($js -match '\/\[xy\]\/,\s*function').Count)
        Write-Output 'REPO_BAD_FRAG: ' (($js -match '\/\[xy\]function').Count)
        break
    }
}