$wf = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json' | ConvertFrom-Json
foreach ($n in $wf.nodes) {
    if ($n.type -eq 'n8n-nodes-base.code' -and $n.parameters.jsCode) {
        $c = $n.parameters.jsCode
        if ($c.Contains("require('crypto'")) {
            Write-Output ("Node " + $n.name + " has crypto require")
        }
        if ($c.Contains('require("crypto"')) {
            Write-Output ("Node " + $n.name + ' has crypto require (double quotes)')
        }
    }
}
Write-Output "Done"