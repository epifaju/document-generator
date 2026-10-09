# Test Invoke-WebRequest on both ports
$urls = @("http://127.0.0.1:5678/webhook/document-generation", "http://127.0.0.1:5681/webhook/document-generation")

foreach ($url in $urls) {
    Write-Output "Testing: $url"
    try {
        $wr = Invoke-WebRequest -Uri $url -Method Post -Body @{message="test"} -ContentType "application/json" -ErrorAction Stop
        Write-Output "  Status: $($wr.StatusCode)"
        Write-Output "  Body length: $($wr.Content.Length)"
        Write-Output "  Body: $($wr.Content.Substring(0,100))"
    } catch {
        Write-Output "  ERROR: $($_.Exception.Message.Substring(0,60))"
    }
    Write-Output ""
}