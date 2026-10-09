$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$matches = [regex]::Matches($raw, "[ǟÃƒâ€]")
foreach ($m in $matches) {
    $i = $m.Index
    $ctx = $raw.Substring([Math]::Max(0, $i - 50), 120)
    Write-Output ("At $i : $ctx")
    Write-Output "---"
}