<#
.SYNOPSIS
  Static validator for the synthetic DG-I1-FIXTURES-001 conversation fixtures.

.DESCRIPTION
  STATIC ONLY. This script reads JSON fixtures under tests/n8n/fixtures and
  validates their shape, naming, UTF-8 encoding, case coverage and internal
  consistency. It never starts a service, never uses the network and never
  executes an n8n workflow.

  The fixture expectations are test intent, not runtime proof. In particular,
  this validator does NOT prove gates I-B, I-C or I-D.

  With -SelfTest, the script also mutates loaded fixtures in memory and asserts
  that the validator rejects them. No temporary file is written.

  Exit codes:
    0 = every check passed (and self-tests passed when requested)
    1 = at least one check or self-test failed
    2 = the fixture directory is missing or unreadable
#>
[CmdletBinding()]
param(
    [string]$FixtureDir,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($FixtureDir)) {
    $FixtureDir = Join-Path $PSScriptRoot 'fixtures'
}

$script:Failures = 0
$script:Passed = 0
$script:SelfTestPassed = 0
$script:SelfTestFailed = 0

$script:Stages = @('OLLAMA', 'E8', 'E1', 'E2', 'E3', 'E4', 'E5', 'E6')
$script:Outcomes = @(
    'GENERATED', 'MISSING_INFORMATION', 'REJECTED', 'GENERATION_FAILED',
    'REQUEST_NOT_FOUND', 'UNSUPPORTED_DOCUMENT_TYPE', 'CLARIFICATION_LIMIT_REACHED',
    'INVALID_REQUEST', 'ERROR'
)
$script:ErrorCodes = @(
    'VALIDATION_ERROR', 'AI_EXTRACTION_ERROR', 'EXTRACTION_SCHEMA_INVALID',
    'BACKEND_UNAVAILABLE', 'INTERNAL_ERROR', 'REQUEST_NOT_FOUND'
)

$script:CaseOrder = @(
    'I1C-01', 'I1C-02', 'I1C-03', 'I1C-04', 'I1C-05',
    'I1D-05', 'I1D-06', 'I1D-07', 'I1D-08', 'I1D-09', 'I1D-10'
)
$script:CaseSpec = @{
    'I1C-01' = [pscustomobject]@{ File = 'i1c_01_initial_valid.json'; Gate = 'I-C'; CaseNumber = 1; CaseKey = 'initial_valid' }
    'I1C-02' = [pscustomobject]@{ File = 'i1c_02_second_turn_complete.json'; Gate = 'I-C'; CaseNumber = 2; CaseKey = 'second_turn_complete' }
    'I1C-03' = [pscustomobject]@{ File = 'i1c_03_missing_information.json'; Gate = 'I-C'; CaseNumber = 3; CaseKey = 'missing_information' }
    'I1C-04' = [pscustomobject]@{ File = 'i1c_04_invalid_request_reference.json'; Gate = 'I-C'; CaseNumber = 4; CaseKey = 'invalid_request_reference' }
    'I1C-05' = [pscustomobject]@{ File = 'i1c_05_round2_incomplete.json'; Gate = 'I-C'; CaseNumber = 11; CaseKey = 'round2_incomplete' }
    'I1D-05' = [pscustomobject]@{ File = 'i1d_05_duplicate_message.json'; Gate = 'I-D'; CaseNumber = 5; CaseKey = 'duplicate_message' }
    'I1D-06' = [pscustomobject]@{ File = 'i1d_06_backend_error.json'; Gate = 'I-D'; CaseNumber = 6; CaseKey = 'backend_error' }
    'I1D-07' = [pscustomobject]@{ File = 'i1d_07_ollama_unavailable.json'; Gate = 'I-D'; CaseNumber = 7; CaseKey = 'ollama_unavailable' }
    'I1D-08' = [pscustomobject]@{ File = 'i1d_08_timeout.json'; Gate = 'I-D'; CaseNumber = 8; CaseKey = 'timeout' }
    'I1D-09' = [pscustomobject]@{ File = 'i1d_09_fictitious_pii.json'; Gate = 'I-D'; CaseNumber = 9; CaseKey = 'fictitious_pii' }
    'I1D-10' = [pscustomobject]@{ File = 'i1d_10_binary_content.json'; Gate = 'I-D'; CaseNumber = 10; CaseKey = 'binary_content' }
}

# FIX-01: approved business contract per fixture. These are the expected
# results an execution would have to satisfy; the ordered Path is the exact
# sequence of simulated stub stages, not merely per-stage counts.
$script:Contracts = @{
    'I1C-01' = [pscustomobject]@{ Outcome = 'GENERATED'; HttpStatus = 200; ErrorCode = $null; Path = @('OLLAMA', 'E8', 'E1', 'E4', 'E5', 'E6') }
    'I1C-02' = [pscustomobject]@{ Outcome = 'GENERATED'; HttpStatus = 200; ErrorCode = $null; Path = @('E2', 'OLLAMA', 'E8', 'E3', 'E4', 'E5', 'E6') }
    'I1C-03' = [pscustomobject]@{ Outcome = 'MISSING_INFORMATION'; HttpStatus = 200; ErrorCode = $null; Path = @('OLLAMA', 'E8', 'E1') }
    'I1C-04' = [pscustomobject]@{ Outcome = 'INVALID_REQUEST'; HttpStatus = 400; ErrorCode = 'VALIDATION_ERROR'; Path = @() }
    'I1C-05' = [pscustomobject]@{ Outcome = 'MISSING_INFORMATION'; HttpStatus = 200; ErrorCode = $null; Path = @('OLLAMA', 'E8', 'E1', 'E2', 'OLLAMA', 'E8', 'E3') }
    'I1D-05' = [pscustomobject]@{ Outcome = 'GENERATED'; HttpStatus = 200; ErrorCode = $null; Path = @('E2', 'E6', 'E2', 'E6') }
    'I1D-06' = [pscustomobject]@{ Outcome = 'ERROR'; HttpStatus = 500; ErrorCode = 'INTERNAL_ERROR'; Path = @('OLLAMA', 'E8', 'E1', 'E4', 'E4', 'E4') }
    'I1D-07' = [pscustomobject]@{ Outcome = 'ERROR'; HttpStatus = 502; ErrorCode = 'AI_EXTRACTION_ERROR'; Path = @('OLLAMA', 'OLLAMA', 'OLLAMA') }
    'I1D-08' = [pscustomobject]@{ Outcome = 'ERROR'; HttpStatus = 502; ErrorCode = 'AI_EXTRACTION_ERROR'; Path = @('OLLAMA', 'OLLAMA', 'OLLAMA') }
    'I1D-09' = [pscustomobject]@{ Outcome = 'GENERATED'; HttpStatus = 200; ErrorCode = $null; Path = @('OLLAMA', 'E8', 'E1', 'E4', 'E5', 'E6') }
    'I1D-10' = [pscustomobject]@{ Outcome = 'GENERATED'; HttpStatus = 200; ErrorCode = $null; Path = @('OLLAMA', 'E8', 'E1', 'E4', 'E5', 'E6') }
}

function Get-Prop {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    Write-Output -NoEnumerate $prop.Value
}

function Has-Prop {
    param($Object, [string]$Name)
    return ($null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name])
}

function Test-Integer {
    param($Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [int] -or $Value -is [long] -or $Value -is [int16] -or $Value -is [byte]) { return $true }
    if ($Value -is [double] -or $Value -is [decimal]) { return ([double]$Value -eq [math]::Truncate([double]$Value)) }
    return $false
}

function Test-Number {
    param($Value)
    return ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal])
}

function Test-Boolean {
    param($Value)
    return ($Value -is [bool])
}

function Test-UuidV4 {
    param([string]$Value)
    if ($null -eq $Value) { return $false }
    return ($Value -match '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
}

function Test-Utf8NoBom {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes) {
        return [pscustomobject]@{ Ok = $false; Reason = 'no bytes' }
    }
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        return [pscustomobject]@{ Ok = $false; Reason = 'UTF-8 BOM present' }
    }
    try {
        $strict = [System.Text.UTF8Encoding]::new($false, $true)
        $null = $strict.GetString($Bytes)
        return [pscustomobject]@{ Ok = $true; Reason = '' }
    }
    catch {
        return [pscustomobject]@{ Ok = $false; Reason = 'invalid UTF-8' }
    }
}

function Add-Error {
    param($Errors, [string]$Message)
    [void]$Errors.Add($Message)
}

function Get-CallCount {
    param($Calls, [string]$Stage)
    $value = Get-Prop $Calls $Stage
    if (Test-Integer $value) { return [int]$value }
    return -1
}

function Copy-Fixture {
    param($Fixture)
    return ($Fixture | ConvertTo-Json -Depth 20 -Compress | ConvertFrom-Json)
}

function Test-SequenceEqual {
    param($Left, $Right)
    $leftValues = @($Left)
    $rightValues = @($Right)
    if ($leftValues.Count -ne $rightValues.Count) { return $false }
    for ($i = 0; $i -lt $leftValues.Count; $i++) {
        if ([string]$leftValues[$i] -cne [string]$rightValues[$i]) { return $false }
    }
    return $true
}

