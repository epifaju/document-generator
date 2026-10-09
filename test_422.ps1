# Test the actual webhook with full response
$wc = New-Object System.Net.WebClient
$wc.Headers.Add('Content-Type','application/json')
$url = 'http://127.0.0.1:5681/webhook/document-generation'
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