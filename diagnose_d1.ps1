# D1 — Downstream blocker diagnosis
$file = "C:\Users\epifa\opencode_workspace\dev\document-generator\eventlog1.txt"
$content = Get-Content $file -Encoding UTF8
$execEvents = $content | Where-Object { $_ -match "executionId" }

Write-Output "Total execution events: $execEvents.Count"

# Count events by lastNodeExecuted
Write-Output ""
Write-Output "=== Events grouped by lastNodeExecuted ==="

# Extract lastNode values
$lastNodeCounts = @{}
foreach $line in $execEvents {
    try {
        $json = $line | ConvertFrom-Json
        $payload = $json.payload
        $execId = $payload.executionId
        
        $lastNode = ""
        foreach $prop in $payload.PSObject.Properties {
            if ($prop.Name -eq "lastNodeExecuted") {
                $lastNode = $prop.Value
                break
            }
        }
        
        if ($lastNode -ne "") {
            if (-not $lastNodeCounts.ContainsKey($lastNode)) {
                $lastNodeCounts[$lastNode] = 0
            }
            $lastNodeCounts[$lastNode]++
        }
    } catch { }
}

# Show counts
foreach ($kv in $lastNodeCounts.GetEnumerator() | Sort-Object -Property value -Descending) {
    Write-Output "lastNode=$($kv.Key): $($kv.Value) executions"
}

# Also check for success/failure counts
Write-Output ""
Write-Output "=== Workflow success/failure ==="
$successCounts = @{}
foreach $line in $execEvents {
    try {
        $json = $line | ConvertFrom-Json
        $payload = $json.payload
        $execId = $payload.executionId
        
        $success = ""
        foreach $prop in $payload.PSObject.Properties {
            if ($prop.Name -eq "success") {
                $success = $prop.Value
                break
            }
        }
        
        if ($success -ne "") {
            if (-not $successCounts.ContainsKey($success)) {
                $successCounts[$success] = 0
            }
            $successCounts[$success]++
        }
    } catch { }
}

foreach ($kv in $successCounts.GetEnumerator() | Sort-Object -Property value -Descending) {
    Write-Output "success=$($kv.Key): $($kv.Value) executions"
}