function Add-ValuesByName {
    param($Object, [string]$Name, $Results)
    if ($null -eq $Object) { return }
    if ($Object -is [System.Collections.IEnumerable] -and -not ($Object -is [string])) {
        foreach ($item in $Object) { Add-ValuesByName $item $Name $Results }
        return
    }
    if ($Object -is [pscustomobject]) {
        foreach ($property in $Object.PSObject.Properties) {
            if ($property.Name -eq $Name) {
                [void]$Results.Add($property.Value)
            }
            else {
                Add-ValuesByName $property.Value $Name $Results
            }
        }
    }
}

function Get-ValuesByName {
    param($Object, [string]$Name)
    $results = New-Object System.Collections.Generic.List[object]
    Add-ValuesByName $Object $Name $results
    return @($results.ToArray())
}

function Get-StageIndices {
    param($Stubs, [string]$Stage)
    $indices = New-Object System.Collections.Generic.List[int]
    $items = @($Stubs)
    for ($i = 0; $i -lt $items.Count; $i++) {
        if ((Get-Prop $items[$i] 'stage') -eq $Stage) { [void]$indices.Add($i) }
    }
    return @($indices.ToArray())
}

function Get-AbsentMandatoryKeys {
    param($Data)
    $keys = @('prenom', 'nom', 'dateNaissance', 'lieuNaissance', 'nomIncorrect', 'nomCorrect')
    $absent = New-Object System.Collections.Generic.List[string]
    foreach ($key in $keys) {
        if (-not (Has-Prop $Data $key)) { [void]$absent.Add($key) }
    }
    return @($absent.ToArray())
}

# Round >= 2 is an input event that carries a requestId. Its extraction is the
# delta form: a successful OLLAMA stub placed after the first E2 (E2, then
# extraction). The rule is structural and does not name a fixture.
function Test-IsDeltaOllamaStub {
    param($Stubs, $Events, [int]$StubIndex)
    $hasLaterRound = $false
    foreach ($event in @($Events)) {
        $eventBody = Get-Prop $event 'body'
        $requestId = Get-Prop $eventBody 'requestId'
        if ($null -ne $requestId -and ([string]$requestId).Length -gt 0) { $hasLaterRound = $true }
    }
    if (-not $hasLaterRound) { return $false }
    $e2Indices = @(Get-StageIndices $Stubs 'E2')
    if ($e2Indices.Count -eq 0) { return $false }
    return ($StubIndex -gt [int]$e2Indices[0])
}

function Add-BirthPhraseErrors {
    param($Errors, $Events, $Stubs)
    $success = New-Object System.Collections.Generic.List[object]
    foreach ($stub in @($Stubs)) {
        if ((Get-Prop $stub 'stage') -eq 'OLLAMA' -and (Get-Prop $stub 'mode') -eq 'success') {
            [void]$success.Add($stub)
        }
    }
    $eventItems = @($Events)
    $pairCount = $eventItems.Count
    if ($success.Count -lt $pairCount) { $pairCount = $success.Count }
    if ($pairCount -le 0) { return }
    $datePattern = [regex]::new('(?<![A-Za-z])[Nn](?:\u00E9e|\u00E9)\s+le\s+(?<day>\d{2})[/-](?<month>\d{2})[/-](?<year>\d{4})')
    $placePattern = [regex]::new('(?<![A-Za-z])[Nn](?:\u00E9e|\u00E9)\s+le\s+\d{2}[/-]\d{2}[/-]\d{4}\s+\u00E0\s+(?<place>\p{Lu}[\p{L}\u0027\u2019\-]*)')
    for ($k = 0; $k -lt $pairCount; $k++) {
        $event = $eventItems[$eventItems.Count - $pairCount + $k]
        $ollama = $success[$success.Count - $pairCount + $k]
        $message = [string](Get-Prop (Get-Prop $event 'body') 'message')
        $data = Get-Prop (Get-Prop $ollama 'body') 'data'
        foreach ($match in $datePattern.Matches($message)) {
            $iso = '{0:0000}-{1:00}-{2:00}' -f [int]$match.Groups['year'].Value, [int]$match.Groups['month'].Value, [int]$match.Groups['day'].Value
            if ([string](Get-Prop $data 'dateNaissance') -cne $iso) {
                Add-Error $Errors 'message birth date is absent from the paired extraction'
            }
        }
        foreach ($match in $placePattern.Matches($message)) {
            if ([string](Get-Prop $data 'lieuNaissance') -cne [string]$match.Groups['place'].Value) {
                Add-Error $Errors 'message birth place is absent from the paired extraction'
            }
        }
    }
}

function Get-AnalyzedPayload {
    param($Fixture)
    return [pscustomobject]@{
        input = Get-Prop $Fixture 'input'
        stubs = Get-Prop $Fixture 'stubs'
    }
}

function ConvertTo-AnalyzedText {
    param($Fixture)
    return ((Get-AnalyzedPayload $Fixture) | ConvertTo-Json -Depth 30 -Compress)
}

function Test-Uniqueness {
    param($Fixtures)
    $errors = New-Object System.Collections.Generic.List[string]
    $ids = @{}
    $numbers = @{}
    foreach ($fixture in @($Fixtures)) {
        if ($null -eq $fixture) { continue }
        $id = [string](Get-Prop $fixture 'fixtureId')
        $number = Get-Prop $fixture 'caseNumber'
        if ($id -ne '' -and $ids.ContainsKey($id)) {
            Add-Error $errors "duplicate fixtureId: $id"
        }
        elseif ($id -ne '') {
            $ids[$id] = $true
        }
        if (Test-Integer $number) {
            $key = [string][int]$number
            if ($numbers.ContainsKey($key)) {
                Add-Error $errors "duplicate caseNumber: $key"
            }
            else {
                $numbers[$key] = $true
            }
        }
    }
    return @($errors.ToArray())
}

