$wf = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json' | ConvertFrom-Json
$node = $wf.nodes | Where-Object { $_.name -eq 'FinalizeResponse' }
$code = $node.parameters.jsCode
# The exact gap is: `};` + newline + `// FR message...`
$old = "};" + [Environment]::NewLine + "// FR message authored by one of our own Code nodes (internalDetail proves it), never relayed from"
$new = "};" + [Environment]::NewLine + "  // Native UUID v4 generator (replaces require('crypto').randomUUID() which is not allowed in n8n Code nodes)" + [Environment]::NewLine + "  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {" + [Environment]::NewLine + "    const r = Math.floor(Math.random() * 16);" + [Environment]::NewLine + "    const v = c === 'x' ? r : (r & 0x3) | 0x8;" + [Environment]::NewLine + "    return v.toString(16);" + [Environment]::NewLine + "  });" + [Environment]::NewLine + "};" + [Environment]::NewLine + [Environment]::NewLine + "// FR message authored by one of our own Code nodes (internalDetail proves it), never relayed from"
if ($code.Contains($old)) {
    $node.parameters.jsCode = $code.Replace($old, $new)
    $json = $wf | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $json, [System.Text.Encoding]::UTF8)
    Write-Output "REPLACED"
} else {
    Write-Output "NOT FOUND"
    # Check exact
    $idx = $code.IndexOf("};")
    while ($idx -ge 0) {
        $next = $code.Substring($idx, 80)
        if ($next.Contains("FR message")) { break }
        $idx = $code.IndexOf("};", $idx + 1)
    }
    Write-Output ("FOUND AT: " + $idx)
    Write-Output $code.Substring($idx, 120)
}