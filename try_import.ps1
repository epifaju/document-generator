# Try n8n API workflow import
$wc = New-Object System.Net.WebClient
$json = Get-Content 'n8n/workflows/document-generation-v1.json' -Raw
$urls = @(
    'http://127.0.0.1:5681/api/v1/workflows',
    'http://127.0.0.1:5681/rest/workflows'
)
foreach ($u in $urls) {
    Write-Output "Trying: $u"
    try {
        $r = $wc.DownloadString($u)
        Write-Output "  DOWNLOAD OK: $($r.Length) chars"
    } catch {
        Write-Output "  DOWNLOAD ERR: $($_.Exception.Message.Substring(0, 50))"
    }
    try {
        $r2 = $wc.UploadString($u, $json)
        Write-Output "  UPLOAD OK: $($r2.Length) chars"
    } catch {
        Write-Output "  UPLOAD ERR: $($_.Exception.Message.Substring(0, 50))"
    }
}