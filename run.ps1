$p = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$r = $wc.UploadString('http://127.0.0.1:5681/webhook/document-generation', $p)
Write-Output "LENGTH: $($r.Length)"
Write-Output "RAW: $($r)"