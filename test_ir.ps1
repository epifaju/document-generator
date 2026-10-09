# Test with Invoke-WebRequest
$url = "http://127.0.0.1:5681/webhook/document-generation"
$payload = @{message='Génère une attestation de concordance pour Maria Gomes, née le 12/05/1985 à Bissau, forme erronée Maria Gomez, forme correcte Maria Gomes.'} | ConvertTo-Json
try {
    $wr = Invoke-WebRequest -Uri $url -Method Post -Body $payload -ContentType "application/json" -ErrorAction Stop
    Write-Output "StatusCode: $($wr.StatusCode)"
    Write-Output "StatusDescription: $($wr.StatusDescription)"
    Write-Output "Content length: $($wr.Content.Length)"
    Write-Output "Content: $($wr.Content)"
} catch {
    Write-Output "ERROR: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        Write-Output "HTTP Status: $($_.Exception.Response.StatusCode)"
        Write-Output "Error body: $($_.Exception.Response.StatusDescription)"
        # Try to read the error body
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader $stream, [System.Text.Encoding]::UTF8
        Write-Output "Error body text: $($reader.ReadToEnd())"
    }
}