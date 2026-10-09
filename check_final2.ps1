$wf = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json' | ConvertFrom-Json
$node = $wf.nodes | Where-Object { $_.name -eq 'LoadPrompt' }
$code = $node.parameters.jsCode
if ($code.Contains("require(")) {
    $idx = $code.IndexOf("require(")
    while ($idx -ge 0) {
        $end = $code.IndexOf(")", $idx)
        if ($end -ge 0) { Write-Output $code.Substring($idx, $end - $idx + 1) }
        $idx = $code.IndexOf("require(", $idx + 1)
    }
} else { Write-Output "No require calls" }
Write-Output ("active: " + $wf.active)
Write-Output ("Nodes: " + $wf.nodes.Count)