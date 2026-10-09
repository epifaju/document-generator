$payload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = 'http://127.0.0.1:5681/webhook/document-generation'
$result = $wc.UploadString($url, $payload)
# Save result to file
$file = "C:\temp\wh_result.txt"
Write-Output $result | Out-File -Encoding UTF8 $file
# Also output length
Write-Output "Result length: $($result.Length)"
Write-Output "First 200 chars: $($result.Substring(0,200))"