$bytes = [System.Text.Encoding]::UTF8.GetBytes((Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'))

function ReplaceBytes($bytes, $search, $replace) {
    $result = @()
    $i = 0
    while ($i -lt $bytes.Length) {
        $match = $true
        if ($i + $search.Length -le $bytes.Length) {
            for ($j = 0; $j -lt $search.Length; $j++) {
                if ($bytes[$i + $j] -ne $search[$j]) { $match = $false; break }
            }
        } else { $match = $false }
        if ($match) {
            $result += $replace
            $i += $search.Length
        } else {
            $result += $bytes[$i]
            $i++
        }
    }
    return [byte[]]$result
}

$bytes = [System.Text.Encoding]::UTF8.GetBytes((Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'))

# Fix double-encoded UTF-8 sequences
$bytes = ReplaceBytes $bytes @(195,131,194,169) @(195,169)  # é
$bytes = ReplaceBytes $bytes @(195,131,194,168) @(195,168)  # è
$bytes = ReplaceBytes $bytes @(195,131,194,160) @(195,160)  # à
$bytes = ReplaceBytes $bytes @(195,131,194,170) @(195,170)  # ê
$bytes = ReplaceBytes $bytes @(195,131,194,174) @(195,174)  # î
$bytes = ReplaceBytes $bytes @(195,131,194,180) @(195,180)  # ô
$bytes = ReplaceBytes $bytes @(195,131,194,167) @(195,167)  # ç
$bytes = ReplaceBytes $bytes @(195,131,194,185) @(195,185)  # ù
$bytes = ReplaceBytes $bytes @(195,131,194,187) @(195,187)  # û
$bytes = ReplaceBytes $bytes @(195,131,194,162) @(195,162)  # â
$bytes = ReplaceBytes $bytes @(195,131,194,164) @(195,164)  # ä
$bytes = ReplaceBytes $bytes @(195,131,194,186) @(195,186)  # æ
$bytes = ReplaceBytes $bytes @(195,131,194,182) @(195,182)  # ö
$bytes = ReplaceBytes $bytes @(195,131,194,188) @(195,188)  # ü

# Write back
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Done"