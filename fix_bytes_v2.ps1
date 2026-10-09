$raw = Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($raw)

# The mojibake pattern in the raw JSON:
# "ǟ" = U+01DF = 0xC7 0x9F in UTF-8
# But the actual corruption is double-encoded UTF-8 where:
# é (U+00E9) = 0xC3 0xA9 got double-encoded as:
#   0xC3 -> 0xC3 0x83 (Ãƒ)
#   0xA9 -> 0xC2 0xA9 (©)
# So in the raw JSON we see the bytes: C3 83 C2 A9 which renders as "Ãƒ©"
# But in the JSON string it's escaped as \u00C3\u0083\u00C2\u00A9 or similar

# The raw JSON has these byte sequences that need fixing:
# C3 83 C2 A9 (195 131 194 169) -> C3 A9 (é)
# C3 83 C2 A8 (195 131 194 168) -> C3 A8 (è)
# C3 83 C2 A0 (195 131 194 160) -> C3 A0 (à)
# C3 83 C2 AA (195 131 194 170) -> C3 AA (ê)
# C3 83 C2 AE (195 131 194 174) -> C3 AE (î)
# C3 83 C2 B4 (195 131 194 180) -> C3 B4 (ô)
# C3 83 C2 A7 (195 131 194 167) -> C3 A7 (ç)
# C3 83 C2 B9 (195 131 194 185) -> C3 B9 (ù)
$bytes = [System.Text.Encoding]::UTF8.GetBytes((Get-Content -Raw -LiteralPath 'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'))

function ReplaceBytes($bytes, $search, $replace) {
    $result = New-Object byte[] ($bytes.Length)
    $ri = 0
    $i = 0
    while ($i -lt $bytes.Length) {
        $match = $true
        if ($i + $search.Length -le $bytes.Length) {
            for ($j = 0; $j -lt $search.Length; $j++) {
                if ($bytes[$i + $j] -ne $search[$j]) { $match = $false; break }
            }
        } else { $match = $false }
        if ($match) {
            for ($k = 0; $k -lt $replace.Length; $k++) {
                $bytes[$ri++] = $replace[$k]
            }
            $i += $search.Length
        } else {
            $bytes[$ri++] = $bytes[$i++]
        }
    }
    # Trim if needed
    $result = New-Object byte[] $ri
    [Array]::Copy($bytes, 0, $result, 0, $ri)
    return $result
}

# Fix double-encoded UTF-8 sequences
# é = 0xC3 0xA9 was double-encoded as 0xC3 0x83 0xC2 0xA9
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
$bytes = ReplaceBytes $bytes @(195,131,194,174) @(195,174)  # î (alt)
$bytes = ReplaceBytes $bytes @(195,131,194,179) @(195,179)  # ô (alt)

# Also fix the Webhook notes: "posé" was corrupted
# "posé" = 70 111 115 195 169 -> was corrupted to 112 111 115 195 131 194 169
$bytes = ReplaceBytes $bytes @(112,111,115,195,131,194,169) @(112,111,115,195,169)  # posé

# Write back
[IO.File]::WriteAllBytes('C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json', $bytes)
Write-Output "Byte-level fix v2 applied"