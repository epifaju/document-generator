$wc = New-Object System.Net.WebClient
$paths = @("/", "/workflow", "/workflows", "/node", "/nodes", "/execute", "/run", "/api/v1", "/v1/", "/internal", "/internal/")
foreach ($p in $paths) {
    $url = "http://127.0.0.1:5681" + $p
    try {
        $r = $wc.DownloadString($url)
        Write-Output ("OK: " + $p + " length=" + $r.Length)
    } catch {
        $msg = $_.Exception.Message
        Write-Output ("ERROR: " + $p + " " + $msg)
    }
}