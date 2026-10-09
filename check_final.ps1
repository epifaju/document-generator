$wf = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json' | ConvertFrom-Json
$node = $wf.nodes | Where-Object { $_.name -eq 'LoadPrompt' }
$code = $node.parameters.jsCode
$matches = [regex]::Matches($code, "require\(['\"]([^'\"]+)['\"]\)")
foreach ($m in $matches) { Write-Output ("require: " + $m.Groups[1].Value) }
Write-Output ("active: " + $wf.active)
Write-Output ("Nodes: " + $wf.nodes.Count)