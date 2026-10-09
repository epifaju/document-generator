$bytes = [System.Text.Encoding]::UTF8.GetBytes((Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'))
$idx = -1
for ($i = 0; $i -le $bytes.Length - 5; $i++) {
    if ($bytes[$i] -eq 115 -and $bytes[$i+1] -eq 117 -and $bytes[$i+2] -eq 112 -and $bytes[$i+3] -eq 112 -and $bytes[$i+4] -eq 108) { $idx = $i; break }
}
if ($idx -ge 0) {
    $segment = $bytes[$idx..($idx+30)]
    Write-Output ($segment -join ' ')
}