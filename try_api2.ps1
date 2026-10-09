$wc = New-Object System.Net.WebClient
Write-Output 'Trying /api/workflows export...'
try {
    $r = $wc.DownloadString('http://127.0.0.1:5681/api/workflows')
    Write-Output 'SUCCESS: Length=' + $r.Length
    Write-Output 'First 200: ' + $r.Substring(0,200)
} catch {
    Write-Output 'ERROR: ' + $_.Exception.Message.Substring(0,60)
}
Write-Output ''
Write-Output 'Trying /workflows export...'
try {
    $r = $wc.DownloadString('http://127.0.0.1:5681/workflows')
    Write-Output 'SUCCESS: Length=' + $r.Length
    Write-Output 'First 200: ' + $r.Substring(0,200)
} catch {
    Write-Output 'ERROR: ' + $_.Exception.Message.Substring(0,60)
}
Write-Output ''
Write-Output 'Trying /v1/workflows...'
try {
    $r = $wc.DownloadString('http://127.0.0.1:5681/v1/workflows')
    Write-Output 'SUCCESS: Length=' + $r.Length
    Write-Output 'First 200: ' + $r.Substring(0,200)
} catch {
    Write-Output 'ERROR: ' + $_.Exception.Message.Substring(0,60)
}