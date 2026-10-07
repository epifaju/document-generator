$content = Get-Content "n8n/workflows/document-generation-v1.json" -Raw

# Count require('crypto') / require("crypto")
$count1 = [regex]::Matches($content, 'require\(["'"'"']crypto["'"'"']\)').Count
Write-Host "require('crypto' or require(""crypto""): $count1"

# Count crypto.randomUUID()
$count2 = [regex]::Matches($content, 'crypto\.randomUUID\(\)').Count
Write-Host "crypto.randomUUID(): $count2"

# Count generateUUID (as function name or call)
$count3 = [regex]::Matches($content, '\bgenerateUUID\b').Count
Write-Host "generateUUID (word boundary): $count3"

# Also search for 'generateUUID(' specifically
$count4 = [regex]::Matches($content, 'generateUUID\(').Count
Write-Host "generateUUID\( : $count4"