function Test-Fixture {
    param($Fixture, [string]$FileName)

    $errors = New-Object System.Collections.Generic.List[string]
    if ($null -eq $Fixture) {
        Add-Error $errors 'fixture is null'
        return @($errors.ToArray())
    }

    $topLevel = @(
        'schemaVersion', 'fixtureId', 'gate', 'caseNumber', 'caseKey', 'title',
        'description', 'synthetic', 'input', 'stubs', 'expected', 'securityProbe'
    )
    foreach ($key in $topLevel) {
        if (-not (Has-Prop $Fixture $key)) { Add-Error $errors "missing top-level key: $key" }
    }

    $schemaVersion = Get-Prop $Fixture 'schemaVersion'
    if ($schemaVersion -ne '1.0') { Add-Error $errors 'schemaVersion must be 1.0' }

    $fixtureId = [string](Get-Prop $Fixture 'fixtureId')
    $spec = $null
    if (-not $script:CaseSpec.ContainsKey($fixtureId)) {
        Add-Error $errors "unknown fixtureId: $fixtureId"
    }
    else {
        $spec = $script:CaseSpec[$fixtureId]
    }

    $gate = Get-Prop $Fixture 'gate'
    $caseNumber = Get-Prop $Fixture 'caseNumber'
    $caseKey = Get-Prop $Fixture 'caseKey'
    $title = Get-Prop $Fixture 'title'
    $description = Get-Prop $Fixture 'description'
    $synthetic = Get-Prop $Fixture 'synthetic'

    if ($null -ne $spec) {
        if ($gate -ne $spec.Gate) { Add-Error $errors "gate must be $($spec.Gate)" }
        if (-not (Test-Integer $caseNumber) -or [int]$caseNumber -ne $spec.CaseNumber) { Add-Error $errors "caseNumber must be $($spec.CaseNumber)" }
        if ($caseKey -ne $spec.CaseKey) { Add-Error $errors "caseKey must be $($spec.CaseKey)" }
        if ($FileName -ne $spec.File) { Add-Error $errors "file name must be $($spec.File)" }
    }
    if (-not ($title -is [string]) -or $title.Trim().Length -eq 0) { Add-Error $errors 'title must be a non-empty string' }
    if (-not ($description -is [string]) -or $description.Trim().Length -eq 0) { Add-Error $errors 'description must be a non-empty string' }
    if ($synthetic -ne $true) { Add-Error $errors 'synthetic must be true' }

    $input = Get-Prop $Fixture 'input'
    if ($null -eq $input -or -not ($input -is [pscustomobject])) {
        Add-Error $errors 'input must be an object'
    }
    else {
        if ((Get-Prop $input 'method') -ne 'POST') { Add-Error $errors 'input.method must be POST' }
        if ((Get-Prop $input 'path') -ne '/webhook/document-generation') { Add-Error $errors 'input.path is not the approved webhook path' }
        if (-not (Test-UuidV4 ([string](Get-Prop $input 'correlationId')))) { Add-Error $errors 'input.correlationId must be a UUID v4' }
    }

    $eventsValue = Get-Prop $input 'events'
    $events = @()
    if ($null -ne $eventsValue) { $events = @($eventsValue) }
    if ($events.Count -eq 0) { Add-Error $errors 'input.events must contain at least one event' }
    for ($i = 0; $i -lt $events.Count; $i++) {
        $event = $events[$i]
        if ($null -eq $event -or -not ($event -is [pscustomobject])) {
            Add-Error $errors "input.events[$i] must be an object"
            continue
        }
        $sequence = Get-Prop $event 'sequence'
        if (-not (Test-Integer $sequence) -or [int]$sequence -ne ($i + 1)) { Add-Error $errors "input.events[$i].sequence must be $($i + 1)" }
        $eventKeys = @($event.PSObject.Properties.Name)
        foreach ($key in $eventKeys) {
            if ($key -notin @('sequence', 'body')) { Add-Error $errors "input.events[$i] has unknown key: $key" }
        }
        $body = Get-Prop $event 'body'
        if ($null -eq $body -or -not ($body -is [pscustomobject])) {
            Add-Error $errors "input.events[$i].body must be an object"
            continue
        }
        foreach ($key in @($body.PSObject.Properties.Name)) {
            if ($key -notin @('message', 'requestId')) { Add-Error $errors "input.events[$i].body has unknown key: $key" }
        }
        $message = Get-Prop $body 'message'
        if (-not ($message -is [string]) -or $message.Trim().Length -lt 1 -or $message.Length -gt 4000) {
            Add-Error $errors "input.events[$i].body.message must be 1..4000 characters"
        }
        $requestId = Get-Prop $body 'requestId'
        if ($null -ne $requestId -and -not ($requestId -is [string])) {
            Add-Error $errors "input.events[$i].body.requestId must be null or a string"
        }
        if ($fixtureId -ne 'I1C-04' -and $null -ne $requestId -and -not (Test-UuidV4 $requestId)) {
            Add-Error $errors "input.events[$i].body.requestId must be a UUID v4"
        }
    }

    $stubsValue = Get-Prop $Fixture 'stubs'
    $stubs = @()
    if ($null -ne $stubsValue) { $stubs = @($stubsValue) }
    $stubCounts = @{}
    foreach ($stage in $script:Stages) { $stubCounts[$stage] = 0 }

    foreach ($stub in $stubs) {
        if ($null -eq $stub -or -not ($stub -is [pscustomobject])) {
            Add-Error $errors 'each stub must be an object'
            continue
        }
        $stage = Get-Prop $stub 'stage'
        $mode = Get-Prop $stub 'mode'
        $httpStatus = Get-Prop $stub 'httpStatus'
        $delayMs = Get-Prop $stub 'delayMs'
        $body = Get-Prop $stub 'body'

        if ($stage -notin $script:Stages) { Add-Error $errors "stub has unknown stage: $stage" }
        else { $stubCounts[$stage] += 1 }

        if ($mode -notin @('success', 'error', 'connection_refused', 'timeout')) {
            Add-Error $errors "stub stage $stage has unknown mode: $mode"
            continue
        }
        if (-not (Test-Integer $delayMs) -or [int]$delayMs -lt 0) {
            Add-Error $errors "stub stage $stage has invalid delayMs"
        }

        switch ($mode) {
            'success' {
                if (-not (Test-Integer $httpStatus) -or [int]$httpStatus -lt 200 -or [int]$httpStatus -gt 299) {
                    Add-Error $errors "success stub $stage must use a 2xx httpStatus"
                }
                if ($null -eq $body -or -not ($body -is [pscustomobject])) {
                    Add-Error $errors "success stub $stage must carry a body object"
                }
            }
            'error' {
                if (-not (Test-Integer $httpStatus) -or [int]$httpStatus -lt 400 -or [int]$httpStatus -gt 599) {
                    Add-Error $errors "error stub $stage must use a 4xx/5xx httpStatus"
                }
                if ($null -eq $body -or -not ($body -is [pscustomobject])) {
                    Add-Error $errors "error stub $stage must carry a body object"
                }
            }
            'timeout' {
                if ($null -ne $httpStatus) { Add-Error $errors "timeout stub $stage must not set httpStatus" }
                if (-not (Test-Integer $delayMs) -or [int]$delayMs -le 0) { Add-Error $errors "timeout stub $stage must set delayMs > 0" }
                if ($null -ne $body) { Add-Error $errors "timeout stub $stage must not carry a body" }
            }
            'connection_refused' {
                if ($null -ne $httpStatus) { Add-Error $errors "connection_refused stub $stage must not set httpStatus" }
                if ($null -ne $body) { Add-Error $errors "connection_refused stub $stage must not carry a body" }
            }
        }

        if ($mode -eq 'error' -and $stage -in @('E1', 'E2', 'E3', 'E4', 'E5', 'E6') -and (Test-Integer $httpStatus) -and ([int]$httpStatus -eq 400 -or [int]$httpStatus -eq 422)) {
            $bodyCode = $null
            if ($null -ne $body -and ($body -is [pscustomobject])) { $bodyCode = Get-Prop $body 'code' }
            if (-not ($bodyCode -is [string]) -or $bodyCode.Length -eq 0) {
                Add-Error $errors 'backend 422/400 body.code is absent'
            }
            elseif ([int]$httpStatus -eq 422 -and [string]$bodyCode -cne 'MISSING_INFORMATION') {
                Add-Error $errors 'backend 422 body.code must be MISSING_INFORMATION'
            }
            elseif ([int]$httpStatus -eq 400 -and [string]$bodyCode -cne 'VALIDATION_ERROR') {
                Add-Error $errors 'backend 400 body.code must be VALIDATION_ERROR'
            }
        }

        if ($stage -eq 'OLLAMA' -and $mode -eq 'success') {
            $documentType = Get-Prop $body 'documentType'
            $confidence = Get-Prop $body 'confidence'
            $data = Get-Prop $body 'data'
            $missingFields = Get-Prop $body 'missingFields'
            if ($documentType -ne 'ATTESTATION_CONCORDANCE') { Add-Error $errors 'OLLAMA success body must target ATTESTATION_CONCORDANCE' }
            if (-not (Test-Number $confidence) -or [double]$confidence -lt 0 -or [double]$confidence -gt 1) { Add-Error $errors 'OLLAMA confidence must be within [0,1]' }
            if ($null -eq $data -or -not ($data -is [pscustomobject])) { Add-Error $errors 'OLLAMA body.data must be an object' }
            if ($null -eq $missingFields -or -not ($missingFields -is [array])) { Add-Error $errors 'OLLAMA body.missingFields must be an array' }
        }
    }

    $expected = Get-Prop $Fixture 'expected'
    if ($null -eq $expected -or -not ($expected -is [pscustomobject])) {
        Add-Error $errors 'expected must be an object'
        return @($errors.ToArray())
    }

    $finalHttpStatus = Get-Prop $expected 'finalHttpStatus'
    $finalOutcome = Get-Prop $expected 'finalOutcome'
    $errorCode = Get-Prop $expected 'errorCode'
    $expectedRequestId = Get-Prop $expected 'requestId'
    $expectedDocumentId = Get-Prop $expected 'documentId'
    $missingFieldsValue = Get-Prop $expected 'missingFields'
    $calls = Get-Prop $expected 'calls'
    $idempotency = Get-Prop $expected 'idempotency'
    $securityScan = Get-Prop $expected 'securityScan'

    if (-not (Test-Integer $finalHttpStatus)) { Add-Error $errors 'expected.finalHttpStatus must be an integer' }
    if ($finalOutcome -notin $script:Outcomes) { Add-Error $errors "expected.finalOutcome is not approved: $finalOutcome" }
    if ($null -ne $errorCode -and $errorCode -notin $script:ErrorCodes) { Add-Error $errors "expected.errorCode is not approved: $errorCode" }
    if ($null -ne $expectedRequestId -and -not (Test-UuidV4 ([string]$expectedRequestId))) { Add-Error $errors 'expected.requestId must be null or UUID v4' }
    if ($null -ne $expectedDocumentId -and -not (Test-UuidV4 ([string]$expectedDocumentId))) { Add-Error $errors 'expected.documentId must be null or UUID v4' }

    $missingFields = @()
    if ($null -ne $missingFieldsValue) { $missingFields = @($missingFieldsValue) }
    if ($null -ne $missingFieldsValue -and -not ($missingFieldsValue -is [array])) {
        Add-Error $errors 'expected.missingFields must be an array'
    }
    foreach ($field in $missingFields) {
        if (-not ($field -is [string]) -or $field.Trim().Length -eq 0) { Add-Error $errors 'expected.missingFields entries must be non-empty strings' }
    }
    if (@($missingFields | Select-Object -Unique).Count -ne $missingFields.Count) {
        Add-Error $errors 'expected.missingFields must not contain duplicates'
    }

    # FIX-01: assert the approved business outcome, HTTP status, error code and
    # the exact ordered call path of stub stages.
    $contract = $null
    if ($script:Contracts.ContainsKey($fixtureId)) { $contract = $script:Contracts[$fixtureId] }
    if ($null -ne $contract) {
        if ($finalOutcome -cne $contract.Outcome) {
            Add-Error $errors "expected.finalOutcome must be $($contract.Outcome) for $fixtureId (got $finalOutcome)"
        }
        if (-not (Test-Integer $finalHttpStatus) -or [int]$finalHttpStatus -ne [int]$contract.HttpStatus) {
            Add-Error $errors "expected.finalHttpStatus must be $($contract.HttpStatus) for $fixtureId (got $finalHttpStatus)"
        }
        $errorCodeMatches = $false
        if ($null -eq $contract.ErrorCode) { $errorCodeMatches = ($null -eq $errorCode) }
        elseif ($null -ne $errorCode) { $errorCodeMatches = ([string]$errorCode -ceq [string]$contract.ErrorCode) }
        if (-not $errorCodeMatches) {
            $expectedErrorText = 'null'
            if ($null -ne $contract.ErrorCode) { $expectedErrorText = [string]$contract.ErrorCode }
            Add-Error $errors "expected.errorCode must be $expectedErrorText for $fixtureId (got $errorCode)"
        }
        $actualPath = @($stubs | ForEach-Object { [string](Get-Prop $_ 'stage') })
        $expectedPathText = @($contract.Path) -join ','
        $actualPathText = @($actualPath) -join ','
        if ($actualPathText -cne $expectedPathText) {
            Add-Error $errors "$fixtureId call path mismatch: expected $expectedPathText, got $actualPathText"
        }
    }

    # FIX-02: identifiers and missingFields must be consistent across input
    # events, simulated stub responses and the expected contract.
    if ($fixtureId -ne 'I1C-04') {
        for ($i = 0; $i -lt $events.Count; $i++) {
            $eventRequestId = Get-Prop $events[$i].body 'requestId'
            if ($null -ne $eventRequestId -and ([string]$eventRequestId -cne [string]$expectedRequestId)) {
                Add-Error $errors "requestId does not match expected.requestId for input.events[$i] (got $eventRequestId)"
            }
        }
    }
    for ($i = 0; $i -lt $stubs.Count; $i++) {
        $stubBody = Get-Prop $stubs[$i] 'body'
        if ($null -eq $stubBody) { continue }
        $stubRequestId = Get-Prop $stubBody 'requestId'
        if ($null -ne $stubRequestId -and ([string]$stubRequestId -cne [string]$expectedRequestId)) {
            Add-Error $errors "requestId does not match expected.requestId for stub index $i (got $stubRequestId)"
        }
    }
    foreach ($foundDocumentId in @(Get-ValuesByName $stubs 'documentId')) {
        if ([string]$foundDocumentId -cne [string]$expectedDocumentId) {
            Add-Error $errors "documentId does not match expected.documentId (got $foundDocumentId)"
        }
    }
    $ollamaSuccessSeen = 0
    for ($i = 0; $i -lt $stubs.Count; $i++) {
        $stub = $stubs[$i]
        $stage = Get-Prop $stub 'stage'
        $mode = Get-Prop $stub 'mode'
        $stubBody = Get-Prop $stub 'body'
        if ($null -eq $stubBody) { continue }
        $relevant = (($stage -eq 'OLLAMA' -and $mode -eq 'success') -or ($stage -eq 'E1') -or ($stage -eq 'E3') -or ($stage -eq 'E2' -and $fixtureId -ne 'I1C-02'))
        if (-not $relevant) { continue }
        if (-not (Has-Prop $stubBody 'missingFields')) { continue }
        $stubMissingFieldsValue = Get-Prop $stubBody 'missingFields'
        $stubMissingFields = @()
        if ($null -ne $stubMissingFieldsValue) { $stubMissingFields = @($stubMissingFieldsValue) }
        # Delta extraction (round >= 2): missingFields follows the payload-scoped
        # allOf rule. It is not compared to expected.missingFields.
        if ($stage -eq 'OLLAMA' -and $mode -eq 'success' -and (Test-IsDeltaOllamaStub $stubs $events $i)) {
            $absentKeys = @(Get-AbsentMandatoryKeys (Get-Prop $stubBody 'data'))
            if (-not (Test-SequenceEqual $stubMissingFields $absentKeys)) {
                Add-Error $errors 'delta extraction missingFields must redeclare every mandatory key absent from data'
            }
            continue
        }
        # I1C-05 keeps the round-1 state on the first extraction, E1 and E2.
        # The final expected list applies only to the delta extraction and E3.
        $isI1C05PriorRound = $false
        if ($fixtureId -eq 'I1C-05') {
            if ($stage -eq 'OLLAMA' -and $mode -eq 'success') {
                if ($ollamaSuccessSeen -eq 0) { $isI1C05PriorRound = $true }
                $ollamaSuccessSeen++
            }
            elseif ($stage -eq 'E1' -or $stage -eq 'E2') {
                $isI1C05PriorRound = $true
            }
        }
        if ($isI1C05PriorRound) {
            $priorFields = @('dateNaissance', 'lieuNaissance')
            if (-not (Test-SequenceEqual $stubMissingFields $priorFields)) {
                Add-Error $errors "I1C-05 prior-round missingFields must be [dateNaissance, lieuNaissance] for stub stage $stage"
            }
            continue
        }
        if (-not (Test-SequenceEqual $stubMissingFields $missingFields)) {
            Add-Error $errors "missingFields do not match expected.missingFields for stub stage $stage"
        }
    }

    Add-BirthPhraseErrors $errors $events $stubs

    if ($null -eq $calls -or -not ($calls -is [pscustomobject])) {
        Add-Error $errors 'expected.calls must be an object'
    }
    else {
        $totalCalls = 0
        foreach ($stage in $script:Stages) {
            $count = Get-Prop $calls $stage
            if (-not (Test-Integer $count) -or [int]$count -lt 0) {
                Add-Error $errors "expected.calls.$stage must be an integer >= 0"
            }
            else {
                $totalCalls += [int]$count
                if ($stubCounts[$stage] -ne [int]$count) {
                    Add-Error $errors "stub count for $stage is $($stubCounts[$stage]) but expected.calls.$stage is $count"
                }
            }
        }
        if ($stubs.Count -ne $totalCalls) {
            Add-Error $errors "stub count is $($stubs.Count) but expected total calls is $totalCalls"
        }
    }

    if ($finalOutcome -eq 'GENERATED') {
        if (-not (Test-UuidV4 ([string]$expectedRequestId))) { Add-Error $errors 'GENERATED requires a UUID requestId' }
        if (-not (Test-UuidV4 ([string]$expectedDocumentId))) { Add-Error $errors 'GENERATED requires a UUID documentId' }
        if ($missingFields.Count -ne 0) { Add-Error $errors 'GENERATED must not declare missingFields' }
        if ([int]$finalHttpStatus -ne 200) { Add-Error $errors 'GENERATED must map to HTTP 200' }
        if ($null -ne $errorCode) { Add-Error $errors 'GENERATED must not carry an errorCode' }
    }
    elseif ($finalOutcome -eq 'MISSING_INFORMATION') {
        if (-not (Test-UuidV4 ([string]$expectedRequestId))) { Add-Error $errors 'MISSING_INFORMATION requires a UUID requestId' }
        if ($missingFields.Count -eq 0) { Add-Error $errors 'MISSING_INFORMATION requires at least one missing field' }
        if ($null -ne $expectedDocumentId) { Add-Error $errors 'MISSING_INFORMATION must not carry a documentId' }
        if ([int]$finalHttpStatus -ne 200) { Add-Error $errors 'MISSING_INFORMATION must map to HTTP 200' }
        if ($null -ne $errorCode) { Add-Error $errors 'MISSING_INFORMATION must not carry an errorCode' }
    }
    elseif ($finalOutcome -eq 'INVALID_REQUEST') {
        if ([int]$finalHttpStatus -ne 400) { Add-Error $errors 'INVALID_REQUEST must map to HTTP 400' }
        if ($errorCode -ne 'VALIDATION_ERROR') { Add-Error $errors 'INVALID_REQUEST must carry VALIDATION_ERROR' }
        if ($null -ne $expectedRequestId) { Add-Error $errors 'INVALID_REQUEST must not carry a requestId' }
        if ($null -ne $expectedDocumentId) { Add-Error $errors 'INVALID_REQUEST must not carry a documentId' }
    }
    elseif ($finalOutcome -eq 'REQUEST_NOT_FOUND') {
        if ([int]$finalHttpStatus -ne 404) { Add-Error $errors 'REQUEST_NOT_FOUND must map to HTTP 404' }
        if ($errorCode -ne 'REQUEST_NOT_FOUND') { Add-Error $errors 'REQUEST_NOT_FOUND must carry the REQUEST_NOT_FOUND code' }
        if ($null -ne $expectedDocumentId) { Add-Error $errors 'REQUEST_NOT_FOUND must not carry a documentId' }
    }
    elseif ($finalOutcome -eq 'ERROR') {
        if ([int]$finalHttpStatus -notin @(500, 502, 503)) { Add-Error $errors 'ERROR must map to HTTP 500, 502 or 503' }
        if ($errorCode -notin @('INTERNAL_ERROR', 'AI_EXTRACTION_ERROR', 'EXTRACTION_SCHEMA_INVALID', 'BACKEND_UNAVAILABLE')) {
            Add-Error $errors "ERROR has an invalid errorCode: $errorCode"
        }
        if ($errorCode -eq 'INTERNAL_ERROR' -and [int]$finalHttpStatus -ne 500) { Add-Error $errors 'INTERNAL_ERROR must map to HTTP 500' }
        if ($errorCode -in @('AI_EXTRACTION_ERROR', 'EXTRACTION_SCHEMA_INVALID') -and [int]$finalHttpStatus -ne 502) { Add-Error $errors 'AI extraction errors must map to HTTP 502' }
        if ($errorCode -eq 'BACKEND_UNAVAILABLE' -and [int]$finalHttpStatus -ne 503) { Add-Error $errors 'BACKEND_UNAVAILABLE must map to HTTP 503' }
        if ($null -ne $expectedDocumentId) { Add-Error $errors 'ERROR must not carry a documentId' }
    }
    else {
        if ([int]$finalHttpStatus -ne 200) { Add-Error $errors "$finalOutcome must map to HTTP 200" }
        if ($null -ne $errorCode) { Add-Error $errors "$finalOutcome must not carry an errorCode" }
    }

    if ($null -eq $securityScan -or -not ($securityScan -is [pscustomobject])) {
        Add-Error $errors 'expected.securityScan must be an object'
    }
    else {
        $piiInLogs = Get-Prop $securityScan 'piiInLogs'
        $binaryInExecutionData = Get-Prop $securityScan 'binaryInExecutionData'
        if (-not (Test-Boolean $piiInLogs)) { Add-Error $errors 'expected.securityScan.piiInLogs must be boolean' }
        if (-not (Test-Boolean $binaryInExecutionData)) { Add-Error $errors 'expected.securityScan.binaryInExecutionData must be boolean' }
        if ($piiInLogs -ne $false) { Add-Error $errors 'expected.securityScan.piiInLogs must be false' }
        if ($binaryInExecutionData -ne $false) { Add-Error $errors 'expected.securityScan.binaryInExecutionData must be false' }
    }

    $securityProbe = Get-Prop $Fixture 'securityProbe'
    if ($fixtureId -in @('I1C-01', 'I1C-02', 'I1C-03', 'I1C-04', 'I1C-05', 'I1D-05', 'I1D-06', 'I1D-07', 'I1D-08')) {
        if ($null -ne $securityProbe) { Add-Error $errors 'securityProbe must be null for this case' }
        if ($null -ne $idempotency -and $fixtureId -ne 'I1D-05') { Add-Error $errors 'idempotency must be null for this case' }
    }

    switch ($fixtureId) {
        'I1C-01' {
            if ($events.Count -ne 1) { Add-Error $errors 'I1C-01 must have exactly one event' }
            if ($events.Count -ge 1 -and $null -ne (Get-Prop $events[0].body 'requestId')) { Add-Error $errors 'I1C-01 first event must not carry a requestId' }
            if ((Get-CallCount $calls 'E1') -ne 1) { Add-Error $errors 'I1C-01 must call E1 once' }
            if ((Get-CallCount $calls 'E2') -ne 0 -or (Get-CallCount $calls 'E3') -ne 0) { Add-Error $errors 'I1C-01 must not call E2 or E3' }
            break
        }
        'I1C-02' {
            if ($events.Count -ne 2) { Add-Error $errors 'I1C-02 must have exactly two events' }
            if ($events.Count -ge 2) {
                if ($null -ne (Get-Prop $events[0].body 'requestId')) { Add-Error $errors 'I1C-02 round 1 must not carry a requestId' }
                if (-not (Test-UuidV4 ([string](Get-Prop $events[1].body 'requestId')))) { Add-Error $errors 'I1C-02 round 2 must carry a UUID requestId' }
            }
            if ((Get-CallCount $calls 'E1') -ne 0) { Add-Error $errors 'I1C-02 must not call E1' }
            if ((Get-CallCount $calls 'E2') -ne 1 -or (Get-CallCount $calls 'E3') -ne 1) { Add-Error $errors 'I1C-02 must call E2 and E3 exactly once' }
            $e2Indices = @(Get-StageIndices $stubs 'E2')
            $ollamaIndices = @(Get-StageIndices $stubs 'OLLAMA')
            $e3Indices = @(Get-StageIndices $stubs 'E3')
            $e2Before = ($e2Indices.Count -gt 0 -and $ollamaIndices.Count -gt 0 -and $e3Indices.Count -gt 0 -and $e2Indices[0] -lt $ollamaIndices[0] -and $e2Indices[0] -lt $e3Indices[0])
            if (-not $e2Before) { Add-Error $errors 'I1C-02 E2 must occur before extraction (OLLAMA) and E3' }
            break
        }
        'I1C-03' {
            if ($events.Count -ne 1) { Add-Error $errors 'I1C-03 must have exactly one event' }
            if ($events.Count -ge 1 -and $null -ne (Get-Prop $events[0].body 'requestId')) { Add-Error $errors 'I1C-03 first event must not carry a requestId' }
            if (($missingFields -join ',') -ne 'dateNaissance,lieuNaissance') { Add-Error $errors 'I1C-03 missingFields must be exactly [dateNaissance, lieuNaissance]' }
            if ((Get-CallCount $calls 'E1') -ne 1) { Add-Error $errors 'I1C-03 must call E1 once' }
            foreach ($stage in @('E4', 'E5', 'E6')) {
                if ((Get-CallCount $calls $stage) -ne 0) { Add-Error $errors "I1C-03 must not call $stage" }
            }
            break
        }
        'I1C-04' {
            if ($events.Count -ne 1) { Add-Error $errors 'I1C-04 must have exactly one event' }
            if ($events.Count -ge 1) {
                $rid = Get-Prop $events[0].body 'requestId'
                if (-not ($rid -is [string]) -or (Test-UuidV4 $rid)) { Add-Error $errors 'I1C-04 must carry a deliberately invalid requestId' }
            }
            if ($stubs.Count -ne 0) { Add-Error $errors 'I1C-04 must not define stubs' }
            foreach ($stage in $script:Stages) {
                if ((Get-CallCount $calls $stage) -ne 0) { Add-Error $errors "I1C-04 must not call $stage" }
            }
            break
        }
        'I1C-05' {
            if ($events.Count -ne 2) { Add-Error $errors 'I1C-05 must have exactly two events' }
            if ($events.Count -ge 2) {
                if ($null -ne (Get-Prop $events[0].body 'requestId')) { Add-Error $errors 'I1C-05 round 1 must not carry a requestId' }
                if (-not (Test-UuidV4 ([string](Get-Prop $events[1].body 'requestId')))) { Add-Error $errors 'I1C-05 round 2 must carry a UUID requestId' }
            }
            if (($missingFields -join ',') -ne 'lieuNaissance') { Add-Error $errors 'I1C-05 missingFields must be exactly [lieuNaissance]' }
            if ((Get-CallCount $calls 'E1') -ne 1 -or (Get-CallCount $calls 'E2') -ne 1 -or (Get-CallCount $calls 'E3') -ne 1) {
                Add-Error $errors 'I1C-05 must call E1, E2 and E3 exactly once'
            }
            if ((Get-CallCount $calls 'OLLAMA') -ne 2 -or (Get-CallCount $calls 'E8') -ne 2) {
                Add-Error $errors 'I1C-05 must call OLLAMA and E8 twice'
            }
            foreach ($stage in @('E4', 'E5', 'E6')) {
                if ((Get-CallCount $calls $stage) -ne 0) { Add-Error $errors "I1C-05 must not call $stage" }
            }
            $e1Stubs = @($stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E1' })
            if ($e1Stubs.Count -ge 1) {
                $e1Status = Get-Prop $e1Stubs[0] 'httpStatus'
                if ((Get-Prop $e1Stubs[0] 'mode') -ne 'error' -or -not (Test-Integer $e1Status) -or [int]$e1Status -ne 422) {
                    Add-Error $errors 'I1C-05 E1 must be an error 422'
                }
            }
            $e3Stubs = @($stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E3' })
            if ($e3Stubs.Count -ge 1) {
                $e3Status = Get-Prop $e3Stubs[0] 'httpStatus'
                if ((Get-Prop $e3Stubs[0] 'mode') -ne 'success' -or -not (Test-Integer $e3Status) -or [int]$e3Status -ne 200) {
                    Add-Error $errors 'I1C-05 E3 must be a success 200'
                }
                if ((Get-Prop (Get-Prop $e3Stubs[0] 'body') 'status') -ne 'MISSING_INFORMATION') {
                    Add-Error $errors 'I1C-05 E3 status must be MISSING_INFORMATION'
                }
            }
            $e2Indices = @(Get-StageIndices $stubs 'E2')
            $ollamaIndices = @(Get-StageIndices $stubs 'OLLAMA')
            $e3Indices = @(Get-StageIndices $stubs 'E3')
            $e2BeforeSecond = ($e2Indices.Count -gt 0 -and $ollamaIndices.Count -ge 2 -and $e3Indices.Count -gt 0 -and $e2Indices[0] -lt $ollamaIndices[1] -and $e2Indices[0] -lt $e3Indices[0])
            if (-not $e2BeforeSecond) { Add-Error $errors 'I1C-05 E2 must occur before the second extraction (OLLAMA) and E3' }
            break
        }
        'I1D-05' {
            if ($events.Count -ne 2) { Add-Error $errors 'I1D-05 must have exactly two events' }
            if ($events.Count -ge 2) {
                $rid1 = [string](Get-Prop $events[0].body 'requestId')
                $rid2 = [string](Get-Prop $events[1].body 'requestId')
                if (-not (Test-UuidV4 $rid1) -or $rid1 -ne $rid2) { Add-Error $errors 'I1D-05 events must share one UUID requestId' }
            }
            if ($null -eq $idempotency -or -not ($idempotency -is [pscustomobject])) {
                Add-Error $errors 'I1D-05 must declare an idempotency object'
            }
            else {
                if ((Get-Prop $idempotency 'key') -ne 'same-requestId') { Add-Error $errors 'I1D-05 idempotency.key must be same-requestId' }
                if ((Get-Prop $idempotency 'sameDocumentId') -ne $true) { Add-Error $errors 'I1D-05 idempotency.sameDocumentId must be true' }
            }
            if ((Get-CallCount $calls 'E1') -ne 0) { Add-Error $errors 'I1D-05 must not call E1' }
            if ((Get-CallCount $calls 'E2') -ne 2) { Add-Error $errors 'I1D-05 must call E2 twice' }
            if ((Get-CallCount $calls 'E6') -ne 2) { Add-Error $errors 'I1D-05 must call E6 twice' }
            $path = @($stubs | ForEach-Object { [string](Get-Prop $_ 'stage') })
            if ($path.Count -eq 0 -or $path[0] -ne 'E2') { Add-Error $errors 'I1D-05 E2 must be the first call' }
            $lastGenerationIndex = -1
            $roundSemanticsOk = $true
            for ($i = 0; $i -lt $path.Count; $i++) {
                if ($path[$i] -eq 'E2') { $lastGenerationIndex = $i }
                elseif ($path[$i] -eq 'E6') {
                    if ($lastGenerationIndex -lt 0 -or $lastGenerationIndex -ge $i) { $roundSemanticsOk = $false }
                    else { $lastGenerationIndex = -1 }
                }
            }
            if (-not $roundSemanticsOk) { Add-Error $errors 'I1D-05 E6 must occur after E2 generation' }
            break
        }
        'I1D-06' {
            if ($finalOutcome -ne 'ERROR' -or $errorCode -ne 'INTERNAL_ERROR' -or [int]$finalHttpStatus -ne 500) { Add-Error $errors 'I1D-06 must expect 500 INTERNAL_ERROR' }
            if ((Get-CallCount $calls 'E4') -ne 3) { Add-Error $errors 'I1D-06 must call E4 three times' }
            $e4 = @($stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E4' })
            if ($e4.Count -ne 3) { Add-Error $errors 'I1D-06 must define three E4 stubs' }
            foreach ($stub in $e4) {
                if ((Get-Prop $stub 'mode') -ne 'error' -or [int](Get-Prop $stub 'httpStatus') -ne 500) { Add-Error $errors 'I1D-06 E4 stubs must all be error 500' }
            }
            break
        }
        'I1D-07' {
            if ($finalOutcome -ne 'ERROR' -or $errorCode -ne 'AI_EXTRACTION_ERROR' -or [int]$finalHttpStatus -ne 502) { Add-Error $errors 'I1D-07 must expect 502 AI_EXTRACTION_ERROR' }
            if ((Get-CallCount $calls 'OLLAMA') -ne 3) { Add-Error $errors 'I1D-07 must attempt OLLAMA three times' }
            foreach ($stage in @('E8', 'E1', 'E2', 'E3', 'E4', 'E5', 'E6')) {
                if ((Get-CallCount $calls $stage) -ne 0) { Add-Error $errors "I1D-07 must not call $stage" }
            }
            foreach ($stub in @($stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'OLLAMA' })) {
                if ((Get-Prop $stub 'mode') -ne 'connection_refused') { Add-Error $errors 'I1D-07 OLLAMA stubs must use connection_refused mode' }
            }
            break
        }
        'I1D-08' {
            if ($finalOutcome -ne 'ERROR' -or $errorCode -ne 'AI_EXTRACTION_ERROR' -or [int]$finalHttpStatus -ne 502) { Add-Error $errors 'I1D-08 must expect 502 AI_EXTRACTION_ERROR' }
            if ((Get-CallCount $calls 'OLLAMA') -ne 3) { Add-Error $errors 'I1D-08 must attempt OLLAMA three times' }
            foreach ($stage in @('E8', 'E1', 'E2', 'E3', 'E4', 'E5', 'E6')) {
                if ((Get-CallCount $calls $stage) -ne 0) { Add-Error $errors "I1D-08 must not call $stage" }
            }
            foreach ($stub in @($stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'OLLAMA' })) {
                if ((Get-Prop $stub 'mode') -ne 'timeout') { Add-Error $errors 'I1D-08 OLLAMA stubs must use timeout mode' }
                if (-not (Test-Integer (Get-Prop $stub 'delayMs')) -or [int](Get-Prop $stub 'delayMs') -le 0) { Add-Error $errors 'I1D-08 OLLAMA timeout stubs must set delayMs' }
            }
            break
        }
        'I1D-09' {
            if ($null -eq $securityProbe -or -not ($securityProbe -is [pscustomobject])) {
                Add-Error $errors 'I1D-09 must define a securityProbe object'
            }
            else {
                if ((Get-Prop $securityProbe 'kind') -ne 'pii') { Add-Error $errors 'I1D-09 securityProbe.kind must be pii' }
                $markersValue = Get-Prop $securityProbe 'markers'
                $markers = @()
                if ($null -ne $markersValue) { $markers = @($markersValue) }
                if ($markers.Count -lt 4) { Add-Error $errors 'I1D-09 must declare at least four PII markers' }
                $analyzedText = ConvertTo-AnalyzedText $Fixture
                $seenMarkers = @()
                foreach ($marker in $markers) {
                    if (-not ($marker -is [string]) -or $marker.Trim().Length -eq 0) {
                        Add-Error $errors 'I1D-09 markers must be non-empty strings'
                        continue
                    }
                    if ($seenMarkers -cnotcontains $marker) { $seenMarkers += $marker }
                    else { Add-Error $errors "I1D-09 markers must be unique: $marker" }
                    if ($analyzedText.IndexOf($marker, [System.StringComparison]::Ordinal) -lt 0) {
                        Add-Error $errors "I1D-09 marker is absent from the analyzed payload: $marker"
                    }
                }
            }
            if ((Get-CallCount $calls 'E1') -ne 1 -or (Get-CallCount $calls 'E5') -ne 1) { Add-Error $errors 'I1D-09 must keep the normal generation call path' }
            break
        }
        'I1D-10' {
            if ($null -eq $securityProbe -or -not ($securityProbe -is [pscustomobject])) {
                Add-Error $errors 'I1D-10 must define a securityProbe object'
            }
            else {
                if ((Get-Prop $securityProbe 'kind') -ne 'binary') { Add-Error $errors 'I1D-10 securityProbe.kind must be binary' }
                $base64 = Get-Prop $securityProbe 'base64'
                $expectedMagic = [string](Get-Prop $securityProbe 'expectedMagicHex')
                if (-not ($base64 -is [string]) -or $base64.Length -eq 0) {
                    Add-Error $errors 'I1D-10 securityProbe.base64 must be a non-empty string'
                }
                else {
                    $analyzedText = ConvertTo-AnalyzedText $Fixture
                    try {
                        $bytes = [Convert]::FromBase64String($base64)
                        $hex = ($bytes | ForEach-Object { $_.ToString('X2') }) -join ''
                        if ($expectedMagic -ne '504B0304') { Add-Error $errors 'I1D-10 expectedMagicHex must be 504B0304' }
                        if (-not $hex.StartsWith($expectedMagic, [System.StringComparison]::OrdinalIgnoreCase)) {
                            Add-Error $errors "I1D-10 base64 does not start with $expectedMagic"
                        }
                    }
                    catch {
                        Add-Error $errors 'I1D-10 base64 is not valid base64'
                    }
                    if ($analyzedText.IndexOf($base64, [System.StringComparison]::Ordinal) -lt 0) {
                        Add-Error $errors 'I1D-10 binary signature is absent from the analyzed payload'
                    }
                }
            }
            if ((Get-CallCount $calls 'E1') -ne 1 -or (Get-CallCount $calls 'E5') -ne 1) { Add-Error $errors 'I1D-10 must keep the normal generation call path' }
            break
        }
    }

    return @($errors.ToArray())
}

function Assert-Negative-Diagnostic {
    param(
        [string]$Name,
        [object[]]$Errors,
        [string]$ExpectedDiagnostic
    )
    $actual = @($Errors | ForEach-Object { [string]$_ })
    $found = $false
    foreach ($message in $actual) {
        if ($message.IndexOf($ExpectedDiagnostic, [System.StringComparison]::Ordinal) -ge 0) {
            $found = $true
            break
        }
    }
    if ($found) {
        Write-Output ("SELFTEST {0} PASS" -f $Name)
        $script:SelfTestPassed++
    }
    else {
        Write-Output ("SELFTEST {0} FAIL" -f $Name)
        Write-Output ("      expected diagnostic: {0}" -f $ExpectedDiagnostic)
        if ($actual.Count -eq 0) {
            Write-Output '      actual errors: <none>'
        }
        else {
            foreach ($message in $actual) { Write-Output ("      actual error: {0}" -f $message) }
        }
        $script:SelfTestFailed++
    }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
Write-Output 'DG-I1-FIXTURES-001 static validator'
Write-Output ("fixtureDir={0}" -f $FixtureDir)

if (-not (Test-Path -LiteralPath $FixtureDir -PathType Container)) {
    Write-Output 'RESULT: FAIL (fixture directory missing)'
    exit 2
}

try {
    $actualFiles = @(Get-ChildItem -LiteralPath $FixtureDir -File -Filter '*.json' | Sort-Object Name)
}
catch {
    Write-Output 'RESULT: FAIL (fixture directory unreadable)'
    exit 2
}

$expectedFiles = @($script:CaseOrder | ForEach-Object { $script:CaseSpec[$_].File })
$actualNames = @($actualFiles | ForEach-Object { $_.Name })

foreach ($expected in $expectedFiles) {
    if ($actualNames -notcontains $expected) {
        Write-Output ("FAIL missing fixture: {0}" -f $expected)
        $script:Failures++
    }
}
foreach ($actual in $actualNames) {
    if ($expectedFiles -notcontains $actual) {
        Write-Output ("FAIL unexpected fixture: {0}" -f $actual)
        $script:Failures++
    }
}

$loaded = @()
foreach ($name in $expectedFiles) {
    if ($actualNames -notcontains $name) { continue }
    $path = Join-Path $FixtureDir $name
    try {
        $bytes = [System.IO.File]::ReadAllBytes($path)
    }
    catch {
        Write-Output ("FAIL {0} unreadable" -f $name)
        $script:Failures++
        continue
    }

    $encoding = Test-Utf8NoBom $bytes
    if (-not $encoding.Ok) {
        Write-Output ("FAIL {0} encoding: {1}" -f $name, $encoding.Reason)
        $script:Failures++
        continue
    }

    try {
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        $fixture = $text | ConvertFrom-Json
    }
    catch {
        Write-Output ("FAIL {0} invalid JSON: {1}" -f $name, $_.Exception.Message)
        $script:Failures++
        continue
    }

    $errors = @(Test-Fixture $fixture $name)
    if ($errors.Count -eq 0) {
        Write-Output ("PASS {0}" -f $name)
        $script:Passed++
        $loaded += $fixture
    }
    else {
        Write-Output ("FAIL {0}" -f $name)
        foreach ($message in $errors) { Write-Output ("      - {0}" -f $message) }
        $script:Failures++
    }
}

if ($loaded.Count -eq $expectedFiles.Count) {
    $uniqueErrors = @(Test-Uniqueness $loaded)
    if ($uniqueErrors.Count -eq 0) {
        Write-Output 'PASS fixture identifiers unique'
        $script:Passed++
    }
    else {
        Write-Output 'FAIL fixture identifiers unique'
        foreach ($message in $uniqueErrors) { Write-Output ("      - {0}" -f $message) }
        $script:Failures++
    }
}

if ($SelfTest) {
    Write-Output '--- SELF-TEST (negative mutations) ---'
    $byId = @{}
    foreach ($fixture in $loaded) { $byId[[string]$fixture.fixtureId] = $fixture }

    # 1. missing-finalOutcome
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $mutated.expected.PSObject.Properties.Remove('finalOutcome')
        Assert-Negative-Diagnostic 'missing-finalOutcome' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'expected.finalOutcome is not approved'
    }
    else { Assert-Negative-Diagnostic 'missing-finalOutcome' @() 'expected.finalOutcome is not approved' }

    # 2. invalid-finalOutcome
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $mutated.expected.finalOutcome = 'NOT_AN_OUTCOME'
        Assert-Negative-Diagnostic 'invalid-finalOutcome' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'expected.finalOutcome is not approved'
    }
    else { Assert-Negative-Diagnostic 'invalid-finalOutcome' @() 'expected.finalOutcome is not approved' }

    # 3. generated-to-rejected
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $mutated.expected.finalOutcome = 'REJECTED'
        Assert-Negative-Diagnostic 'generated-to-rejected' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'expected.finalOutcome must be GENERATED'
    }
    else { Assert-Negative-Diagnostic 'generated-to-rejected' @() 'expected.finalOutcome must be GENERATED' }

    # 4. generated-to-error
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $mutated.expected.finalOutcome = 'ERROR'
        Assert-Negative-Diagnostic 'generated-to-error' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'expected.finalOutcome must be GENERATED'
    }
    else { Assert-Negative-Diagnostic 'generated-to-error' @() 'expected.finalOutcome must be GENERATED' }

    # 5. incorrect-http-status
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $mutated.expected.finalHttpStatus = 500
        Assert-Negative-Diagnostic 'incorrect-http-status' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'expected.finalHttpStatus must be 200'
    }
    else { Assert-Negative-Diagnostic 'incorrect-http-status' @() 'expected.finalHttpStatus must be 200' }

    # 6. incorrect-error-code
    if ($byId.ContainsKey('I1D-06')) {
        $mutated = Copy-Fixture $byId['I1D-06']
        $mutated.expected.errorCode = 'AI_EXTRACTION_ERROR'
        Assert-Negative-Diagnostic 'incorrect-error-code' @(Test-Fixture $mutated 'i1d_06_backend_error.json') 'expected.errorCode must be INTERNAL_ERROR'
    }
    else { Assert-Negative-Diagnostic 'incorrect-error-code' @() 'expected.errorCode must be INTERNAL_ERROR' }

    # 7. invalid-requestId
    if ($byId.ContainsKey('I1C-02')) {
        $mutated = Copy-Fixture $byId['I1C-02']
        $mutated.input.events[1].body.requestId = 'not-a-uuid'
        Assert-Negative-Diagnostic 'invalid-requestId' @(Test-Fixture $mutated 'i1c_02_second_turn_complete.json') 'input.events[1].body.requestId must be a UUID v4'
    }
    else { Assert-Negative-Diagnostic 'invalid-requestId' @() 'input.events[1].body.requestId must be a UUID v4' }

    # 8. inconsistent-requestId
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $e1Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E1' })
        if ($e1Stub.Count -gt 0) { $e1Stub[0].body.requestId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' }
        Assert-Negative-Diagnostic 'inconsistent-requestId' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'requestId does not match expected.requestId'
    }
    else { Assert-Negative-Diagnostic 'inconsistent-requestId' @() 'requestId does not match expected.requestId' }

    # 9. inconsistent-documentId
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $e6Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E6' })
        if ($e6Stub.Count -gt 0) { $e6Stub[0].body.documentId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' }
        Assert-Negative-Diagnostic 'inconsistent-documentId' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'documentId does not match expected.documentId'
    }
    else { Assert-Negative-Diagnostic 'inconsistent-documentId' @() 'documentId does not match expected.documentId' }

    # 10. inconsistent-missingFields
    if ($byId.ContainsKey('I1C-03')) {
        $mutated = Copy-Fixture $byId['I1C-03']
        $e1Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E1' })
        if ($e1Stub.Count -gt 0) { $e1Stub[0].body.missingFields = @('dateNaissance') }
        Assert-Negative-Diagnostic 'inconsistent-missingFields' @(Test-Fixture $mutated 'i1c_03_missing_information.json') 'missingFields do not match expected.missingFields'
    }
    else { Assert-Negative-Diagnostic 'inconsistent-missingFields' @() 'missingFields do not match expected.missingFields' }

    # 11. stub-count-mismatch
    if ($byId.ContainsKey('I1C-01')) {
        $mutated = Copy-Fixture $byId['I1C-01']
        $mutated.stubs = @($mutated.stubs | Select-Object -Skip 1)
        Assert-Negative-Diagnostic 'stub-count-mismatch' @(Test-Fixture $mutated 'i1c_01_initial_valid.json') 'stub count for'
    }
    else { Assert-Negative-Diagnostic 'stub-count-mismatch' @() 'stub count for' }

    # 12. duplicate-fixtureId
    if ($byId.ContainsKey('I1C-01') -and $byId.ContainsKey('I1C-02')) {
        $mutated = Copy-Fixture $byId['I1C-02']
        $mutated.fixtureId = 'I1C-01'
        Assert-Negative-Diagnostic 'duplicate-fixtureId' @(Test-Uniqueness @($byId['I1C-01'], $mutated)) 'duplicate fixtureId'
    }
    else { Assert-Negative-Diagnostic 'duplicate-fixtureId' @() 'duplicate fixtureId' }

    # 13. missing-pii-markers
    if ($byId.ContainsKey('I1D-09')) {
        $mutated = Copy-Fixture $byId['I1D-09']
        $mutated.securityProbe.markers = @()
        Assert-Negative-Diagnostic 'missing-pii-markers' @(Test-Fixture $mutated 'i1d_09_fictitious_pii.json') 'at least four PII markers'
    }
    else { Assert-Negative-Diagnostic 'missing-pii-markers' @() 'at least four PII markers' }

    # 14. pii-marker-absent-from-payload
    if ($byId.ContainsKey('I1D-09')) {
        $mutated = Copy-Fixture $byId['I1D-09']
        $mutated.input.events[0].body.message = $mutated.input.events[0].body.message.Replace('FAKE-PP-0001', 'FAKE-PP-XXXX')
        Assert-Negative-Diagnostic 'pii-marker-absent-from-payload' @(Test-Fixture $mutated 'i1d_09_fictitious_pii.json') 'absent from the analyzed payload'
    }
    else { Assert-Negative-Diagnostic 'pii-marker-absent-from-payload' @() 'absent from the analyzed payload' }

    # 15. binary-signature-absent
    if ($byId.ContainsKey('I1D-10')) {
        $mutated = Copy-Fixture $byId['I1D-10']
        $e5Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E5' })
        if ($e5Stub.Count -gt 0) { $e5Stub[0].body.PSObject.Properties.Remove('documentBase64') }
        Assert-Negative-Diagnostic 'binary-signature-absent' @(Test-Fixture $mutated 'i1d_10_binary_content.json') 'absent from the analyzed payload'
    }
    else { Assert-Negative-Diagnostic 'binary-signature-absent' @() 'absent from the analyzed payload' }

    # 16. bad-binary-magic
    if ($byId.ContainsKey('I1D-10')) {
        $mutated = Copy-Fixture $byId['I1D-10']
        $mutated.securityProbe.base64 = 'AAAA'
        $e5Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E5' })
        if ($e5Stub.Count -gt 0) { $e5Stub[0].body.documentBase64 = 'AAAA' }
        Assert-Negative-Diagnostic 'bad-binary-magic' @(Test-Fixture $mutated 'i1d_10_binary_content.json') 'does not start with'
    }
    else { Assert-Negative-Diagnostic 'bad-binary-magic' @() 'does not start with' }

    # 17. step-order-i1c-05
    if ($byId.ContainsKey('I1C-05')) {
        $mutated = Copy-Fixture $byId['I1C-05']
        $e2Stubs = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E2' })
        $otherStubs = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -ne 'E2' })
        $mutated.stubs = @($otherStubs + $e2Stubs)
        Assert-Negative-Diagnostic 'step-order-i1c-05' @(Test-Fixture $mutated 'i1c_05_round2_incomplete.json') 'I1C-05 E2 must occur before'
    }
    else { Assert-Negative-Diagnostic 'step-order-i1c-05' @() 'I1C-05 E2 must occur before' }

    # 18. e3-http-i1c-05
    if ($byId.ContainsKey('I1C-05')) {
        $mutated = Copy-Fixture $byId['I1C-05']
        $e3Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E3' })
        if ($e3Stub.Count -gt 0) {
            $e3Stub[0].mode = 'error'
            $e3Stub[0].httpStatus = 422
        }
        Assert-Negative-Diagnostic 'e3-http-i1c-05' @(Test-Fixture $mutated 'i1c_05_round2_incomplete.json') 'I1C-05 E3 must be a success 200'
    }
    else { Assert-Negative-Diagnostic 'e3-http-i1c-05' @() 'I1C-05 E3 must be a success 200' }

    # 19. step-order-i1c-02
    if ($byId.ContainsKey('I1C-02')) {
        $mutated = Copy-Fixture $byId['I1C-02']
        $e2Stubs = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E2' })
        $otherStubs = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -ne 'E2' })
        $mutated.stubs = @($otherStubs + $e2Stubs)
        Assert-Negative-Diagnostic 'step-order-i1c-02' @(Test-Fixture $mutated 'i1c_02_second_turn_complete.json') 'I1C-02 E2 must occur before'
    }
    else { Assert-Negative-Diagnostic 'step-order-i1c-02' @() 'I1C-02 E2 must occur before' }

    # 20. step-order-i1d-05
    if ($byId.ContainsKey('I1D-05')) {
        $mutated = Copy-Fixture $byId['I1D-05']
        $firstStub = $mutated.stubs[0]
        $mutated.stubs[0] = $mutated.stubs[1]
        $mutated.stubs[1] = $firstStub
        Assert-Negative-Diagnostic 'step-order-i1d-05' @(Test-Fixture $mutated 'i1d_05_duplicate_message.json') 'I1D-05 E2 must be the first call'
    }
    else { Assert-Negative-Diagnostic 'step-order-i1d-05' @() 'I1D-05 E2 must be the first call' }

    # 21. utf8-bom-rejected
    $bomBytes = [byte[]](0xEF, 0xBB, 0xBF, 0x7B, 0x7D)
    $bomResult = Test-Utf8NoBom $bomBytes
    Assert-Negative-Diagnostic 'utf8-bom-rejected' @($bomResult.Reason) 'UTF-8 BOM present'

    # 22. message-place-absent — birth place in the message, absent from the paired extraction
    if ($byId.ContainsKey('I1C-05')) {
        $mutated = Copy-Fixture $byId['I1C-05']
        $mutated.input.events[1].body.message = 'N' + [char]0x00E9 + 'e le 01/01/1980 ' + [char]0x00E0 + ' Ville-Fictive.'
        Assert-Negative-Diagnostic 'message-place-absent' @(Test-Fixture $mutated 'i1c_05_round2_incomplete.json') 'message birth place is absent from the paired extraction'
    }
    else { Assert-Negative-Diagnostic 'message-place-absent' @() 'message birth place is absent from the paired extraction' }

    # 23. message-date-absent — birth date in the message, absent from the paired extraction
    if ($byId.ContainsKey('I1C-03')) {
        $mutated = Copy-Fixture $byId['I1C-03']
        $mutated.input.events[0].body.message = 'Attestation fictive, n' + [char]0x00E9 + 'e le 02/02/1990, nom incorrect Camil Fictif, nom correct Camille Fictive.'
        Assert-Negative-Diagnostic 'message-date-absent' @(Test-Fixture $mutated 'i1c_03_missing_information.json') 'message birth date is absent from the paired extraction'
    }
    else { Assert-Negative-Diagnostic 'message-date-absent' @() 'message birth date is absent from the paired extraction' }

    # 24. delta-missing-undeclared — drop one mandatory key still absent from the delta payload
    if ($byId.ContainsKey('I1C-02')) {
        $mutated = Copy-Fixture $byId['I1C-02']
        $e2Index = -1
        for ($i = 0; $i -lt @($mutated.stubs).Count; $i++) {
            if ((Get-Prop $mutated.stubs[$i] 'stage') -eq 'E2') { $e2Index = $i; break }
        }
        $delta = $null
        if ($e2Index -ge 0) {
            for ($i = $e2Index + 1; $i -lt @($mutated.stubs).Count; $i++) {
                $candidate = $mutated.stubs[$i]
                if ((Get-Prop $candidate 'stage') -eq 'OLLAMA' -and (Get-Prop $candidate 'mode') -eq 'success') {
                    $delta = $candidate
                    break
                }
            }
        }
        if ($null -ne $delta) {
            $mandatory = @('prenom', 'nom', 'dateNaissance', 'lieuNaissance', 'nomIncorrect', 'nomCorrect')
            $data = Get-Prop (Get-Prop $delta 'body') 'data'
            $absent = New-Object System.Collections.Generic.List[string]
            foreach ($key in $mandatory) {
                if (-not (Has-Prop $data $key)) { [void]$absent.Add($key) }
            }
            $truncated = @()
            if ($absent.Count -gt 1) { $truncated = @($absent.ToArray()[0..($absent.Count - 2)]) }
            $delta.body.missingFields = [object[]]$truncated
        }
        Assert-Negative-Diagnostic 'delta-missing-undeclared' @(Test-Fixture $mutated 'i1c_02_second_turn_complete.json') 'delta extraction missingFields must redeclare every mandatory key absent from data'
    }
    else { Assert-Negative-Diagnostic 'delta-missing-undeclared' @() 'delta extraction missingFields must redeclare every mandatory key absent from data' }

    # 25. error-code-absent — 422 backend body without ErrorResponse.code
    if ($byId.ContainsKey('I1C-03')) {
        $mutated = Copy-Fixture $byId['I1C-03']
        $e1Stub = @($mutated.stubs | Where-Object { (Get-Prop $_ 'stage') -eq 'E1' })
        if ($e1Stub.Count -gt 0) { $e1Stub[0].body.PSObject.Properties.Remove('code') }
        Assert-Negative-Diagnostic 'error-code-absent' @(Test-Fixture $mutated 'i1c_03_missing_information.json') 'backend 422/400 body.code is absent'
    }
    else { Assert-Negative-Diagnostic 'error-code-absent' @() 'backend 422/400 body.code is absent' }

    Write-Output ("SELFTEST_PASSED={0} SELFTEST_FAILED={1}" -f $script:SelfTestPassed, $script:SelfTestFailed)
    if ($script:SelfTestFailed -gt 0) { $script:Failures += $script:SelfTestFailed }
}

Write-Output ("PASSED={0} FAILED={1}" -f $script:Passed, $script:Failures)
if ($script:Failures -eq 0) {
    Write-Output 'RESULT: PASS'
    exit 0
}
Write-Output 'RESULT: FAIL'
exit 1
