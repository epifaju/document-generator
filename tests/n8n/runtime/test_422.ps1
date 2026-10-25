# Usage : envoie un message au webhook document-generation et affiche le statut HTTP en cas d'erreur. Base n8n : $env:N8N_BASE_URL (défaut http://127.0.0.1:5678).
# G6 : exécution soumise à accord explicite.
$n8nBase = if ($env:N8N_BASE_URL) { $env:N8N_BASE_URL } else { 'http://127.0.0.1:5678' }
$n8nBase = $n8nBase.TrimEnd('/')
# Test the actual webhook with full response
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = "$n8nBase/webhook/document-generation"
$payload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
try {
    $result = $wc.UploadString($url, $payload)
    Write-Output "Body: $result"
    Write-Output "Length: $($result.Length)"
} catch {
    $err = $_.Exception
    Write-Output "Error: $err"
    # Try to get HTTP status
    if ($err.Response) {
        Write-Output "HTTP Status: $($err.Response.StatusCode)"
        Write-Output "Error body: $($err.Response.StatusDescription)"
        try {
            $errBody = $err.Response.GetResponseStream() | New-Object System.IO.StreamReader -Encoding UTF8
            Write-Output "Error body read: $($errBody.ReadToEnd())"
        } catch {}
    }
}