$payload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
$params = @{
    Uri = 'http://127.0.0.1:5678/webhook/document-generation'
    Method = 'Post'
    Body = $payload
    ContentType = 'application/json'
}
$response = Invoke-WebRequest @params
$response.Content