$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

# Fix the double-encoded UTF-8 sequences
# The pattern: 0xC3 0x83 0xC2 0xA9 (195 131 194 169) = "Ãƒ©" -> should be 0xC3 0xA9 (195 169) = "é"
# The pattern: 0xC3 0x83 0xC2 0xA8 (195 131 194 168) = "Ãƒ¨" -> should be 0xC3 0xA8 (195 168) = "è"
# The pattern: 0xC3 0x83 0xC2 0xA0 (195 131 194 160) = "Ãƒ " -> should be 0xC3 0xA0 (195 160) = "à"
# The pattern: 0xC3 0x83 0xC2 0xAA (195 131 194 170) = "Ãƒª" -> should be 0xC3 0xAA (195 170) = "ê"
# The pattern: 0xC3 0x83 0xC2 0xAE (195 131 194 174) = "Ãƒ®" -> should be 0xC3 0xAE (195 174) = "î"
# The pattern: 0xC3 0x83 0xC2 0xB4 (195 131 194 180) = "Ãƒ´" -> should be 0xC3 0xB4 (195 180) = "ô"
# The pattern: 0xC3 0x83 0xC2 0xA7 (195 131 194 167) = "Ãƒ§" -> should be 0xC3 0xA7 (195 167) = "ç"
# The pattern: 0xC3 0x83 0xC2 0xB9 (195 131 194 185) = "Ãƒ¹" -> should be 0xC3 0xB9 (195 185) = "ù"
# The pattern: 0xC3 0x83 0xC2 0xBB (195 131 194 187) = "Ãƒ»" -> should be 0xC3 0xBB (195 187) = "û"
# The pattern: 0xC3 0x83 0xC2 0xA0 (195 131 194 160) = "Ãƒ " -> should be 0xC3 0xA0 (195 160) = "à"
# The pattern: 0xC3 0x83 0xC2 0xA2 (195 131 194 162) = "Ãƒ¢" -> should be 0xC3 0xA2 (195 162) = "â"
# The pattern: 0xC3 0x83 0xC2 0xA4 (195 131 194 164) = "Ãƒ¤" -> should be 0xC3 0xA4 (195 164) = "ä"
# The pattern: 0xC3 0x83 0xC2 0xA7 (195 131 194 167) = "Ãƒ§" -> should be 0xC3 0xA7 (195 167) = "ç"
# The pattern: 0xC3 0x83 0xC2 0xB9 (195 131 194 185) = "Ãƒ¹" -> should be 0xC3 0xB9 (195 185) = "ù"
# The pattern: 0xC3 0x83 0xC2 0xBB (195 131 194 187) = "Ãƒ»" -> should be 0xC3 0xBB (195 187) = "û"

# Also fix the Webhook notes: "posé" was corrupted to "posÃƒÂ©" (195 131 194 169)
# And the InitOrchestration SAFE_INGRESS_MESSAGES

function ReplaceBytes($bytes, $search, $replace) {
    $result = New-Object System.Collections.Generic.List[byte]
    $i = 0
    while ($i -lt $bytes.Length) {
        $match = $true
        if ($i + $search.Length -le $bytes.Length) {
            for ($j = 0; $j -lt $search.Length; $j++) {
                if ($bytes[$i + $j] -ne $search[$j]) { $match = $false; break }
            }
        } else {
            $match = $false
        }
        if ($match) {
            $result.AddRange($replace)
            $i += $search.Length
        } else {
            $result.Add($bytes[$i])
            $i++
        }
    }
    return $result.ToArray()
}

# Fix double-encoded UTF-8 sequences
# é = 0xC3 0xA9 (195 169) - was 195 131 194 169 (0xC3 0x83 0xC2 0xA9)
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
$bytes = ReplaceBytes $bytes @(195,131,194,179) @(195,179)  # ô (alternative)
$bytes = ReplaceBytes $bytes @(195,131,194,182) @(195,182)  # ö
$bytes = ReplaceBytes $bytes @(195,131,194,188) @(195,188)  # ü

# Also fix the Webhook notes: "posé" was "posé" -> 195 131 194 169
# And the InitOrchestration SAFE_INGRESS_MESSAGES has similar issues

# Write back
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Byte-level UTF-8 fix applied"