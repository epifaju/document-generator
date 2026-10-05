<#
.SYNOPSIS
  Gate I-A static validation for Phase I.1-A of the administrative document generator.

.DESCRIPTION
  purpose  = Gate I-A static validation for Phase I.1-A (n8n workflow document-generation-v1).
  scope    = STATIC ONLY. The workflow JSON is parsed and inspected as data.
             It is never executed, never evaluated as code, never imported into n8n.
  services = NO service is started, stopped or probed (no docker, no n8n, no ollama,
             no postgres, no backend).
  network  = NONE. No HTTP call, no DNS lookup, no socket.
  safety   = the target workflow file is opened read-only and is NEVER modified.
  runtime  = target < 30 s, hard cap 60 s (enforced by a deadline guard, no unbounded loops).

  Every check prints exactly one line: "A<nn> PASS", "A<nn> FAIL" or
  "A<nn> NOT_STATICALLY_PROVABLE", optionally followed by an indented evidence line.
  A DEFERRED (NOT_STATICALLY_PROVABLE) check never counts as a pass.

  Checks A01..A37. A28 (graph acyclicity) is mandatory: the B1 blocker passed a
  previous gate because no acyclicity check existed. A27 is deliberately deferred
  (DOCX retention in the execution data is not statically provable; gate I-B owns
  that proof). A29 asserts the hard part of the 4 timeout/retry table (every timeout,
  and retryOnFail/maxTries for every stage whose 4 retry prescription is a blanket
  retry) and, after the human ruling R2, PROVES the 4 bound of 2 E5 attempts
  structurally instead of deferring it: exactly two generate nodes, neither with a
  blanket retry, the retry terminating at FinalizeResponse, and a real runtime
  counter (initialised in MergeContext, incremented in AdoptE4Recovery, compared to
  MAX_E5_ATTEMPTS=2 in PrepareE5Recovery). Only the E1 conditional retry remains
  deferred and is reported as a separate A29-E1-DEFERRED NOT_STATICALLY_PROVABLE
  line, which is never counted as a pass. A30..A34 assert the 9 env whitelist, the
  webhook responseMode, settings.executionTimeout, the prompt asset reference and the
  UTF-8-without-BOM encoding. A35 asserts the E2 status routing after the human ruling
  R6 (MISSING_INFORMATION is the only status allowed to reach E3PatchRequest,
  VALIDATED/DRAFT/FAILED are resumed through E4 then E5 on a resume read only, a
  reconciliation stays read-only), the read-only reconciliation path (no terminal
  state mutated, GENERATED only after E6) and the R2 recovery chain wiring; A36
  asserts the transport-failure / received-status / DATABASE_ERROR ceiling and the
  R1/R2 classification of the received 5xx carrying a generation cause; and A37 asserts
  the technical-precedence order, the C5 fail-fast short-circuits, the absence of any
  fabricated status or outcome on the recovery path, the removal of the dead branch
  and the dead webhook response header.

  Exit codes:
    0 = every provable check passed (no FAIL)
    1 = at least one check FAILED
    2 = the validation itself could not run (missing file, unreadable, fatal parse error)
    3 = deadline exceeded (> 60 s)

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tests\n8n\validate_workflow.ps1

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tests\n8n\validate_workflow.ps1 -WorkflowPath C:\tmp\mutated.json
#>
[CmdletBinding()]
param(
    [string]$WorkflowPath,
    [int]$DeadlineSeconds = 60
)

$ErrorActionPreference = 'Stop'
$sw = [System.Diagnostics.Stopwatch]::StartNew()

# ---------------------------------------------------------------------------
# Approved vocabularies (I.1-A contracts)
# ---------------------------------------------------------------------------
$script:ApprovedOutcomes = @(
    'GENERATED', 'MISSING_INFORMATION', 'REJECTED', 'GENERATION_FAILED',
    'REQUEST_NOT_FOUND', 'UNSUPPORTED_DOCUMENT_TYPE', 'CLARIFICATION_LIMIT_REACHED',
    'INVALID_REQUEST', 'ERROR'
)
$script:ApprovedErrorCodes = @(
    'VALIDATION_ERROR', 'AI_EXTRACTION_ERROR', 'EXTRACTION_SCHEMA_INVALID',
    'BACKEND_UNAVAILABLE', 'INTERNAL_ERROR', 'REQUEST_NOT_FOUND'
)
$script:RequiredNodes = @(
    'Webhook', 'InitOrchestration', 'LoadPrompt', 'OllamaExtract', 'ParseExtraction',
    'E8ValidateExtraction', 'E1CreateRequest', 'E2GetRequest', 'E3PatchRequest',
    'E4ValidateRequest', 'E5GenerateDocument', 'E6VerifyDocument',
    'E2ReconcileRequest', 'RouteE2Status', 'RouteMutatingFailure', 'AdoptE2Reconcile',
    'FinalizeResponse', 'RespondToWebhook',
    # R2 bounded E5 recovery. Mandatory, not optional: the graph is the proof that
    # E5 runs at most twice per execution (A29), so a workflow without these four
    # nodes cannot satisfy the 4 table any more.
    'PrepareE5Recovery', 'E4ReconcileValidate', 'AdoptE4Recovery', 'E5RetryGenerate'
)
# Internal hop markers that are NOT egress outcomes. They are tolerated only when
# they never appear in the RespondToWebhook responseCode map and are never the
# value assigned to an `outcome` field.
$script:InternalMarkers = @(
    'INITIALIZED', 'PROMPT_LOADED', 'EXTRACTION_PARSED', 'CONTEXT_READY',
    'CLARIFICATION_REQUIRED', 'PROCEED'
)
# Non-outcome uppercase literals legitimately present in orchestration code
# (backend business codes, causes, technical codes, node-name registries).
$script:NonOutcomeLiterals = @(
    'ERR_DOCUMENT_TYPE_NON_SUPPORTE', 'ERR_CHAMP_INCONNU', 'ERR_CHAMP_RESERVE',
    'TEMPLATE_NOT_FOUND', 'DOCUMENT_GENERATION_ERROR', 'DATABASE_ERROR',
    'MISSING_INFORMATION', 'FAILED', 'UNSUPPORTED_AI_PROVIDER',
    'PROMPTS_DIR_NOT_CONFIGURED', 'PROMPT_ASSET_UNREADABLE', 'PROMPT_ASSET_EMPTY',
    'SCHEMA_ASSET_UNREADABLE', 'SCHEMA_ASSET_INVALID',
    'AI_EXTRACTION_MALFORMED_CONTENT', 'AI_EXTRACTION_INVALID_JSON',
    'AI_EXTRACTION_NOT_AN_OBJECT',
    'UNEXPECTED_FIELDS', 'INVALID_REQUEST_ID', 'MESSAGE_REQUIRED',
    'MESSAGE_LENGTH_INVALID',
    'E6VerifyDocument', 'E5GenerateDocument', 'E4ValidateRequest', 'E3PatchRequest',
    'E2GetRequest', 'E1CreateRequest', 'E8ValidateExtraction', 'OllamaExtract',
    'LoadPrompt', 'ParseExtraction',
    'E2ReconcileRequest', 'AdoptE2Reconcile', 'MergeContext', 'CheckClarificationBound',
    'AdoptE2Response', 'AdoptRequestStatus', 'AdoptE4Response', 'AdoptE5Response',
    'PrepareE5Recovery', 'E4ReconcileValidate', 'AdoptE4Recovery', 'E5RetryGenerate',
    'VALIDATED', 'DRAFT', 'RECONCILE_ON_E2', 'RECONCILE_ON_TIMEOUT',
    'E2_GENERATED_TERMINAL', 'E2_VALIDATED_WITH_DOCUMENTS', 'E2_REJECTED_TERMINAL',
    'E2_MISSING_INFORMATION_ONLY', 'E2_RESUME_E4_GUARD', 'E2_PATCHABLE',
    'RECOVER_E5_ON_RECEIVED_5XX', 'UNSUPPORTED_DOCUMENT_TYPE', 'EXTRACTION_USABLE'
)

# ---------------------------------------------------------------------------
# Result plumbing
# ---------------------------------------------------------------------------
$script:PASSED = 0
$script:FAILED = 0
$script:DEFERRED = 0
$script:Failures = New-Object System.Collections.Generic.List[string]

function Write-Result {
    param(
        [string]$Id,
        [ValidateSet('PASS', 'FAIL', 'NOT_STATICALLY_PROVABLE')][string]$Status,
        [string]$Evidence = ''
    )
    Write-Output ("{0} {1}" -f $Id, $Status)
    if ($Evidence -ne '') { Write-Output ("      evidence: " + $Evidence) }
    switch ($Status) {
        'PASS' { $script:PASSED++ }
        'FAIL' {
            $script:FAILED++
            $script:Failures.Add($Id)
        }
        default { $script:DEFERRED++ }
    }
}

function Assert-Deadline {
    if ($sw.Elapsed.TotalSeconds -gt $DeadlineSeconds) {
        Write-Output ("TIMEOUT exceeded hard cap of {0}s (elapsed {1:N1}s)" -f $DeadlineSeconds, $sw.Elapsed.TotalSeconds)
        Write-Output 'RESULT: FAIL'
        Write-Output ("PASSED={0} FAILED={1} DEFERRED={2}" -f $script:PASSED, ($script:FAILED + 1), $script:DEFERRED)
        exit 3
    }
}

# ---------------------------------------------------------------------------
# Repository root discovery: walk up from $PSScriptRoot until n8n\workflows exists
# ---------------------------------------------------------------------------
function Find-RepoRoot {
    param([string]$StartDir)
    $dir = $StartDir
    for ($i = 0; $i -lt 12 -and $dir; $i++) {
        if (Test-Path -LiteralPath (Join-Path $dir 'n8n\workflows') -PathType Container) { return $dir }
        $parent = Split-Path -Path $dir -Parent
        if (-not $parent -or $parent -eq $dir) { break }
        $dir = $parent
    }
    return $null
}

$repoRoot = Find-RepoRoot -StartDir $PSScriptRoot
if (-not $WorkflowPath) {
    if ($repoRoot) {
        $WorkflowPath = Join-Path $repoRoot 'n8n\workflows\document-generation-v1.json'
    }
    else {
        $WorkflowPath = Join-Path (Get-Location).Path 'n8n\workflows\document-generation-v1.json'
    }
}
$WorkflowPath = [System.IO.Path]::GetFullPath($WorkflowPath)

Write-Output ('validate_workflow.ps1 - Gate I-A static validation for Phase I.1-A')
Write-Output ('target: ' + $WorkflowPath)
Write-Output ''

# ---------------------------------------------------------------------------
# A01 - file exists and is readable
# ---------------------------------------------------------------------------
$raw = $null
if (-not (Test-Path -LiteralPath $WorkflowPath -PathType Leaf)) {
    Write-Result 'A01' 'FAIL' ("expected an existing workflow file at '" + $WorkflowPath + "'; found nothing")
    Write-Output ''
    Write-Output 'SUMMARY: A01 failed, validation aborted (nothing to parse).'
    Write-Output 'RESULT: FAIL'
    Write-Output ("PASSED={0} FAILED={1} DEFERRED={2}" -f $script:PASSED, $script:FAILED, $script:DEFERRED)
    Write-Output ("SCRIPT_RUNTIME_SECONDS={0:N2}" -f $sw.Elapsed.TotalSeconds)
    exit 2
}
try {
    $raw = [System.IO.File]::ReadAllText($WorkflowPath)
    if ($raw.Trim().Length -eq 0) { throw 'file is empty' }
    Write-Result 'A01' 'PASS' ("readable, " + $raw.Length + " chars, " + (New-Object System.IO.FileInfo($WorkflowPath)).Length + " bytes")
}
catch {
    Write-Result 'A01' 'FAIL' ("file exists but is not readable as UTF-8 text: " + $_.Exception.Message)
    Write-Output ''
    Write-Output 'SUMMARY: A01 failed, validation aborted.'
    Write-Output 'RESULT: FAIL'
    Write-Output ("PASSED={0} FAILED={1} DEFERRED={2}" -f $script:PASSED, $script:FAILED, $script:DEFERRED)
    Write-Output ("SCRIPT_RUNTIME_SECONDS={0:N2}" -f $sw.Elapsed.TotalSeconds)
    exit 2
}

# ---------------------------------------------------------------------------
# A02 - parses as JSON
# ---------------------------------------------------------------------------
$wf = $null
try {
    $wf = $raw | ConvertFrom-Json
    if ($null -eq $wf) { throw 'ConvertFrom-Json returned null' }
    Write-Result 'A02' 'PASS' 'ConvertFrom-Json parsed the document'
}
catch {
    $msg = $_.Exception.Message -replace '\s+', ' '
    Write-Result 'A02' 'FAIL' ("expected valid JSON; parse error: " + $msg)
    Write-Output ''
    Write-Output 'SUMMARY: A02 failed, the file is not valid JSON; remaining checks skipped.'
    Write-Output 'RESULT: FAIL'
    Write-Output ("PASSED={0} FAILED={1} DEFERRED={2}" -f $script:PASSED, $script:FAILED, $script:DEFERRED)
    Write-Output ("SCRIPT_RUNTIME_SECONDS={0:N2}" -f $sw.Elapsed.TotalSeconds)
    exit 1
}
Assert-Deadline

# Convenience structures
$topKeys = @($wf.PSObject.Properties.Name)
$nodes = @()
if ($topKeys -contains 'nodes' -and $null -ne $wf.nodes) { $nodes = @($wf.nodes) }
$nodeByName = @{}
foreach ($n in $nodes) {
    if ($n.PSObject.Properties.Name -contains 'name' -and $n.name) { $nodeByName[[string]$n.name] = $n }
}
$jsCodeByNode = @{}
foreach ($n in $nodes) {
    if ($n.PSObject.Properties.Name -contains 'parameters' -and $null -ne $n.parameters) {
        $pkeys = @($n.parameters.PSObject.Properties.Name)
        if ($pkeys -contains 'jsCode' -and $n.parameters.jsCode) {
            $jsCodeByNode[[string]$n.name] = [string]$n.parameters.jsCode
        }
    }
}
$httpNodes = @($nodes | Where-Object {
    $_.PSObject.Properties.Name -contains 'type' -and $_.type -and ([string]$_.type) -match '(?i)httpRequest$'
})
# Script-scoped views reused by A35..A37 (declared here so the new checks never
# recompute the parse result).
$script:NodeByName = $nodeByName
$script:JsCodeByNode = $jsCodeByNode
$script:HttpNodeNames = @($httpNodes | ForEach-Object { [string]$_.name })

function Get-NodeText {
    param($Node)
    if ($null -eq $Node) { return '' }
    try {
        $t = ($Node | ConvertTo-Json -Depth 40 -Compress)
        # ConvertTo-Json escapes quotes as \u0022 / \u0027; unescape so that the
        # text-level regexes below see the same characters as the source file.
        return ($t -replace '\\u0022', '"' -replace "\\u0027", "'" -replace '\\u0026', '&' -replace '\\u003c', '<' -replace '\\u003e', '>' -replace '\\u002f', '/')
    }
    catch { return '' }
}

# ---------------------------------------------------------------------------
# A03 - top-level keys present, active === false
# ---------------------------------------------------------------------------
$missingKeys = @('name', 'nodes', 'connections', 'settings') | Where-Object { $topKeys -notcontains $_ }
$evidence3 = ''
if ($missingKeys.Count -gt 0) {
    $evidence3 = "expected top-level keys name,nodes,connections,settings; missing: " + ($missingKeys -join ',')
}
elseif ($topKeys -notcontains 'active') {
    $evidence3 = 'expected an explicit top-level "active" property; it is absent'
}
elseif ($wf.active -isnot [bool]) {
    $evidence3 = ('expected active to be the JSON boolean false; found type ' + $(if ($null -eq $wf.active) { 'null' } else { $wf.active.GetType().Name }) + ' value ' + [string]$wf.active)
}
elseif ($wf.active -ne $false) {
    $evidence3 = 'expected active=false so the workflow cannot auto-execute on import; found active=' + ([string]$wf.active)
}
else {
    $evidence3 = 'keys name,nodes,connections,settings present; active=false (boolean)'
}
if ($evidence3 -like 'keys name*') { Write-Result 'A03' 'PASS' $evidence3 } else { Write-Result 'A03' 'FAIL' $evidence3 }
Assert-Deadline

# ---------------------------------------------------------------------------
# A04 - node count inside the hard envelope
# ---------------------------------------------------------------------------
# The hard envelope is what protects A05 (unique ids) and A07 (importable graph),
# so it is deliberately wider than the expected band: A04 must never silently
# bless a workflow that grew without a documented decision, and it must never
# fail one either. Nodes added by the human rulings R2 (bounded E5 recovery:
# PrepareE5Recovery, E4ReconcileValidate, AdoptE4Recovery, E5RetryGenerate) and
# R6 (third output of AdoptE2Reconcile) are part of the reviewed design, so the
# expected band moves with them: 28 -> 32.
$count = $nodes.Count
if ($count -lt 2 -or $count -gt 40) {
    Write-Result 'A04' 'FAIL' ("actual node count " + $count + " is outside the hard envelope 2..40")
}
elseif ($count -lt 14 -or $count -gt 32) {
    Write-Result 'A04' 'PASS' ("actual node count " + $count + " (inside 2..40 but outside the expected 14..32 envelope)")
}
else {
    Write-Result 'A04' 'PASS' ("actual node count " + $count + " inside the expected 14..32 envelope")
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A05 - node id uniqueness
# ---------------------------------------------------------------------------
$ids = @()
$missingId = @()
foreach ($n in $nodes) {
    if (-not ($n.PSObject.Properties.Name -contains 'id') -or -not $n.id) { $missingId += [string]$n.name }
    else { $ids += [string]$n.id }
}
$dupIds = @($ids | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
if ($missingId.Count -gt 0) {
    Write-Result 'A05' 'FAIL' ("expected a unique id on every node; nodes without id: " + ($missingId -join ', '))
}
elseif ($dupIds.Count -gt 0) {
    Write-Result 'A05' 'FAIL' ("expected unique node ids; duplicated: " + ($dupIds -join ', '))
}
else {
    Write-Result 'A05' 'PASS' ($count.ToString() + ' distinct node ids')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A06 - node name uniqueness
# ---------------------------------------------------------------------------
$names = @($nodes | ForEach-Object { [string]$_.name })
$dupNames = @($names | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
if ($dupNames.Count -gt 0) {
    Write-Result 'A06' 'FAIL' ("expected unique node names; duplicated: " + ($dupNames -join ', '))
}
else {
    Write-Result 'A06' 'PASS' ($count.ToString() + ' distinct node names')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A07 / A08 - connection endpoints resolve to existing nodes
# ---------------------------------------------------------------------------
function Get-ConnectionRefs {
    param($Connections)
    $refs = New-Object System.Collections.Generic.List[object]
    if ($null -eq $Connections) { return $refs }
    foreach ($srcProp in $Connections.PSObject.Properties) {
        $src = [string]$srcProp.Name
        $conns = $srcProp.Value
        if ($null -eq $conns) { continue }
        if (-not ($conns.PSObject.Properties.Name -contains 'main')) {
            # Never ignore a connection entry silently: a shape the graph model does not
            # cover hides its edges from A07, A08, A09 and A28, so acyclicity could no
            # longer be asserted. Recorded and reported by A07.
            $branchNames = @($conns.PSObject.Properties.Name)
            if ($conns -is [System.Array]) {
                $script:UnmodelledConnections.Add($src + ' -> [unmodelled array, ' + @($conns).Count.ToString() + ' entries, no main branch]')
            }
            elseif ($branchNames.Count -gt 0) {
                $script:UnmodelledConnections.Add($src + ' -> [' + ($branchNames -join ',') + ']')
            }
            continue
        }
        $main = $conns.main
        for ($i = 0; $i -lt @($main).Count; $i++) {
            $branch = @($main)[$i]
            foreach ($c in @($branch)) {
                if ($null -eq $c) { continue }
                $target = if ($c.PSObject.Properties.Name -contains 'node') { [string]$c.node } else { '' }
                if ($target -eq '') { continue }
                $refs.Add([pscustomobject]@{ Source = $src; Target = $target; Index = $i })
            }
        }
    }
    return $refs
}
$script:UnmodelledConnections = New-Object System.Collections.Generic.List[string]
$refs = @(Get-ConnectionRefs -Connections $wf.connections)
$script:AllRefs = $refs
$badSources = @($refs | Where-Object { -not $nodeByName.ContainsKey($_.Source) } | ForEach-Object { $_.Source } | Sort-Object -Unique)
$badTargets = @($refs | Where-Object { -not $nodeByName.ContainsKey($_.Target) } | ForEach-Object { $_.Target } | Sort-Object -Unique)
if ($badSources.Count -gt 0) {
    Write-Result 'A07' 'FAIL' ("expected every connection source to be a declared node; unknown sources: " + ($badSources -join ', '))
}
elseif ($script:UnmodelledConnections.Count -gt 0) {
    Write-Result 'A07' 'FAIL' ("connection entries whose shape the graph model does not cover, so their edges are invisible to A07/A08/A09/A28: " + (($script:UnmodelledConnections | Sort-Object -Unique) -join '; '))
}
else {
    Write-Result 'A07' 'PASS' ($refs.Count.ToString() + ' connection edges, all source names resolve')
}
if ($badTargets.Count -gt 0) {
    Write-Result 'A08' 'FAIL' ("expected every connection target to be a declared node; unknown targets: " + ($badTargets -join ', '))
}
else {
    Write-Result 'A08' 'PASS' ($refs.Count.ToString() + ' connection edges, all target names resolve')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A09 - every node reachable from Webhook
# ---------------------------------------------------------------------------
$outMap = @{}
foreach ($r in $refs) {
    if (-not $outMap.ContainsKey($r.Source)) { $outMap[$r.Source] = New-Object System.Collections.Generic.List[string] }
    $outMap[$r.Source].Add($r.Target)
}
$visited = New-Object System.Collections.Generic.HashSet[string]
$queue = New-Object System.Collections.Generic.Queue[string]
if ($nodeByName.ContainsKey('Webhook')) { $queue.Enqueue('Webhook') }
else {
    Write-Result 'A09' 'FAIL' 'no Webhook node to traverse from'
}
if ($nodeByName.ContainsKey('Webhook')) {
    $visited.Add('Webhook') | Out-Null
    while ($queue.Count -gt 0) {
        $cur = $queue.Dequeue()
        if ($outMap.ContainsKey($cur)) {
            foreach ($nx in $outMap[$cur]) {
                if ($visited.Add($nx)) { $queue.Enqueue($nx) }
            }
        }
    }
    $unreachable = @($names | Where-Object { -not $visited.Contains($_) } | Sort-Object -Unique)
    if ($unreachable.Count -gt 0) {
        Write-Result 'A09' 'FAIL' ("expected all nodes reachable from Webhook; unreachable: " + ($unreachable -join ', '))
    }
    else {
        Write-Result 'A09' 'PASS' ($visited.Count.ToString() + ' of ' + $count.ToString() + ' nodes reachable from Webhook')
    }
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A10 - FinalizeResponse connects onward to RespondToWebhook
# ---------------------------------------------------------------------------
$fwToRespond = @($refs | Where-Object { $_.Source -eq 'FinalizeResponse' -and $_.Target -eq 'RespondToWebhook' })
$finalizeHasOut = $false
if ($nodeByName.ContainsKey('FinalizeResponse')) {
    if ($wf.connections.PSObject.Properties.Name -contains 'FinalizeResponse') { $finalizeHasOut = $true }
}
if (-not $nodeByName.ContainsKey('FinalizeResponse')) {
    Write-Result 'A10' 'FAIL' 'FinalizeResponse node not found'
}
elseif ($finalizeHasOut -and $fwToRespond.Count -eq 0) {
    $targets = @($refs | Where-Object { $_.Source -eq 'FinalizeResponse' } | ForEach-Object { $_.Target } | Sort-Object -Unique)
    Write-Result 'A10' 'FAIL' ("expected FinalizeResponse -> RespondToWebhook; actual targets: " + ($(if ($targets.Count) { $targets -join ', ' } else { '<none>' })))
}
elseif ($fwToRespond.Count -gt 0) {
    Write-Result 'A10' 'PASS' 'FinalizeResponse -> RespondToWebhook (main output) present'
}
else {
    Write-Result 'A10' 'FAIL' 'expected FinalizeResponse -> RespondToWebhook; FinalizeResponse has no outgoing connection at all'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A11 - required nodes present
# ---------------------------------------------------------------------------
$missingRequired = @($script:RequiredNodes | Where-Object { -not $nodeByName.ContainsKey($_) })
if ($missingRequired.Count -gt 0) {
    Write-Result 'A11' 'FAIL' ("expected these nodes to exist; missing: " + ($missingRequired -join ', '))
}
else {
    Write-Result 'A11' 'PASS' ('all ' + $script:RequiredNodes.Count.ToString() + ' required node names present')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A12 - no node carries a credentials property
# ---------------------------------------------------------------------------
$credNodes = New-Object System.Collections.Generic.List[string]
foreach ($n in $nodes) {
    if (@($n.PSObject.Properties.Name) -contains 'credentials') { $credNodes.Add([string]$n.name) }
}
if ($credNodes.Count -gt 0) {
    Write-Result 'A12' 'FAIL' ("expected no node to carry a credentials property; found it on: " + ($credNodes -join ', '))
}
else {
    Write-Result 'A12' 'PASS' ('no credentials property on any of the ' + $count.ToString() + ' nodes')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A13 - no secret-like literal anywhere in the file
# ---------------------------------------------------------------------------
$secretPatterns = [ordered]@{
    'password'             = '(?i)pass(word|wd)'
    'secret'               = '(?i)secret'
    'api key'              = '(?i)api[_-]?key'
    'bearer token'         = '(?i)Bearer\s+[A-Za-z0-9\-._~+/]{8,}'
    'private key block'    = 'BEGIN\s+[A-Z ]*PRIVATE KEY'
    'postgres url'         = '(?i)postgres(ql)?://'
    'jdbc url'             = '(?i)jdbc:'
    'mongodb url'          = '(?i)mongodb(\+srv)?://'
    'authorization literal'= '(?i)["'']?authorization["'']?\s*[:=]\s*["''](?!\{\{)[^"'']{3,}'
}
$secretHits = New-Object System.Collections.Generic.List[string]
foreach ($k in $secretPatterns.Keys) {
    $m = [regex]::Matches($raw, $secretPatterns[$k])
    if ($m.Count -gt 0) {
        $snippet = ($m[0].Value -replace '\s+', ' ')
        if ($snippet.Length -gt 60) { $snippet = $snippet.Substring(0, 60) + '...' }
        $secretHits.Add($k + " (x" + $m.Count.ToString() + ") e.g. '" + $snippet + "'")
    }
}
if ($secretHits.Count -gt 0) {
    Write-Result 'A13' 'FAIL' ("expected no secret-like literal; found: " + ($secretHits -join '; '))
}
else {
    Write-Result 'A13' 'PASS' ('no secret-like literal matched (' + $secretPatterns.Count.ToString() + ' patterns over the whole file)')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A14 - no database node type
# ---------------------------------------------------------------------------
$dbTypePattern = '(?i)(postgres|postgresql|mysql|mariadb|mongodb|redis|mssql|oracle|sqlite|executeQuery|executequery|\bdb\b)'
$dbNodes = New-Object System.Collections.Generic.List[string]
foreach ($n in $nodes) {
    $t = ''
    if ($n.PSObject.Properties.Name -contains 'type') { $t = [string]$n.type }
    if ($t -match $dbTypePattern -or $t -match '(?i)n8n-nodes-base\..*[Dd]ata.*') {
        $dbNodes.Add(([string]$n.name + ' -> ' + $t))
    }
}
if ($dbNodes.Count -gt 0) {
    Write-Result 'A14' 'FAIL' ("expected no PostgreSQL/database node; found: " + ($dbNodes -join '; '))
}
else {
    $types = (@($nodes | ForEach-Object { [string]$_.type }) | Sort-Object -Unique) -join ', '
    Write-Result 'A14' 'PASS' ('no database node type; declared types: ' + $types)
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A15 - no DOCX/template generation node, no shell execution
# ---------------------------------------------------------------------------
$renderTypePattern = '(?i)(executeCommand|exec|shell|functionItem|code.?python|docker|convertToFile|htmlToPdf|readWriteFile|extractFromFile|compression|ftp|ssh|emailSend)'
$renderNodes = New-Object System.Collections.Generic.List[string]
foreach ($n in $nodes) {
    $t = ''
    if ($n.PSObject.Properties.Name -contains 'type') { $t = [string]$n.type }
    if ($t -match '(?i)executeCommand') { $renderNodes.Add(([string]$n.name + ' -> ' + $t + ' (shell execution)')) }
    elseif ($t -match $renderTypePattern) { $renderNodes.Add(([string]$n.name + ' -> ' + $t)) }
}
$renderRefs = New-Object System.Collections.Generic.List[string]
foreach ($p in @('poi', 'docx4j', 'libreoffice', 'pandoc', 'openxml', 'aspose', 'docxtemplater', 'soffice')) {
    $m = [regex]::Matches($raw, [regex]::Escape($p), 'IgnoreCase')
    if ($m.Count -gt 0) { $renderRefs.Add($p + ' (x' + $m.Count.ToString() + ')') }
}
if ($renderNodes.Count -gt 0 -or $renderRefs.Count -gt 0) {
    $ev = @()
    if ($renderNodes.Count) { $ev += ('nodes: ' + ($renderNodes -join '; ')) }
    if ($renderRefs.Count) { $ev += ('rendering tool references: ' + ($renderRefs -join '; ')) }
    Write-Result 'A15' 'FAIL' ("expected no document rendering / shell node; found " + ($ev -join ' | '))
}
else {
    Write-Result 'A15' 'PASS' 'no rendering node type and no poi/docx4j/libreoffice/pandoc reference'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A16 - backend endpoints are a subset of the approved set
# ---------------------------------------------------------------------------
$approvedPaths = @(
    '/api/v1/extraction/validate',
    '/api/v1/requests',
    '/api/v1/requests/{{...}}',
    '/api/v1/requests/{{...}}/validate',
    '/api/v1/requests/{{...}}/generate',
    '/api/v1/requests/{{...}}/documents/{{...}}'
)
$observedPaths = New-Object System.Collections.Generic.List[string]
$badPaths = New-Object System.Collections.Generic.List[string]
foreach ($n in $httpNodes) {
    $url = ''
    if ($n.PSObject.Properties.Name -contains 'parameters' -and $n.parameters.PSObject.Properties.Name -contains 'url') {
        $url = [string]$n.parameters.url
    }
    $flat = ($url -replace '\{\{[^}]*\}\}', '{{...}}')
    $flat = ($flat -replace '\s+', ' ')
    $m = [regex]::Match($flat, '/api/v1/[^\s"'']*')
    if ($m.Success) {
        $p = $m.Value.TrimEnd(',', '.', ';', ')')
        $observedPaths.Add(([string]$n.name + ': ' + $p))
        if ($approvedPaths -notcontains $p) { $badPaths.Add(([string]$n.name + ': ' + $p)) }
    }
}
# any other /api/v1/ occurrence anywhere in the file (jsCode included).
# The path token is built from {{ ... }} expressions and plain path segments so
# that a n8n expression such as {{ $json.requestId }} is consumed as a whole.
$allApiPaths = @()
$pathToken = '(?:\{\{[^{}]*\}\}|[A-Za-z0-9_\-]+)'
foreach ($m in [regex]::Matches($raw, ('/api/v1/(?:' + $pathToken + '/?)+'))) {
    $cand = ($m.Value -replace '\{\{[^{}]*\}\}', '{{...}}')
    $cand = ($cand -replace '\{\{\.\.\.\}\}//', '{{...}}')
    $cand = $cand.TrimEnd('/', ',', '.', ';')
    $cand = $cand -replace '\{\{\.\.\.\}\}', '{{...}}'
    if ($cand -eq '/api/v1/') { continue }
    $allApiPaths += $cand
}
$badAnywhere = New-Object System.Collections.Generic.List[string]
foreach ($p in ($allApiPaths | Sort-Object -Unique)) {
    if ($approvedPaths -notcontains $p) { $badAnywhere.Add($p) }
}
if ($badPaths.Count -gt 0 -or $badAnywhere.Count -gt 0) {
    $ev = @()
    if ($badPaths.Count) { $ev += ('unapproved node URLs: ' + ($badPaths -join '; ')) }
    if ($badAnywhere.Count) { $ev += ('unapproved /api/v1/ literals in file: ' + ($badAnywhere -join '; ')) }
    Write-Result 'A16' 'FAIL' ("expected only the approved endpoints; " + ($ev -join ' | '))
}
else {
    Write-Result 'A16' 'PASS' ('all referenced endpoints are in the approved set: ' + (($observedPaths | Sort-Object -Unique) -join ', '))
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A17 - AI extraction call present (OLLAMA_BASE_URL + /api/chat)
# ---------------------------------------------------------------------------
$aiNodes = New-Object System.Collections.Generic.List[string]
foreach ($n in $httpNodes) {
    $url = ''
    if ($n.PSObject.Properties.Name -contains 'parameters' -and $n.parameters.PSObject.Properties.Name -contains 'url') {
        $url = [string]$n.parameters.url
    }
    if ($url -match '\$env\.OLLAMA_BASE_URL' -and $url -match '/api/chat') { $aiNodes.Add([string]$n.name) }
}
if ($aiNodes.Count -eq 0) {
    Write-Result 'A17' 'FAIL' 'expected an HTTP node calling $env.OLLAMA_BASE_URL/api/chat; none found'
}
else {
    Write-Result 'A17' 'PASS' ('AI extraction call found on: ' + ($aiNodes -join ', '))
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A18 - no hard-coded absolute URL in node url parameters
# ---------------------------------------------------------------------------
$hardUrls = New-Object System.Collections.Generic.List[string]
foreach ($n in $nodes) {
    if (-not ($n.PSObject.Properties.Name -contains 'parameters')) { continue }
    if (-not ($n.parameters.PSObject.Properties.Name -contains 'url')) { continue }
    $url = [string]$n.parameters.url
    foreach ($m in [regex]::Matches($url, 'https?://[^\s"'']+')) {
        $literal = $m.Value
        if ($url -match '\$env\.') { continue }
        $hardUrls.Add(([string]$n.name + ': ' + $literal))
    }
}
if ($hardUrls.Count -gt 0) {
    Write-Result 'A18' 'FAIL' ("expected only $env-based URLs; literal host found in: " + ($hardUrls -join '; '))
}
else {
    $urlNodes = @($nodes | Where-Object { $_.PSObject.Properties.Name -contains 'parameters' -and $_.parameters.PSObject.Properties.Name -contains 'url' })
    $allEnv = ($urlNodes | Where-Object { ([string]$_.parameters.url) -notmatch 'https?://' }).Count -eq $urlNodes.Count
    if ($allEnv) {
        Write-Result 'A18' 'PASS' ('no http:// or https:// literal in any node url parameter (' + $urlNodes.Count.ToString() + ' urls inspected)')
    }
    else {
        Write-Result 'A18' 'PASS' ('no hard-coded absolute URL; node urls use $env base URLs (' + $urlNodes.Count.ToString() + ' urls inspected)')
    }
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A19 - no dd/MM/yyyy -> ISO conversion logic in jsCode
# ---------------------------------------------------------------------------
$datePatterns = [ordered]@{
    'date token regex (dd/MM/yyyy)' = '(?i)\b[DMYdmy]{1,4}\s*\/\s*[DMYdmy]{1,4}\s*\/\s*[DMYdmy]{2,4}\b'
    'split on /'                   = "\.split\(\s*['""]\/['""]\s*\)"
    'split on -'                   = "\.split\(\s*['""]-['""]\s*\)"
    'getDate/setDate'             = '\.(get|set)(FullYear|Month|Date)\s*\('
    'Date.parse'                   = 'Date\.parse\s*\('
    'toISOString'                  = '\.toISOString\s*\('
    'padStart with date regex'     = '(?s)padStart.{0,120}\\d\{1,4\}.{0,120}\\d\{1,4\}'
}
$dateHits = New-Object System.Collections.Generic.List[string]
foreach ($k in $datePatterns.Keys) {
    foreach ($name in $jsCodeByNode.Keys) {
        $m = [regex]::Matches([string]$jsCodeByNode[$name], $datePatterns[$k])
        if ($m.Count -gt 0) {
            $dateHits.Add(($name + ' -> ' + $k + ' (x' + $m.Count.ToString() + ')'))
        }
    }
}
if ($dateHits.Count -gt 0) {
    Write-Result 'A19' 'FAIL' ("expected no date reordering logic in jsCode; found: " + (($dateHits | Sort-Object -Unique) -join '; '))
}
else {
    Write-Result 'A19' 'PASS' ('no date conversion pattern in the ' + $jsCodeByNode.Count.ToString() + ' jsCode blocks')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A20 - no branching on confidence
# ---------------------------------------------------------------------------
$confidenceFindings = New-Object System.Collections.Generic.List[string]
# Benign form: forwarding confidence through a typeof guard, e.g.
#   typeof n.confidence === 'number' ? n.confidence : null
# That is data transport, not a branch. Everything else is examined.
$guardTypeof = '(?i)typeof\s+[A-Za-z0-9_$\.\[\]]*confidence\s*===?\s*[''"][a-z]+[''"]\s*\?\s*[^:;]*:\s*null'
foreach ($n in $nodes) {
    $name = [string]$n.name
    $type = ''
    if ($n.PSObject.Properties.Name -contains 'type') { $type = [string]$n.type }
    $text = Get-NodeText -Node $n
    if ($text -eq '' -or $text -notmatch '(?i)confidence') { continue }
    $scrubbed = [regex]::Replace($text, $guardTypeof, '')
    if ($scrubbed -notmatch '(?i)confidence') { continue }
    foreach ($m in [regex]::Matches($scrubbed, '(?i)[^\n]{0,200}confidence[^\n]{0,200}')) {
        $line = $m.Value
        $isBranch = $false
        # threshold / equality comparison against a value
        if ($line -match '(?i)confidence\s*(>=|<=|===|!==|>|<)\s*') { $isBranch = $true }
        if ($line -match '(?i)(>=|<=|===|!==|>|<)\s*[\w$\.\[\]]*confidence') { $isBranch = $true }
        # confidence used as the condition of a ternary
        if ($line -match '(?i)[\w$\.\[\]]*confidence[^,;]{0,80}\?\s*') { $isBranch = $true }
        # confidence inside an if/switch/while condition
        if ($line -match '(?i)\b(if|switch|while)\s*\([^)]*confidence') { $isBranch = $true }
        if ($isBranch) {
            $snippet = ($line -replace '\s+', ' ').Trim()
            if ($snippet.Length -gt 140) { $snippet = $snippet.Substring(0, 140) + '...' }
            $confidenceFindings.Add($name + ' -> ' + $snippet)
        }
    }
    if ($type -match '(?i)\.(if|switch|filter)$' -and $text -match '(?i)confidence') {
        $confidenceFindings.Add($name + ' -> confidence referenced inside a branching node of type ' + $type)
    }
}
if ($confidenceFindings.Count -gt 0) {
    Write-Result 'A20' 'FAIL' ("expected confidence to be forwarded only, never branched on; found: " + (($confidenceFindings | Sort-Object -Unique) -join '; '))
}
else {
    Write-Result 'A20' 'PASS' 'confidence appears only as forwarded data, never in a condition or threshold'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A21 - prompt text not embedded, $env.PROMPTS_DIR referenced
# ---------------------------------------------------------------------------
$hasPromptsDir = ($raw -match '\$env\.PROMPTS_DIR')
$prosePatterns = @(
    'You are ',
    'You must ',
    'Return only',
    'return only',
    'Return ONLY',
    'Ignore previous',
    'Ignore all previous',
    'R[eé]ponds uniquement',
    'R[eé]pondez uniquement',
    'Analysez le message',
    'system_prompt\s*:',
    'You are an expert'
)
$proseHits = New-Object System.Collections.Generic.List[string]
foreach ($p in $prosePatterns) {
    foreach ($name in $jsCodeByNode.Keys) {
        $m = [regex]::Matches([string]$jsCodeByNode[$name], $p, 'IgnoreCase')
        if ($m.Count -gt 0) { $proseHits.Add(($name + ' -> "' + $p + '" x' + $m.Count.ToString())) }
    }
}
if (-not $hasPromptsDir) {
    Write-Result 'A21' 'FAIL' 'expected the workflow to load the extraction prompt from $env.PROMPTS_DIR; that reference is absent'
}
elseif ($proseHits.Count -gt 0) {
    Write-Result 'A21' 'FAIL' ("$env.PROMPTS_DIR is referenced but prompt prose is embedded in jsCode: " + (($proseHits | Sort-Object -Unique) -join '; '))
}
else {
    Write-Result 'A21' 'PASS' '$env.PROMPTS_DIR referenced and no prompt instruction prose found in jsCode'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A22 - egress outcome vocabulary is exactly the nine approved values
# ---------------------------------------------------------------------------
$finalizeJs = ''
if ($jsCodeByNode.ContainsKey('FinalizeResponse')) { $finalizeJs = [string]$jsCodeByNode['FinalizeResponse'] }
$respondResponseCode = ''
if ($nodeByName.ContainsKey('RespondToWebhook')) {
    $rp = $nodeByName['RespondToWebhook'].parameters
    if ($rp.PSObject.Properties.Name -contains 'options' -and $rp.options.PSObject.Properties.Name -contains 'responseCode') {
        $respondResponseCode = [string]$rp.options.responseCode
    }
}
$respondOutcomeLits = @()
foreach ($m in [regex]::Matches($finalizeJs + "`n" + $respondResponseCode, "outcome\s*[:=]{1,2}\s*'([A-Z][A-Z0-9_]+)'")) {
    $respondOutcomeLits += $m.Groups[1].Value
}
$mapKeys = @()
foreach ($m in [regex]::Matches($respondResponseCode, '(?m)(?:^|[{,;\s])([A-Z][A-Z0-9_]{3,})\s*:\s*\d{3}')) {
    $mapKeys += $m.Groups[1].Value
}
$declaredApproved = @()
$am = [regex]::Match($finalizeJs, 'APPROVED_OUTCOMES\s*=\s*\[([^\]]*)\]')
if ($am.Success) {
    foreach ($m in [regex]::Matches($am.Groups[1].Value, "'([A-Z][A-Z0-9_]+)'")) { $declaredApproved += $m.Groups[1].Value }
}
$uppercaseLits = @()
foreach ($m in [regex]::Matches($finalizeJs, "'([A-Z][A-Z0-9_]{2,})'")) { $uppercaseLits += $m.Groups[1].Value }
$uppercaseLits = @($uppercaseLits | Sort-Object -Unique)
$violations = New-Object System.Collections.Generic.List[string]
foreach ($lit in ($respondOutcomeLits + $mapKeys | Sort-Object -Unique)) {
    if ($script:ApprovedOutcomes -notcontains $lit) {
        $violations.Add(('emitted/outcome-map literal outside the nine: ' + $lit))
    }
}
$tolerated = New-Object System.Collections.Generic.List[string]
foreach ($lit in $uppercaseLits) {
    if ($script:ApprovedOutcomes -contains $lit) { continue }
    if ($script:ApprovedErrorCodes -contains $lit) { continue }
    if ($script:InternalMarkers -contains $lit -or $script:NonOutcomeLiterals -contains $lit) {
        if ($mapKeys -contains $lit) { $violations.Add(('internal hop marker used as an egress response code: ' + $lit)) }
        elseif ($respondOutcomeLits -contains $lit) { $violations.Add(('internal hop marker assigned to an outcome field: ' + $lit)) }
        else { $tolerated.Add($lit) }
        continue
    }
    if ($declaredApproved -contains $lit) { continue }
    # unknown uppercase literal: can we prove it is not an outcome? no -> tolerate but report
    $tolerated.Add($lit + '(unclassified)')
}
$declMismatch = $false
if ($am.Success) {
    $a = @($declaredApproved | Sort-Object -Unique)
    $b = @($script:ApprovedOutcomes | Sort-Object -Unique)
    if (($a -join ',') -ne ($b -join ',')) {
        $declMismatch = $true
        $violations.Add(('APPROVED_OUTCOMES declared = [' + ($a -join ', ') + '] but must be [' + ($b -join ', ') + ']'))
    }
}
elseif ($finalizeJs -ne '') {
    $declMismatch = $true
    $violations.Add('no APPROVED_OUTCOMES whitelist found in FinalizeResponse jsCode')
}
if ($violations.Count -gt 0) {
    Write-Result 'A22' 'FAIL' (($violations | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A22' 'PASS' ('outcome whitelist is exactly the nine approved values; responseCode map keys = [' + (($mapKeys | Sort-Object -Unique) -join ', ') + ']; non-outcome uppercase literals tolerated: ' + (($tolerated | Sort-Object -Unique) -join ', '))
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A23 - error-code vocabulary is a subset of the six approved codes
# ---------------------------------------------------------------------------
$errViolations = New-Object System.Collections.Generic.List[string]
$declaredCodes = @()
$cm = [regex]::Match($finalizeJs, 'APPROVED_ERROR_CODES\s*=\s*\[([^\]]*)\]')
$hasWhitelist = $cm.Success
if ($cm.Success) {
    foreach ($m in [regex]::Matches($cm.Groups[1].Value, "'([A-Z][A-Z0-9_]+)'")) { $declaredCodes += $m.Groups[1].Value }
    $a = @($declaredCodes | Sort-Object -Unique)
    $b = @($script:ApprovedErrorCodes | Sort-Object -Unique)
    if (($a -join ',') -ne ($b -join ',')) {
        $errViolations.Add(('APPROVED_ERROR_CODES = [' + ($a -join ', ') + '] must be exactly [' + ($b -join ', ') + ']'))
    }
}
$em = [regex]::Match($finalizeJs, 'ERROR_MESSAGES\s*=\s*\{([^}]*)\}')
if ($em.Success) {
    foreach ($m in [regex]::Matches($em.Groups[1].Value, '(?m)^\s*([A-Z][A-Z0-9_]{3,})\s*:')) {
        if ($script:ApprovedErrorCodes -notcontains $m.Groups[1].Value) {
            $errViolations.Add(('ERROR_MESSAGES key outside the six approved codes: ' + $m.Groups[1].Value))
        }
    }
}
# Case-sensitive on purpose: an uppercase-only literal assigned to a code slot is
# what must be checked. Indirection through a named constant is resolved separately.
$errAssignments = @(
    '(?i)\b(?:errorCode|code)\b\s*[:=]\s*''([A-Z][A-Z0-9_]*)''',
    '\bAPPROVED[A-Z0-9_]*CODE[A-Z0-9_]*\s*=\s*''([A-Z][A-Z0-9_]*)''',
    '\bCODE\s*=\s*''([A-Z][A-Z0-9_]*)'''
)
foreach ($name in $jsCodeByNode.Keys) {
    foreach ($pat in $errAssignments) {
        foreach ($m in [regex]::Matches([string]$jsCodeByNode[$name], $pat)) {
            $lit = $m.Groups[1].Value
            if ($script:ApprovedErrorCodes -notcontains $lit) {
                $errViolations.Add(($name + ' -> error-code literal outside the six approved codes: ' + $lit))
            }
        }
    }
}
if (-not $hasWhitelist) {
    $errViolations.Add('no approved error-code whitelist (APPROVED_ERROR_CODES) found in FinalizeResponse jsCode')
}
if ($errViolations.Count -gt 0) {
    Write-Result 'A23' 'FAIL' (($errViolations | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A23' 'PASS' ('approved error-code whitelist = [' + (($declaredCodes | Sort-Object -Unique) -join ', ') + ']; no other error-code literal found')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A24 - bounded clarification policy: MAX_CLARIFICATION_ROUNDS = 5 + static data
# ---------------------------------------------------------------------------
$maxConstFound = $false
$maxConstValue = $null
$staticFound = $false
$maxNode = ''
foreach ($name in $jsCodeByNode.Keys) {
    $code = [string]$jsCodeByNode[$name]
    $m = [regex]::Match($code, 'MAX_CLARIFICATION_ROUNDS\s*=\s*(\d+)')
    if ($m.Success) { $maxConstFound = $true; $maxConstValue = $m.Groups[1].Value; $maxNode = $name }
    if ($code -match '\$getWorkflowStaticData\s*\(') { $staticFound = $true }
}
if (-not $maxConstFound) {
    Write-Result 'A24' 'FAIL' 'expected a MAX_CLARIFICATION_ROUNDS constant in a jsCode node; none found'
}
elseif ($maxConstValue -ne '5') {
    Write-Result 'A24' 'FAIL' ('expected MAX_CLARIFICATION_ROUNDS = 5; found = ' + $maxConstValue + ' in node ' + $maxNode)
}
elseif (-not $staticFound) {
    Write-Result 'A24' 'FAIL' 'MAX_CLARIFICATION_ROUNDS = 5 found but $getWorkflowStaticData is not used to persist the counter'
}
else {
    Write-Result 'A24' 'PASS' ('MAX_CLARIFICATION_ROUNDS = 5 in ' + $maxNode + ' and $getWorkflowStaticData persists the counter')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A25 - no PII literals / test fixtures
# ---------------------------------------------------------------------------
$piiViolations = New-Object System.Collections.Generic.List[string]
$exampleDomains = 'example\.(com|org|net|test)|localhost|invalid|domain\.(com|test)'
foreach ($m in [regex]::Matches($raw, '[\w\.\+\-]+@[\w\-]+(?:\.[\w\-]+)+')) {
    $em2 = $m.Value
    if ($em2 -match "(?i)$exampleDomains") { continue }
    $piiViolations.Add(('email literal: ' + $em2))
}
foreach ($m in [regex]::Matches($raw, '(?<![0-9])[0-9][0-9\-\. ]{7,}[0-9](?![0-9])')) {
    $digits = ($m.Value -replace '[^0-9]', '')
    if ($digits.Length -ge 9) { $piiViolations.Add(('long digit run (' + $digits.Length.ToString() + ' digits): ' + $m.Value)) }
}
foreach ($name in @('Maria', 'Gomes', 'Gomez')) {
    $m = [regex]::Matches($raw, '(?i)\b' + $name + '\b')
    if ($m.Count -gt 0) { $piiViolations.Add(('requirement example name "' + $name + '" x' + $m.Count.ToString())) }
}
foreach ($p in @('\b\d{1,3}[- ]?\d{2}[- ]?\d{2}[- ]?\d{2}[- ]?\d{4}\b', '(?i)\bpassport\s*[:=#]')) {
    foreach ($m in [regex]::Matches($raw, $p)) {
        $piiViolations.Add(('passport/id-like literal: ' + ($m.Value -replace '\s+', ' ')))
    }
}
if ($piiViolations.Count -gt 0) {
    Write-Result 'A25' 'FAIL' (($piiViolations | Sort-Object -Unique | Select-Object -First 8) -join '; ')
}
else {
    Write-Result 'A25' 'PASS' 'no email, no 9+ digit run, no passport/id pattern, no Maria/Gomes/Gomez fixture in the file'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A26 - every HTTP node uses $env base URL and sets X-Correlation-Id
# ---------------------------------------------------------------------------
$a26 = New-Object System.Collections.Generic.List[string]
foreach ($n in $httpNodes) {
    $name = [string]$n.name
    $url = ''
    if ($n.parameters.PSObject.Properties.Name -contains 'url') { $url = [string]$n.parameters.url }
    if ($url -notmatch '\$env\.(BACKEND_BASE_URL|OLLAMA_BASE_URL)') {
        $a26.Add(($name + ': url does not use $env.BACKEND_BASE_URL or $env.OLLAMA_BASE_URL -> ' + $url))
    }
    $headerNames = New-Object System.Collections.Generic.List[string]
    if ($n.parameters.PSObject.Properties.Name -contains 'headerParameters' -and $null -ne $n.parameters.headerParameters) {
        $hp = @($n.parameters.headerParameters.parameters)
        foreach ($h in $hp) {
            if ($null -ne $h -and ($h.PSObject.Properties.Name -contains 'name')) { $headerNames.Add(([string]$h.name).ToLowerInvariant()) }
        }
    }
    if (-not $headerNames.Contains('x-correlation-id')) {
        $a26.Add(($name + ': missing X-Correlation-Id request header'))
    }
}
if ($httpNodes.Count -eq 0) {
    Write-Result 'A26' 'FAIL' 'no HTTP node found to inspect'
}
elseif ($a26.Count -gt 0) {
    Write-Result 'A26' 'FAIL' (($a26 | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A26' 'PASS' ('all ' + $httpNodes.Count.ToString() + ' HTTP nodes use an $env base URL and send X-Correlation-Id')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A27 - E6 response-body retention: honest deferral + provable sub-assertions
# ---------------------------------------------------------------------------
# HONESTY RULE. The n8n HTTP Request node cannot be PROVEN to discard a response
# body. E6VerifyDocument is configured responseFormat 'text', which RETAINS the DOCX
# body in the n8n execution data, so "no DOCX in the execution data" is NOT statically
# provable. The controlling evidence is the gate I-B scan of the execution data for the
# PK\x03\x04 (ZIP) signature (N8N_CONTRACTS 6 / I.0 SEC-6). A27 therefore always
# reports NOT_STATICALLY_PROVABLE, EXCEPT when a statically PROVABLE violation exists
# (binary output configuration, or FinalizeResponse reading .data/.binary/.body of the
# E6 item), which is reported as FAIL.
$a27 = New-Object System.Collections.Generic.List[string]
$provable27 = New-Object System.Collections.Generic.List[string]
if (-not $nodeByName.ContainsKey('E6VerifyDocument')) {
    $a27.Add('E6VerifyDocument node not found')
}
else {
    $e6 = $nodeByName['E6VerifyDocument']
    $e6Text = Get-NodeText -Node $e6
    if ($e6Text -match '(?i)"?binary"?\s*[:=]\s*(true|"[a-z0-9]+"|\d)' -or $e6Text -match '(?i)putOutputData|storeBinary') {
        $a27.Add('E6VerifyDocument configures a binary output / stores the response body')
    }
    $fmt = [regex]::Match($e6Text, '(?i)responseFormat"?\s*:\s*"?([a-z]+)"?')
    $observedFmt = 'unset'
    if ($fmt.Success) {
        $observedFmt = $fmt.Groups[1].Value
        if ($observedFmt -match '(?i)file|stream|binary') {
            $a27.Add('E6VerifyDocument responseFormat is ' + $observedFmt + ' (binary body kept)')
        }
        else {
            $provable27.Add(('no binary/file/stream responseFormat (observed responseFormat="' + $observedFmt + '")'))
        }
    }
    else { $provable27.Add('no responseFormat key on E6VerifyDocument') }
}
if ($finalizeJs -ne '') {
    $e6Touched = $false
    foreach ($m in [regex]::Matches($finalizeJs, '(?is)[^\n]{0,120}E6VerifyDocument[^\n]{0,200}')) {
        $line = $m.Value
        if ($line -match '(?i)\.(data|binary|body)\b') {
            $snippet = ($line -replace '\s+', ' ').Trim()
            if ($snippet.Length -gt 140) { $snippet = $snippet.Substring(0, 140) + '...' }
            $a27.Add('FinalizeResponse touches the E6 body: ' + $snippet)
            $e6Touched = $true
        }
    }
    if ($finalizeJs -match "(?is)(nodeJson|itemJson|first|last)\s*\(\s*'E6VerifyDocument'\s*\)\s*\.\s*(data|binary|body)\b") {
        $a27.Add('FinalizeResponse reads .data/.binary/.body of the E6VerifyDocument item')
        $e6Touched = $true
    }
    if (-not $e6Touched) { $provable27.Add('FinalizeResponse never reads .data/.binary/.body of the E6VerifyDocument item') }
}
if ($a27.Count -gt 0) {
    Write-Result 'A27' 'FAIL' (('statically provable violation(s): ' + ($a27 | Sort-Object -Unique)) -join '; ')
}
else {
    $reason = 'NOT statically provable that the DOCX is absent from the n8n execution data: the HTTP Request node offers no provable "discard body" behaviour and E6VerifyDocument is configured responseFormat "' + $observedFmt + '", which RETAINS the body in the execution data. Controlling evidence = gate I-B scan of the execution data for the PK\x03\x04 ZIP signature (N8N_CONTRACTS 6 / I.0 SEC-6), not a static assertion. Provable sub-assertions that DO hold: ' + (($provable27 | Sort-Object -Unique) -join '; ')
    Write-Result 'A27' 'NOT_STATICALLY_PROVABLE' $reason
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A28 - graph acyclicity (mandatory: no directed cycle may exist)
# ---------------------------------------------------------------------------
# Rationale: the B1 blocker (unbounded resubmission cycle
# CheckClarificationBound -> CreateOrContinue -> E1|E2 -> E3 -> E4 -> IFRequestComplete
# -> CheckClarificationBound) passed a previous gate because no acyclicity check
# existed. A28 traverses the connection graph from EVERY node and FAILS on the first
# directed cycle found (grey-neighbour back edge, iterative DFS).
$adj = @{}
foreach ($r in $refs) {
    if (-not $adj.ContainsKey($r.Source)) { $adj[$r.Source] = New-Object System.Collections.Generic.List[string] }
    if (-not $adj[$r.Source].Contains($r.Target)) { $adj[$r.Source].Add($r.Target) }
}

function Get-DirectedCyclePath {
    param([hashtable]$Adjacency, [string[]]$NodeNames)
    $colour = @{}
    foreach ($n in $NodeNames) { $colour[$n] = 0 }   # 0 white, 1 grey (on stack), 2 black (done)
    foreach ($root in $NodeNames) {
        if ($colour[$root] -ne 0) { continue }
        $colour[$root] = 1
        $rootEdges = @()
        if ($Adjacency.ContainsKey($root)) { $rootEdges = @($Adjacency[$root]) }
        $stack = New-Object System.Collections.Generic.List[object]
        $stack.Add([pscustomobject]@{ Node = $root; Edges = $rootEdges; Index = 0 })
        while ($stack.Count -gt 0) {
            $frame = $stack[$stack.Count - 1]
            $edges = @($frame.Edges)
            if ($frame.Index -lt $edges.Count) {
                $next = [string]$edges[$frame.Index]
                $frame.Index = $frame.Index + 1
                if (-not $colour.ContainsKey($next)) { continue }   # dangling endpoint: A07/A08 own it
                if ($colour[$next] -eq 1) {
                    $path = New-Object System.Collections.Generic.List[string]
                    foreach ($f in $stack) { $path.Add([string]$f.Node) }
                    $path.Add($next)
                    return ($path -join ' -> ')
                }
                if ($colour[$next] -eq 0) {
                    $colour[$next] = 1
                    $nextEdges = @()
                    if ($Adjacency.ContainsKey($next)) { $nextEdges = @($Adjacency[$next]) }
                    $stack.Add([pscustomobject]@{ Node = $next; Edges = $nextEdges; Index = 0 })
                }
            }
            else {
                $colour[$frame.Node] = 2
                $stack.RemoveAt($stack.Count - 1)
            }
        }
    }
    return $null
}

$cyclePath = Get-DirectedCyclePath -Adjacency $adj -NodeNames $names
if ($null -ne $cyclePath) {
    Write-Result 'A28' 'FAIL' ("directed cycle detected: " + $cyclePath + " (an execution could revisit these nodes, which is forbidden: no internal resubmission loop, I.0 11.3 R5)")
}
else {
    Write-Result 'A28' 'PASS' ("no directed cycle: iterative DFS from all " + $names.Count.ToString() + " nodes over " + $refs.Count.ToString() + " edges; therefore E1/E2/E3/E4 each execute at most once per execution and no internal resubmission loop can occur")
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A29 - 4 conformance: timeout, retry and the R2 bound on the E5 attempts
# ---------------------------------------------------------------------------
# Two dimensions, reported separately and never conflated (correction cycle 2, C2,
# completed by the human rulings R2 and R3):
#
#  HARD (A29, PASS/FAIL) - what the workflow JSON PROVES:
#    * the timeout of EVERY HTTP stage, including the two recovery stages of R2,
#      must equal the value of the 4 table. This dimension is never deferred;
#    * for every stage whose 4 retry prescription is a plain bounded blanket
#      retry, retryOnFail MUST be true and maxTries MUST equal the 4 value;
#    * the 4 table binds E5 to 2 attempts per execution, and n8n CAN express that
#      bound: it is a property of the GRAPH and of the runtime counter, not of a
#      boolean. A29 therefore asserts
#        - exactly TWO nodes call the generate endpoint (the initial call and the
#          single retry), so no third generation attempt exists anywhere;
#        - NEITHER of them sets retryOnFail=true: a blanket retry would multiply
#          the attempts (2 x 2 = 4) and silently break the 4 bound;
#        - the attempt counter is real: e5Attempted is initialised by
#          MergeContext, incremented by AdoptE4Recovery just before the retry
#          call, and compared with MAX_E5_ATTEMPTS = 2 by PrepareE5Recovery;
#        - the retry node's own notes name the recovery chain (M1).
#      A previous revision tolerated retryOnFail=true on E5 as a "compliant
#      variant" because it meant "2 attempts". With the R2 recovery chain that
#      shape is exactly the one that yields 3 and 4 attempts, so it is now a
#      FAIL. The E1 flag stays unconstrained (see below): there is no recovery
#      chain multiplying E1 attempts, so no bound can be broken by the flag.
#    * E1CreateRequest is the one stage whose 4 prescription ("retry only on a
#      RECEIVED 5xx, never on a timeout") n8n cannot express AND that is not
#      implemented (deferred to I.1-B). Neither flag value is enforced there;
#      the M1 documentation rule is, and a declared maxTries must still match 4.
#
#  DEFERRED (A29-E1-DEFERRED, NOT_STATICALLY_PROVABLE):
#    the E1 conditionality alone. It has no structural counterpart in the
#    workflow precisely because the E1 retry is deliberately not implemented, and
#    the runtime proof belongs to gate I-B. The E5 conditionality is NO LONGER
#    deferred: it is now structural (a received 5xx carrying a generation cause
#    is routed to PrepareE5Recovery by RouteMutatingFailure, a timeout is not)
#    and is asserted in A29/A35.
$retryTable = @(
    @{ Node = 'OllamaExtract'; Timeout = 'expr:AI_TIMEOUT_MS|60000'; Tries = 'expr:AI_MAX_ATTEMPTS'; Retry = 'hard' },
    @{ Node = 'E8ValidateExtraction'; Timeout = '10000'; Tries = '2'; Retry = 'hard' },
    @{ Node = 'E1CreateRequest'; Timeout = '10000'; Tries = '2'; Retry = 'conditional' },
    @{ Node = 'E2GetRequest'; Timeout = '5000'; Tries = '3'; Retry = 'hard' },
    @{ Node = 'E3PatchRequest'; Timeout = '10000'; Tries = '2'; Retry = 'hard' },
    @{ Node = 'E4ValidateRequest'; Timeout = '10000'; Tries = '3'; Retry = 'hard' },
    @{ Node = 'E4ReconcileValidate'; Timeout = '10000'; Tries = '3'; Retry = 'hard' },
    @{ Node = 'E5GenerateDocument'; Timeout = '15000'; Tries = '2'; Retry = 'bounded-recovery' },
    @{ Node = 'E5RetryGenerate'; Timeout = '15000'; Tries = '1'; Retry = 'none' },
    @{ Node = 'E6VerifyDocument'; Timeout = '10000'; Tries = '3'; Retry = 'hard' },
    @{ Node = 'E2ReconcileRequest'; Timeout = '5000'; Tries = '3'; Retry = 'hard' }
)
$script:DeferredDimensions = New-Object System.Collections.Generic.List[string]
$a29 = New-Object System.Collections.Generic.List[string]
$seen29 = New-Object System.Collections.Generic.List[string]
foreach ($row in $retryTable) {
    $n = $nodeByName[$row.Node]
    if ($null -eq $n) { $a29.Add(($row.Node + ': node not found')); continue }
    $seen29.Add($row.Node)
    $timeout = $null
    if ($n.parameters.PSObject.Properties.Name -contains 'options' -and $null -ne $n.parameters.options -and $n.parameters.options.PSObject.Properties.Name -contains 'timeout') {
        $timeout = $n.parameters.options.timeout
    }
    $timeoutText = [string]$timeout
    if ($row.Timeout -like 'expr:*') {
        $needles = @($row.Timeout.Substring(5) -split '\|')
        foreach ($needle in $needles) {
            if ($timeoutText -notmatch [regex]::Escape($needle)) { $a29.Add(($row.Node + ': timeout does not bind the expected default ' + $needle + ' (4) -> ' + $timeoutText)) }
        }
    }
    else {
        $tries2 = $false
        if ($timeout -is [int] -or $timeout -is [long] -or $timeout -is [double]) {
            if ([string][int]$timeout -eq $row.Timeout) { $tries2 = $true }
        }
        elseif ($timeoutText -match ('(?<![\d.])' + [regex]::Escape($row.Timeout) + '(?![\d.])')) { $tries2 = $true }
        if (-not $tries2) { $a29.Add(($row.Node + ': timeout must be ' + $row.Timeout + ' ms (4); found ' + $timeoutText)) }
    }
    $hasRetry = ($n.PSObject.Properties.Name -contains 'retryOnFail') -and ($n.retryOnFail -eq $true)
    $hasTries = ($n.PSObject.Properties.Name -contains 'maxTries') -and ($null -ne $n.maxTries)
    $note = ''
    if ($n.PSObject.Properties.Name -contains 'notes') { $note = [string]$n.notes }
    if ($row.Retry -eq 'hard') {
        if (-not $hasRetry) { $a29.Add(($row.Node + ': retryOnFail must be true (4 prescribes a bounded blanket retry on 5xx/timeout)')) }
        if ($row.Tries -like 'expr:*') {
            $needle = $row.Tries.Substring(5)
            if (-not $hasTries -or ([string]$n.maxTries) -notmatch [regex]::Escape($needle)) { $a29.Add(($row.Node + ': maxTries must bind ' + $needle + ' (4)')) }
        }
        elseif (-not $hasTries -or ([string]$n.maxTries) -ne $row.Tries) {
            $a29.Add(($row.Node + ': maxTries must be ' + $row.Tries + ' total attempts (4); found ' + $(if ($hasTries) { [string]$n.maxTries } else { '<absent>' })))
        }
    }
    elseif ($row.Retry -eq 'none') {
        # Last attempt of the chain: n8n may not re-try it by itself, so the total
        # number of generate calls stays provably 2 (see the R2 assertions below).
        if ($hasRetry) { $a29.Add(($row.Node + ': retryOnFail must NOT be true: this is the LAST generate attempt (4 binds 2 in total, R2) and a blanket retry would allow 3 and 4')) }
        if ($hasTries -and ([string]$n.maxTries) -ne '1') {
            $a29.Add(($row.Node + ': maxTries must be absent or 1 (4 binds 2 attempts TOTAL for the generation stage, R2); found ' + [string]$n.maxTries))
        }
    }
    elseif ($row.Retry -eq 'bounded-recovery') {
        # E5 initial attempt. The 4 count of 2 is delivered by the recovery chain,
        # so a blanket retry here would double it: the flag is now decidable.
        if ($hasRetry) { $a29.Add(($row.Node + ': retryOnFail must NOT be true: the R2 recovery chain already delivers the 2 attempts of the 4 table (initial + E5RetryGenerate), so a blanket retry would allow 3 and 4')) }
        if ($hasTries -and ([string]$n.maxTries) -ne '1') {
            $a29.Add(($row.Node + ': maxTries must be absent or 1; the 4 count of 2 is delivered by PrepareE5Recovery -> E4ReconcileValidate -> AdoptE4Recovery -> E5RetryGenerate (R2); found ' + [string]$n.maxTries))
        }
        if ($note -eq '') {
            $a29.Add(($row.Node + ': the node MUST carry notes documenting the bounded R2 recovery (why n8n cannot retry the stage itself); the notes field is empty (M1)'))
        }
        elseif ($note -notmatch 'PrepareE5Recovery' -or $note -notmatch 'E5RetryGenerate') {
            $a29.Add(($row.Node + ': the notes must name the bounded recovery chain (PrepareE5Recovery then E5RetryGenerate), otherwise the 4 count of 2 is unverifiable (M1)'))
        }
    }
    else {
        # E1: the 4 retry prescription ("RECEIVED 5xx only, never a timeout") has no
        # structural counterpart yet because the conditional retry is deferred to
        # I.1-B. Neither flag value is enforced; what is enforced is the M1
        # documentation obligation, in both shapes, and the sanity of a bounded
        # count whenever a retry is declared.
        if ($note -eq '') {
            $a29.Add(($row.Node + ': the 4 retry prescription is not expressible in n8n, so the node MUST carry notes documenting the deviation; the notes field is empty (M1)'))
        }
        elseif ($note -notmatch 'I\.1-B') {
            $a29.Add(($row.Node + ': the notes must record the deferral to I.1-B (why n8n cannot express the 4 retry prescription)'))
        }
        if ($hasRetry) {
            $tryCount = 0
            $parsedTry = 0
            if ($hasTries -and [int]::TryParse([string]$n.maxTries, [ref]$parsedTry)) { $tryCount = $parsedTry }
            if ($tryCount -lt 1) {
                $a29.Add(($row.Node + ': retryOnFail=true requires an integer maxTries >= 1; found ' + $(if ($hasTries) { [string]$n.maxTries } else { '<absent>' })))
            }
            elseif ($tryCount -ne [int]$row.Tries) {
                $a29.Add(($row.Node + ': retryOnFail=true must carry maxTries=' + $row.Tries + ' (4); found ' + $tryCount.ToString()))
            }
        }
        $script:DeferredDimensions.Add(($row.Node + ': the conditionality of its retry (a RECEIVED 5xx only, never a timeout) has no structural counterpart in the workflow because the E1 conditional retry is deferred to I.1-B, and the runtime proof belongs to gate I-B'))
    }
}
foreach ($n in $httpNodes) {
    $nm = [string]$n.name
    if (-not $seen29.Contains($nm)) { $a29.Add(($nm + ': HTTP node not present in the 4 conformance table')) }
}

# ---------------------------------------------------------------------------
# R2 (human ruling): the 4 table binds E5 to 2 attempts per execution. That bound
# is a property of the GRAPH and of the runtime counter, so it is asserted here
# instead of being reported as non-expressible. Four groups of assertions:
#   1. exactly two nodes call the generate endpoint (no third attempt exists);
#   2. the retry node terminates on failure (checked here, not only in A35, since
#      it is what makes the count provable rather than declared);
#   3. the counter is real: initialised, incremented, compared to the bound;
#   4. no generate node may re-try itself.
# ---------------------------------------------------------------------------
function Get-SourceRefsOf {
    param([string]$Target)
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($r in $script:AllRefs) {
        if ($r.Target -eq $Target) { $result.Add([string]$r.Source) }
    }
    return @($result)
}
function Get-TargetsRaw {
    param([string]$Source, [int]$Index)
    $result = @()
    foreach ($r in $script:AllRefs) {
        if ($r.Source -eq $Source -and $r.Index -eq $Index) { $result += [string]$r.Target }
    }
    return @($result)
}

$generateNodes = New-Object System.Collections.Generic.List[string]
foreach ($n in $httpNodes) {
    $u = ''
    if ($n.PSObject.Properties.Name -contains 'parameters' -and $null -ne $n.parameters -and $n.parameters.PSObject.Properties.Name -contains 'url') { $u = [string]$n.parameters.url }
    if ($u -match '/generate\b') { $generateNodes.Add([string]$n.name) }
}
if ($generateNodes.Count -ne 2) {
    $a29.Add('expected exactly TWO nodes calling the generate endpoint (the initial call and the single R2 retry, 4 binds 2 attempts); found ' + $generateNodes.Count.ToString() + ': [' + ($generateNodes -join ', ') + ']')
}
else {
    foreach ($requiredGen in @('E5GenerateDocument', 'E5RetryGenerate')) {
        if ($generateNodes -notcontains $requiredGen) { $a29.Add('the two generate nodes must be E5GenerateDocument and E5RetryGenerate; found [' + ($generateNodes -join ', ') + ']') }
    }
}
$retryErrTargets = @(Get-TargetsRaw -Source 'E5RetryGenerate' -Index 1)
if ($retryErrTargets.Count -eq 0) {
    $a29.Add('E5RetryGenerate error output is not wired: a failed 2nd attempt would leave the execution without a response (C5)')
}
elseif ($retryErrTargets -contains 'E2ReconcileRequest' -or $retryErrTargets -contains 'E4ReconcileValidate' -or $retryErrTargets -contains 'PrepareE5Recovery') {
    $a29.Add('E5RetryGenerate error output reaches [' + ($retryErrTargets -join ', ') + ']: the last E5 attempt must terminate at FinalizeResponse, never re-enter a guard (4 binds 2 attempts, R2)')
}
foreach ($genNode in @('E5GenerateDocument', 'E5RetryGenerate')) {
    $gn = $nodeByName[$genNode]
    if ($null -ne $gn -and $gn.PSObject.Properties.Name -contains 'retryOnFail' -and $gn.retryOnFail -eq $true) {
        $a29.Add($genNode + ': retryOnFail=true would make the 4 bound of 2 E5 attempts unverifiable and breakable (R2)')
    }
}

$mergeJs = [string]$script:JsCodeByNode['MergeContext']
if ($mergeJs -eq '') { $a29.Add('MergeContext jsCode not found: the R2 attempt counter e5Attempted cannot be initialised') }
elseif ($mergeJs -notmatch 'e5Attempted\s*:\s*0') {
    $a29.Add('MergeContext must initialise e5Attempted to 0 (R2): without an honest initial value the recovery guard could refuse or repeat the 2nd attempt')
}
$adoptRecoveryJs = [string]$script:JsCodeByNode['AdoptE4Recovery']
if ($adoptRecoveryJs -eq '') { $a29.Add('AdoptE4Recovery jsCode not found: the R2 attempt counter e5Attempted cannot be incremented') }
elseif ($adoptRecoveryJs -notmatch 'e5Attempted\s*=\s*spent\s*\+\s*1') {
    $a29.Add('AdoptE4Recovery must increment e5Attempted to spent + 1 just before the retry call (R2): the counter must be real, not recomputed from nothing')
}
$prepareJs = [string]$script:JsCodeByNode['PrepareE5Recovery']
if ($prepareJs -eq '') { $a29.Add('PrepareE5Recovery jsCode not found: the R2 attempt bound cannot be enforced') }
else {
    if ($prepareJs -notmatch 'MAX_E5_ATTEMPTS\s*=\s*2\b') {
        $a29.Add('PrepareE5Recovery must bind MAX_E5_ATTEMPTS = 2 (4, line E5): the bound must be a value in the workflow, not an implicit assumption')
    }
    if ($prepareJs -notmatch 'e5Attempted\s*<\s*MAX_E5_ATTEMPTS') {
        $a29.Add('PrepareE5Recovery must REFUSE the recovery when e5Attempted < MAX_E5_ATTEMPTS is false: without the comparison a 3rd generate attempt is reachable (R2)')
    }
    if ($prepareJs -notmatch 'received5xx' -or $prepareJs -notmatch 'RECOVERABLE_CAUSES') {
        $a29.Add('PrepareE5Recovery must require BOTH a received 5xx and a generation cause: 4 authorizes the recovery on a RECEIVED 5xx carrying TEMPLATE_NOT_FOUND or DOCUMENT_GENERATION_ERROR, never on a timeout')
    }
}
$prepPreds = @(Get-SourceRefsOf -Target 'E4ReconcileValidate')
foreach ($expectedPred in @('PrepareE5Recovery', 'AdoptE2Reconcile')) {
    if ($prepPreds -notcontains $expectedPred) { $a29.Add('E4ReconcileValidate must be reachable from ' + $expectedPred + ' (the R2 recovery guard and the R6 resume guard); predecessors: [' + ($prepPreds -join ', ') + ']') }
}
$retryPreds = @(Get-SourceRefsOf -Target 'E5RetryGenerate')
if ($retryPreds -notcontains 'AdoptE4Recovery') {
    $a29.Add('E5RetryGenerate must be fed by AdoptE4Recovery (the only E5 attempt allowed after the first); predecessors: [' + ($retryPreds -join ', ') + ']')
}
$e5Preds = @(Get-SourceRefsOf -Target 'E5GenerateDocument')
if ($e5Preds -notcontains 'IFRequestComplete') {
    $a29.Add('E5GenerateDocument must be fed by IFRequestComplete as before; predecessors: [' + ($e5Preds -join ', ') + ']')
}
if ($a29.Count -gt 0) {
    Write-Result 'A29' 'FAIL' (($a29 | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A29' 'PASS' ('hard dimension: all ' + $seen29.Count.ToString() + ' HTTP stages match the 4 table on timeout; the ' + (@($retryTable | Where-Object { $_.Retry -eq 'hard' }).Count).ToString() + ' stages whose 4 retry prescription is a blanket retry match on retryOnFail=true + maxTries; the R2 bound is structural: exactly two generate nodes (' + ($generateNodes -join ', ') + '), neither with retryOnFail=true, the retry error terminating at FinalizeResponse, e5Attempted initialised by MergeContext, incremented by AdoptE4Recovery and compared to MAX_E5_ATTEMPTS=2 by PrepareE5Recovery; the 1 stage whose 4 retry prescription is still non-expressible (E1CreateRequest, deferred to I.1-B) accepts either flag value and only requires the M1 notes')
}
if ($script:DeferredDimensions.Count -gt 0) {
    $script:DEFERRED++
    Write-Output 'A29-E1-DEFERRED NOT_STATICALLY_PROVABLE'
    Write-Output ('      evidence: ' + (($script:DeferredDimensions | Sort-Object -Unique) -join '; '))
    Write-Output '      evidence: only E1CreateRequest remains deferred; the E5 conditionality is now proved structurally (a received 5xx with a generation cause is routed to PrepareE5Recovery by RouteMutatingFailure, a timeout is not) so it is asserted, not deferred'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A30 - 9 env whitelist: no n8n-internal, database or process.env reference
# ---------------------------------------------------------------------------
$envForbidden = [ordered]@{
    'n8n internal variable' = '\$env\.N8N_'
    'postgres variable'      = '\$env\.POSTGRES'
    'process.env'            = 'process\.env'
}
$a30 = New-Object System.Collections.Generic.List[string]
foreach ($k in $envForbidden.Keys) {
    $m = [regex]::Matches($raw, $envForbidden[$k])
    if ($m.Count -gt 0) { $a30.Add(($k + ' referenced ' + $m.Count.ToString() + 'x e.g. "' + $m[0].Value + '"')) }
}
$envUsed = New-Object System.Collections.Generic.List[string]
foreach ($m in [regex]::Matches($raw, '\$env\.([A-Z][A-Z0-9_]*)')) {
    if (-not $envUsed.Contains($m.Groups[1].Value)) { $envUsed.Add($m.Groups[1].Value) }
}
if ($a30.Count -gt 0) {
    Write-Result 'A30' 'FAIL' (($a30 | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A30' 'PASS' ('no $env.N8N_*, $env.POSTGRES_* or process.env reference; allowed environment surface = ' + (($envUsed | Sort-Object) -join ', '))
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A31 - Webhook responseMode is responseNode (explicit response, not lastNode)
# ---------------------------------------------------------------------------
if (-not $nodeByName.ContainsKey('Webhook')) {
    Write-Result 'A31' 'FAIL' 'Webhook node not found'
}
else {
    $wm = ''
    $wp = $nodeByName['Webhook'].parameters
    if ($wp.PSObject.Properties.Name -contains 'responseMode') { $wm = [string]$wp.responseMode }
    if ($wm -ne 'responseNode') {
        Write-Result 'A31' 'FAIL' ("expected Webhook responseMode=responseNode so every path emits its own envelope; found '" + $wm + "'")
    }
    else {
        Write-Result 'A31' 'PASS' 'Webhook responseMode=responseNode'
    }
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A32 - settings.executionTimeout = 900 (4 EXECUTIONS_TIMEOUT)
# ---------------------------------------------------------------------------
if (-not ($topKeys -contains 'settings') -or $null -eq $wf.settings) {
    Write-Result 'A32' 'FAIL' 'no settings object in the workflow'
}
elseif (-not ($wf.settings.PSObject.Properties.Name -contains 'executionTimeout')) {
    Write-Result 'A32' 'FAIL' 'settings.executionTimeout is absent; expected 900 (4 EXECUTIONS_TIMEOUT)'
}
elseif ([string]$wf.settings.executionTimeout -ne '900') {
    Write-Result 'A32' 'FAIL' ('expected settings.executionTimeout=900 (4 EXECUTIONS_TIMEOUT, one round = one execution); found ' + [string]$wf.settings.executionTimeout)
}
else {
    Write-Result 'A32' 'PASS' 'settings.executionTimeout=900'
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A33 - the version-controlled extraction prompt asset is referenced by path
# ---------------------------------------------------------------------------
# Read-only check of the referenced FILENAME. The asset itself is resolved at runtime
# from $env.PROMPTS_DIR (A21) and its existence is NOT required statically.
$promptAsset = 'system_prompt_attestation_concordance.md'
$promptRefs = [regex]::Matches($raw, [regex]::Escape($promptAsset))
if ($promptRefs.Count -eq 0) {
    Write-Result 'A33' 'FAIL' ("expected the workflow to reference the version-controlled prompt asset " + $promptAsset + "; no reference found")
}
else {
    $owner = New-Object System.Collections.Generic.List[string]
    foreach ($name in $jsCodeByNode.Keys) {
        if ([string]$jsCodeByNode[$name] -match [regex]::Escape($promptAsset)) { $owner.Add($name) }
    }
    Write-Result 'A33' 'PASS' ('prompt asset referenced ' + $promptRefs.Count.ToString() + 'x by [' + (($owner | Sort-Object) -join ', ') + '] as a path under $env.PROMPTS_DIR (read-only filename check; the file need not exist at validation time)')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A34 - workflow file is UTF-8 WITHOUT BOM
# ---------------------------------------------------------------------------
$bytes = $null
try { $bytes = [System.IO.File]::ReadAllBytes($WorkflowPath) } catch { $bytes = $null }
if ($null -eq $bytes -or $bytes.Length -eq 0) {
    Write-Result 'A34' 'FAIL' 'could not read the raw bytes of the workflow file'
}
elseif ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    Write-Result 'A34' 'FAIL' 'the workflow file starts with a UTF-8 BOM (EF BB BF); a BOM breaks n8n expression parsing and diffs, UTF-8 without BOM is required'
}
else {
    $strictUtf8 = $null
    $decodeOk = $true
    try { $strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true) } catch { $decodeOk = $false }
    $decoded = $null
    if ($decodeOk) {
        try { $decoded = $strictUtf8.GetString($bytes) } catch { $decoded = $null }
        if ($null -eq $decoded) { $decodeOk = $false }
    }
    if (-not $decodeOk) {
        Write-Result 'A34' 'FAIL' 'the workflow file is not valid strict UTF-8 (invalid byte sequence)'
    }
    else {
        Write-Result 'A34' 'PASS' ('strict UTF-8, no BOM (' + $bytes.Length.ToString() + ' bytes, first bytes ' + (($bytes[0..2] | ForEach-Object { $_.ToString('X2') }) -join ' ') + ')')
    }
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A35 - E2 status routing (R6), the read-only reconciliation path and the bounded
#       E5 recovery chain (R2) - correction cycle 2 C1, completed by the human
#       rulings R2 and R6
# ---------------------------------------------------------------------------
# Proves, on the connection graph, on the switch definitions and on the Code node
# bodies only:
#   * the E2 read is routed by a status switch instead of an unconditional PATCH;
#   * MISSING_INFORMATION is the ONLY status allowed to reach E3PatchRequest (R6):
#     no second PATCH branch may exist, and the rule that owns E3PatchRequest must
#     not mention DRAFT, VALIDATED or FAILED;
#   * VALIDATED / DRAFT / FAILED are routed to the E4 guard (AdoptE2Reconcile
#     output 1 -> E4ReconcileValidate -> E5RetryGenerate): an E4-then-E5 resume,
#     never a mutation of the request (3.3, R6);
#   * that continuation is open ONLY to the resume read: AdoptE2Reconcile must
#     test E2ReconcileRequest before opening it, so a reconciliation of a 409 /
#     DATABASE_ERROR / timeout still resolves read-only (T14, 3.2 line 176). A
#     static reach-set exclusion is IMPOSSIBLE here (the resume edge shares the
#     node), so the guard itself is asserted;
#   * GENERATED and REJECTED, both terminal, can never reach E3PatchRequest;
#   * a GENERATED already held by the backend reaches E6VerifyDocument before any
#     outcome can be declared (R3), and reaches it through the same adapter as the
#     reconciliation read;
#   * the R2 chain is wired exactly once and terminates: a failure of a mutating
#     stage is routed to the read-only E2 reconciliation node, EXCEPT a received
#     5xx carrying a generation cause, which goes to the bounded recovery guard
#     (RouteMutatingFailure output 0 -> PrepareE5Recovery -> E4ReconcileValidate
#     -> AdoptE4Recovery -> E5RetryGenerate -> FinalizeResponse on error).
$a35 = New-Object System.Collections.Generic.List[string]

function Get-TargetsOf {
    param([string]$Source, [int]$Index)
    $result = @()
    foreach ($r in $script:AllRefs) {
        if ($r.Source -eq $Source -and $r.Index -eq $Index) { $result += [string]$r.Target }
    }
    return @($result)
}

function Get-NodeReachSet {
    param([string]$Start)
    $seen = New-Object System.Collections.Generic.HashSet[string]
    $queue = New-Object 'System.Collections.Generic.Queue[string]'
    $queue.Enqueue($Start)
    while ($queue.Count -gt 0) {
        $cur = [string]$queue.Dequeue()
        foreach ($nxt in Get-TargetsOf -Source $cur -Index 0) {
            if ($seen.Add($nxt)) { $queue.Enqueue($nxt) }
        }
    }
    return $seen
}

function Get-RuleConditions {
    param([string]$NodeName)
    $result = New-Object System.Collections.Generic.List[string]
    $node = $script:NodeByName[$NodeName]
    if ($null -eq $node) { return $result }
    $rules = @()
    if ($null -ne $node.parameters -and $node.parameters.PSObject.Properties.Name -contains 'rules' -and $null -ne $node.parameters.rules) {
        $rules = @($node.parameters.rules.values)
    }
    foreach ($rule in $rules) {
        $conds = @()
        if ($null -ne $rule -and $rule.PSObject.Properties.Name -contains 'conditions' -and $null -ne $rule.conditions) {
            $conds = @($rule.conditions.conditions)
        }
        foreach ($c in $conds) { $result.Add([string]$c.leftValue) }
    }
    return $result
}

function Get-SourceRefsNamed {
    param([string]$Target)
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($r in $script:AllRefs) {
        if ($r.Target -eq $Target) { $result.Add([string]$r.Source) }
    }
    return @($result)
}

$terminalStates = @('GENERATED', 'REJECTED')
$resumeStates = @('VALIDATED', 'DRAFT', 'FAILED')

if (-not $script:NodeByName.ContainsKey('RouteE2Status')) {
    $a35.Add('RouteE2Status is missing: the E2 read must be routed on the backend status, not patched unconditionally (C1)')
}
else {
    $r2Node = $script:NodeByName['RouteE2Status']
    $conds = @(Get-RuleConditions -NodeName 'RouteE2Status')
    foreach ($st in $terminalStates) {
        $hit = @($conds | Where-Object { $_ -match [regex]::Escape($st) })
        if ($hit.Count -eq 0) { $a35.Add(("RouteE2Status has no rule testing status === '" + $st + "' (3.3 terminal state)")) }
    }
    foreach ($st in $resumeStates) {
        $hit = @($conds | Where-Object { $_ -match [regex]::Escape($st) })
        if ($hit.Count -eq 0) { $a35.Add(("RouteE2Status has no rule covering the resumable status '" + $st + "' (3.3, R6)")) }
    }
    $r2Rules = @()
    if ($null -ne $r2Node.parameters -and $r2Node.parameters.PSObject.Properties.Name -contains 'rules' -and $null -ne $r2Node.parameters.rules) { $r2Rules = @($r2Node.parameters.rules.values) }
    if ($r2Rules.Count -lt 5) {
        $a35.Add('RouteE2Status declares ' + $r2Rules.Count.ToString() + ' rule(s): 3.3/R6 requires 5 (GENERATED, VALIDATED+documents, REJECTED, MISSING_INFORMATION, VALIDATED-sans-documents/DRAFT/FAILED) plus the fallback output')
    }
    # R6: MISSING_INFORMATION is the single PATCH-eligible status, and it is the only
    # rule allowed to reach E3PatchRequest.
    $patchRules = New-Object System.Collections.Generic.List[int]
    for ($i = 0; $i -lt $r2Rules.Count; $i++) {
        $t = @(Get-TargetsOf -Source 'RouteE2Status' -Index $i)
        if ($t -contains 'E3PatchRequest') { $patchRules.Add($i) }
    }
    if ($patchRules.Count -ne 1) {
        $a35.Add('exactly ONE RouteE2Status output may feed E3PatchRequest (MISSING_INFORMATION, R6); found ' + $patchRules.Count.ToString() + ' (rule indexes: ' + (($patchRules | ForEach-Object { $_.ToString() }) -join ', ') + ')')
    }
    else {
        $patchRule = $r2Rules[$patchRules[0]]
        $patchConds = @($patchRule.conditions.conditions)
        $patchText = ''
        foreach ($pc in $patchConds) { $patchText += ' ' + [string]$pc.leftValue }
        if ($patchText -notmatch "MISSING_INFORMATION") {
            $a35.Add('the RouteE2Status rule feeding E3PatchRequest does not test MISSING_INFORMATION; found: ' + $patchText.Trim())
        }
        foreach ($forbidden in $resumeStates) {
            if ($patchText -match [regex]::Escape($forbidden)) {
                $a35.Add("the RouteE2Status rule feeding E3PatchRequest also tests '" + $forbidden + "': R6 allows a PATCH for MISSING_INFORMATION only, a VALIDATED/DRAFT/FAILED status must go through E4 then E5")
            }
        }
    }
    # R6: the resume rule must open the E4 guard, not a PATCH.
    $resumeIdx = -1
    for ($i = 0; $i -lt $r2Rules.Count; $i++) {
        if ([string]$r2Rules[$i].outputKey -eq 'E2_RESUME_E4_GUARD') { $resumeIdx = $i }
    }
    if ($resumeIdx -lt 0) {
        $a35.Add('RouteE2Status has no E2_RESUME_E4_GUARD output: VALIDATED / DRAFT / FAILED must be routed to the E4 guard (3.3, R6), not dropped to FinalizeResponse and not PATCHed')
    }
    else {
        $rt = @(Get-TargetsOf -Source 'RouteE2Status' -Index $resumeIdx)
        if ($rt -notcontains 'AdoptE2Reconcile') { $a35.Add('RouteE2Status E2_RESUME_E4_GUARD output must feed AdoptE2Reconcile (which owns the E4 continuation); found [' + ($rt -join ', ') + ']') }
        $rtext = ''
        foreach ($rc in @($r2Rules[$resumeIdx].conditions.conditions)) { $rtext += ' ' + [string]$rc.leftValue }
        foreach ($st in $resumeStates) {
            if ($rtext -notmatch [regex]::Escape($st)) { $a35.Add("the E2_RESUME_E4_GUARD rule does not test '" + $st + "' (3.3, R6); found: " + $rtext.Trim()) }
        }
    }
    # Terminal / generated / fallback outputs keep their documented targets.
    $genIdx = -1
    $withDocsIdx = -1
    $rejectedIdx = -1
    for ($i = 0; $i -lt $r2Rules.Count; $i++) {
        $key = [string]$r2Rules[$i].outputKey
        if ($key -eq 'E2_GENERATED_TERMINAL') { $genIdx = $i }
        if ($key -eq 'E2_VALIDATED_WITH_DOCUMENTS') { $withDocsIdx = $i }
        if ($key -eq 'E2_REJECTED_TERMINAL') { $rejectedIdx = $i }
    }
    if ($genIdx -lt 0 -or $withDocsIdx -lt 0 -or $rejectedIdx -lt 0) {
        $a35.Add('RouteE2Status must keep the E2_GENERATED_TERMINAL, E2_VALIDATED_WITH_DOCUMENTS and E2_REJECTED_TERMINAL outputs (3.3)')
    }
    else {
        foreach ($pair in @(@($genIdx, 'E2_GENERATED_TERMINAL'), @($withDocsIdx, 'E2_VALIDATED_WITH_DOCUMENTS'))) {
            $t = @(Get-TargetsOf -Source 'RouteE2Status' -Index $pair[0])
            if ($t -notcontains 'AdoptE2Reconcile') { $a35.Add($pair[1] + ' must feed AdoptE2Reconcile (the document already exists -> E6); found [' + ($t -join ', ') + ']') }
        }
        $rt2 = @(Get-TargetsOf -Source 'RouteE2Status' -Index $rejectedIdx)
        if ($rt2 -notcontains 'FinalizeResponse') { $a35.Add('E2_REJECTED_TERMINAL must feed FinalizeResponse (terminal, never verified nor patched); found [' + ($rt2 -join ', ') + ']') }
    }
    $fallbackTargets = @(Get-TargetsOf -Source 'RouteE2Status' -Index $r2Rules.Count)
    if ($fallbackTargets -notcontains 'FinalizeResponse') {
        $a35.Add('RouteE2Status fallback output (index ' + $r2Rules.Count.ToString() + ') must feed FinalizeResponse: an unknown status is neither copied as a success nor mutated; found [' + ($fallbackTargets -join ', ') + ']')
    }
    for ($i = 0; $i -lt $r2Rules.Count; $i++) {
        $t = @(Get-TargetsOf -Source 'RouteE2Status' -Index $i)
        if ($t -contains 'E3PatchRequest' -and $r2Rules[$i].outputKey -ne 'E2_MISSING_INFORMATION_ONLY') {
            $a35.Add('RouteE2Status output ' + $i.ToString() + ' (' + [string]$r2Rules[$i].outputKey + ') reaches E3PatchRequest but is not MISSING_INFORMATION (R6)')
        }
    }
    if ($fallbackTargets -contains 'E3PatchRequest') {
        $a35.Add('RouteE2Status fallback output reaches E3PatchRequest: an unknown status must never be mutated (3.3, T14)')
    }
}

if (-not $script:NodeByName.ContainsKey('AdoptE2Reconcile')) {
    $a35.Add('AdoptE2Reconcile is missing: the already-GENERATED metadata read must be adopted in exactly one place (R3)')
}
else {
    $g0 = @(Get-TargetsOf -Source 'AdoptE2Reconcile' -Index 0)
    $g1 = @(Get-TargetsOf -Source 'AdoptE2Reconcile' -Index 1)
    $g2 = @(Get-TargetsOf -Source 'AdoptE2Reconcile' -Index 2)
    if ($g0 -notcontains 'E6VerifyDocument') { $a35.Add('AdoptE2Reconcile output 0 must feed E6VerifyDocument: GENERATED may only be declared after the 200 E6 (R3); found [' + ($g0 -join ', ') + ']') }
    if ($g1 -notcontains 'E4ReconcileValidate') { $a35.Add('AdoptE2Reconcile output 1 must feed E4ReconcileValidate: a VALIDATED / DRAFT / FAILED resume read is completed by E4 then E5 (3.3, R6); found [' + ($g1 -join ', ') + ']') }
    if ($g2 -notcontains 'FinalizeResponse') { $a35.Add('AdoptE2Reconcile output 2 must feed FinalizeResponse; found [' + ($g2 -join ', ') + ']') }
    $adoptReach = Get-NodeReachSet -Start 'AdoptE2Reconcile'
    if ($adoptReach.Contains('E3PatchRequest')) { $a35.Add('E3PatchRequest is reachable downstream of AdoptE2Reconcile: an E2 read must never mutate the request (T14)') }
    # R6 + T14: the E4 continuation must be closed for a RECONCILIATION read. The guard is
    # asserted on the Code body because the reach set cannot distinguish the two reads.
    $adoptJs = [string]$script:JsCodeByNode['AdoptE2Reconcile']
    if ($adoptJs -eq '') { $a35.Add('AdoptE2Reconcile jsCode not found: the resume-only guard on the E4 continuation cannot be checked') }
    else {
        if ($adoptJs -notmatch "E2ReconcileRequest") {
            $a35.Add('AdoptE2Reconcile never references E2ReconcileRequest: the E4 continuation must be closed for a reconciliation read (T14, 3.2 line 176)')
        }
        if ($adoptJs -notmatch 'isExecuted') {
            $a35.Add('AdoptE2Reconcile must test whether E2ReconcileRequest executed before opening the E4 continuation; otherwise a reconciliation of a 409 / DATABASE_ERROR / timeout would mutate the request (T14)')
        }
        foreach ($st in $resumeStates) {
            if ($adoptJs -notmatch [regex]::Escape($st)) { $a35.Add('AdoptE2Reconcile does not mention the resumable status ' + $st + ': the E4 continuation whitelist must be explicit, not implicit (R6)') }
        }
        if ($adoptJs -notmatch 'e5Attempted') {
            $a35.Add('AdoptE2Reconcile must carry the R2 counter e5Attempted on the continuation item, otherwise the recovery guard cannot prove that this E5 attempt is the FIRST one')
        }
    }
}

if (-not $script:NodeByName.ContainsKey('E2ReconcileRequest')) {
    $a35.Add('E2ReconcileRequest is missing: a 409 / DATABASE_ERROR / timeout of a mutating stage must be resolved by an E2 read (4 lines 388/390, 11.3 R2)')
}
else {
    $rc = $script:NodeByName['E2ReconcileRequest']
    $rcUrl = ''
    if ($null -ne $rc.parameters -and $rc.parameters.PSObject.Properties.Name -contains 'url') { $rcUrl = [string]$rc.parameters.url }
    if ($rcUrl -notmatch '/api/v1/requests/') { $a35.Add('E2ReconcileRequest must read the request resource GET /api/v1/requests/{{...}}; found ' + $rcUrl) }
    if ($rcUrl -notmatch '(\{\{|\{\{)') { $a35.Add('E2ReconcileRequest must read the request resource by requestId, not a literal id') }
    $rcMethod = ''
    if ($null -ne $rc.parameters -and $rc.parameters.PSObject.Properties.Name -contains 'method') { $rcMethod = [string]$rc.parameters.method }
    if ($rcMethod.ToUpperInvariant() -ne 'GET') { $a35.Add('E2ReconcileRequest must be a read-only GET; found method=' + $(if ($rcMethod -eq '') { '<absent>' } else { $rcMethod })) }
    $rs0 = @(Get-TargetsOf -Source 'E2ReconcileRequest' -Index 0)
    $rs1 = @(Get-TargetsOf -Source 'E2ReconcileRequest' -Index 1)
    if ($rs0 -notcontains 'AdoptE2Reconcile') { $a35.Add('E2ReconcileRequest success must feed AdoptE2Reconcile; found [' + ($rs0 -join ', ') + ']') }
    if ($rs1 -notcontains 'FinalizeResponse') { $a35.Add('E2ReconcileRequest error must feed FinalizeResponse; found [' + ($rs1 -join ', ') + ']') }
    $reconReach = Get-NodeReachSet -Start 'E2ReconcileRequest'
    if ($reconReach.Contains('E3PatchRequest')) { $a35.Add('E3PatchRequest is reachable downstream of E2ReconcileRequest: a reconciliation must never mutate the request (T14)') }
}

if (-not $script:NodeByName.ContainsKey('RouteMutatingFailure')) {
    $a35.Add('RouteMutatingFailure is missing: the error output of E3/E4/E5 must be classified instead of answering directly (C1/C3)')
}
else {
    foreach ($stage in @('E3PatchRequest', 'E4ValidateRequest', 'E5GenerateDocument')) {
        $st1 = @(Get-TargetsOf -Source $stage -Index 1)
        if ($st1 -notcontains 'RouteMutatingFailure') {
            $a35.Add($stage + ' error output must feed RouteMutatingFailure (409 / DATABASE_ERROR / timeout are reconciliation signals, R1/R2); found [' + ($st1 -join ', ') + ']')
        }
    }
    $mf0 = @(Get-TargetsOf -Source 'RouteMutatingFailure' -Index 0)
    $mf1 = @(Get-TargetsOf -Source 'RouteMutatingFailure' -Index 1)
    $mf2 = @(Get-TargetsOf -Source 'RouteMutatingFailure' -Index 2)
    $mf3 = @(Get-TargetsOf -Source 'RouteMutatingFailure' -Index 3)
    if ($mf0 -notcontains 'PrepareE5Recovery') { $a35.Add('RouteMutatingFailure output 0 (received 5xx with a generation cause, R2) must feed PrepareE5Recovery; found [' + ($mf0 -join ', ') + ']') }
    if ($mf1 -notcontains 'E2ReconcileRequest') { $a35.Add('RouteMutatingFailure output 1 (409/DATABASE_ERROR/received status) must feed E2ReconcileRequest; found [' + ($mf1 -join ', ') + ']') }
    if ($mf2 -notcontains 'E2ReconcileRequest') { $a35.Add('RouteMutatingFailure output 2 (timeout, n8n must never decide on a timeout, R1) must feed E2ReconcileRequest; found [' + ($mf2 -join ', ') + ']') }
    if ($mf3 -notcontains 'FinalizeResponse') { $a35.Add('RouteMutatingFailure fallback must feed FinalizeResponse; found [' + ($mf3 -join ', ') + ']') }
    $mfConds = @(Get-RuleConditions -NodeName 'RouteMutatingFailure')
    foreach ($code in @('TEMPLATE_NOT_FOUND', 'DOCUMENT_GENERATION_ERROR', 'DATABASE_ERROR', 'REQUEST_ALREADY_CLOSED', 'INVALID_STATUS')) {
        $hit = @($mfConds | Where-Object { $_ -match [regex]::Escape($code) })
        if ($hit.Count -eq 0) { $a35.Add(("RouteMutatingFailure has no rule testing the code '" + $code + "' (the routing signal of 11.3 R2 / finding F-I-3 / R2 recovery)")) }
    }
    # R2: the recovery rule must be restricted to a RECEIVED 5xx (4 authorizes a replay on a
    # received 5xx, never on a timeout), and the reconciliation rule must EXCLUDE the
    # generation causes so a template/generation failure is not turned into a read loop.
    $mfRules = @()
    $mfNode = $script:NodeByName['RouteMutatingFailure']
    if ($null -ne $mfNode.parameters -and $mfNode.parameters.PSObject.Properties.Name -contains 'rules' -and $null -ne $mfNode.parameters.rules) { $mfRules = @($mfNode.parameters.rules.values) }
    if ($mfRules.Count -lt 3) {
        $a35.Add('RouteMutatingFailure declares ' + $mfRules.Count.ToString() + ' rule(s); the design requires 3 (R2 recovery, reconciliation on a received status, reconciliation on a timeout) plus the fallback output')
    }
    $recoveryCondText = ''
    $reconcileCondText = ''
    for ($i = 0; $i -lt $mfRules.Count; $i++) {
        $key = [string]$mfRules[$i].outputKey
        $txt = ''
        foreach ($mc in @($mfRules[$i].conditions.conditions)) { $txt += ' ' + [string]$mc.leftValue }
        if ($key -eq 'RECOVER_E5_ON_RECEIVED_5XX') { $recoveryCondText = $txt }
        if ($key -eq 'RECONCILE_ON_E2') { $reconcileCondText = $txt }
    }
    if ($recoveryCondText -eq '') {
        $a35.Add('RouteMutatingFailure has no RECOVER_E5_ON_RECEIVED_5XX output: a received 5xx carrying TEMPLATE_NOT_FOUND or DOCUMENT_GENERATION_ERROR must reach the bounded R2 recovery guard')
    }
    else {
        if ($recoveryCondText -notmatch '500') { $a35.Add('the RECOVER_E5_ON_RECEIVED_5XX rule does not test a 5xx status: 4 authorizes the replay only on a RECEIVED 5xx, never on a timeout') }
        if ($recoveryCondText -notmatch 'E5GenerateDocument') { $a35.Add('the RECOVER_E5_ON_RECEIVED_5XX rule does not test that E5GenerateDocument ran: an E4 or E3 error must not enter the generation recovery') }
    }
    if ($reconcileCondText -ne '' -and $reconcileCondText -notmatch 'return false') {
        $a35.Add('the RECONCILE_ON_E2 rule has no exclusion: TEMPLATE_NOT_FOUND / DOCUMENT_GENERATION_ERROR must NOT be reconciled (R2 routes them to the recovery guard, then to 200 GENERATION_FAILED with the verbatim cause)')
    }
}

# R2: the bounded recovery chain is wired exactly once and every edge terminates.
$recoveryChain = @(
    @{ From = 'PrepareE5Recovery'; Index = 0; To = 'E4ReconcileValidate'; Why = 'authorised recovery re-runs the E4 guard (T11)' },
    @{ From = 'PrepareE5Recovery'; Index = 1; To = 'FinalizeResponse'; Why = 'a refused recovery is classified as-is (2nd attempt failure -> 200 GENERATION_FAILED)' },
    @{ From = 'E4ReconcileValidate'; Index = 0; To = 'AdoptE4Recovery'; Why = 'the E4 response of the recovery guard is adopted in one place' },
    @{ From = 'E4ReconcileValidate'; Index = 1; To = 'FinalizeResponse'; Why = 'a failed E4 of the recovery terminates (4: bounded retry then stop)' },
    @{ From = 'AdoptE4Recovery'; Index = 0; To = 'E5RetryGenerate'; Why = 'VALIDATED is the only status allowed to re-run E5' },
    @{ From = 'AdoptE4Recovery'; Index = 1; To = 'FinalizeResponse'; Why = 'any other status of the recovery guard is copied verbatim' },
    @{ From = 'E5RetryGenerate'; Index = 0; To = 'AdoptE5Response'; Why = 'the 201 of the 2nd attempt is adopted like the first one (single metadata source)' },
    @{ From = 'E5RetryGenerate'; Index = 1; To = 'FinalizeResponse'; Why = 'a 3rd attempt is impossible: the error of the last attempt terminates here' }
)
foreach ($edge in $recoveryChain) {
    $t = @(Get-TargetsOf -Source $edge.From -Index $edge.Index)
    if ($t -notcontains $edge.To) {
        $a35.Add(($edge.From + ' output ' + $edge.Index.ToString() + ' must feed ' + $edge.To + ': ' + $edge.Why + '; found [' + ($t -join ', ') + ']'))
    }
    foreach ($other in $t) {
        if ($other -eq $edge.To) { continue }
        $a35.Add(($edge.From + ' output ' + $edge.Index.ToString() + ' also feeds ' + $other + ': an unexpected second consumer on the bounded recovery chain (R2)'))
    }
}
# AdoptE4Recovery must whitelist VALIDATED and nothing else may reach E5RetryGenerate.
$adoptRecoveryJs = [string]$script:JsCodeByNode['AdoptE4Recovery']
if ($adoptRecoveryJs -eq '') {
    $a35.Add('AdoptE4Recovery jsCode not found: the only status allowed to re-run E5 cannot be checked')
}
elseif ($adoptRecoveryJs -notmatch "'VALIDATED'") {
    $a35.Add("AdoptE4Recovery must test status === 'VALIDATED': that is the single status the 4 table allows to re-run E5 after the guard")
}
else {
    foreach ($forbidden in @("'REJECTED'", "'MISSING_INFORMATION'")) {
        if ($adoptRecoveryJs -match ([regex]::Escape($forbidden) + '\s*\)\s*\{')) {
            $a35.Add('AdoptE4Recovery opens E5RetryGenerate on ' + $forbidden + ': only VALIDATED may be re-generated (4, line E5)')
        }
    }
}

# R3 / C4: no GENERATED outcome may be produced outside the E6 branch.
$finalizeBody = [string]$script:JsCodeByNode['FinalizeResponse']
$coIdx = $finalizeBody.IndexOf('const classifyOutcome')
$generatedReturns = @([regex]::Matches($finalizeBody, "return\s+'GENERATED'"))
if ($coIdx -ge 0) {
    $classifyEnd = $finalizeBody.IndexOf('// 3.4 bindings')
    if ($classifyEnd -lt 0) { $classifyEnd = $finalizeBody.Length }
    $classifyBody = $finalizeBody.Substring($coIdx, $classifyEnd - $coIdx)
    $e6GuardIdx = $classifyBody.IndexOf("src === 'E6VerifyDocument'")
    foreach ($m in $generatedReturns) {
        if ($e6GuardIdx -lt 0 -or $m.Index -lt $e6GuardIdx) {
            $a35.Add('classifyOutcome returns GENERATED before the E6 branch (offset ' + $m.Index.ToString() + '): only a 200 E6 may declare GENERATED (R3)')
        }
    }
    if ($classifyBody -notmatch "generationMetadataComplete") {
        $a35.Add('classifyOutcome must gate GENERATED on generationMetadataComplete (documentId + fileName + downloadPath + generatedAt), never on a guessed success (R3)')
    }
    if ($classifyBody -match "persisted\s*===\s*'GENERATED'[^;]*return\s+'GENERATED'") {
        $a35.Add('classifyOutcome re-declares GENERATED from a persisted status: a GENERATED state reached without the E6 200 must be ERROR (R3)')
    }
}
if ($a35.Count -gt 0) {
    Write-Result 'A35' 'FAIL' (($a35 | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A35' 'PASS' ('RouteE2Status routes the E2 read over ' + ($r2Rules.Count + 1).ToString() + ' outputs (GENERATED / VALIDATED+documents / REJECTED / MISSING_INFORMATION / VALIDATED-sans-documents+DRAFT+FAILED / fallback) and exactly ONE output, MISSING_INFORMATION, feeds E3PatchRequest (R6); VALIDATED/DRAFT/FAILED reach E4ReconcileValidate through AdoptE2Reconcile output 1, and that continuation is guarded on the resume read so a reconciliation stays read-only; GENERATED is adopted then verified by E6VerifyDocument and returned only from the E6 branch with complete metadata; E2ReconcileRequest is a read-only GET whose reach set contains no PATCH; RouteMutatingFailure routes the E3/E4/E5 error outputs to the reconciliation read and a received 5xx with a generation cause to the bounded R2 recovery chain PrepareE5Recovery -> E4ReconcileValidate -> AdoptE4Recovery -> E5RetryGenerate, whose error outputs all terminate at FinalizeResponse')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A36 - transport failure vs received status, the DATABASE_ERROR ceiling, and the
#       R1/R2 classification of the bounded E5 recovery (correction cycle 2, C3)
# ---------------------------------------------------------------------------
# 3.2 reserves BACKEND_UNAVAILABLE / 503 for a genuine ACCESS failure. A RECEIVED
# 5xx is never "backend down" (M2, 4 line 388, 6 line 16), and DATABASE_ERROR is a
# business/technical code of the backend that n8n must never re-emit as an approved
# error code. R2 adds the last dimension: a RECEIVED 5xx that carries a generation
# cause is 200 GENERATION_FAILED with the cause copied verbatim (3.2 line 171), so
# that rule must be evaluated BEFORE the hard "any received status -> ERROR" guard,
# and the recovery stages must be classified like the stages they retry.
$a36 = New-Object System.Collections.Generic.List[string]

function Get-EnclosingIfCondition {
    param([string]$Body, [int]$Index)
    $prefix = $Body.Substring(0, $Index)
    $lastIf = $prefix.LastIndexOf('if (')
    if ($lastIf -lt 0) { return '' }
    $open = $lastIf + 3
    $depth = 0
    for ($i = $open; $i -lt $prefix.Length; $i++) {
        if ($Body[$i] -eq '(' -and $i -lt $prefix.Length) {
            if ($i -eq $open) { $depth = 1; continue }
            $depth++
        }
        elseif ($Body[$i] -eq ')') {
            $depth--
            if ($depth -eq 0) { return $Body.Substring($open + 1, $i - $open - 1) }
        }
    }
    return ''
}

$fj = [string]$script:JsCodeByNode['FinalizeResponse']
if ($fj -eq '') {
    $a36.Add('FinalizeResponse jsCode not found')
}
else {
    $rIdx = $fj.IndexOf('const resolveErrorCode')
    if ($rIdx -lt 0) { $a36.Add('FinalizeResponse does not define resolveErrorCode') }
    else {
        $rEnd = $fj.IndexOf('const correlationIdOf')
        if ($rEnd -lt 0) { $rEnd = $fj.Length }
        $resolveBody = $fj.Substring($rIdx, $rEnd - $rIdx)
        if ($resolveBody -notmatch 'accessFailure') {
            $a36.Add('resolveErrorCode must distinguish a genuine access failure (accessFailure) from a received status before choosing a code (C3)')
        }
        $buReturns = @([regex]::Matches($resolveBody, "return\s+'BACKEND_UNAVAILABLE'"))
        if ($buReturns.Count -eq 0) {
            $a36.Add('resolveErrorCode never returns BACKEND_UNAVAILABLE: a bounded access failure must still answer 503 (3.2 line 179)')
        }
        foreach ($m in $buReturns) {
            # The enclosing condition must be a POSITIVE accessFailure test. A window
            # search over the previous characters accepts '!accessFailure' or
            # 'accessFailure || status >= 500', both of which answer 503 on a
            # RECEIVED 5xx (the exact defect of M2), so the condition itself is read.
            $cond = Get-EnclosingIfCondition -Body $resolveBody -Index $m.Index
            if ($cond -notmatch '\baccessFailure\b') {
                $a36.Add('a `return BACKEND_UNAVAILABLE` statement is not guarded by accessFailure: a RECEIVED 5xx must map to INTERNAL_ERROR 500 (M2, 4 line 388); enclosing condition: ' + $cond.Trim())
            }
            elseif ($cond -match '!' -or $cond -match '\|\|' -or $cond -match '(?i)status|http|received|error') {
                $a36.Add('the condition guarding `return BACKEND_UNAVAILABLE` also tests something else than a pure access failure (found: ' + $cond.Trim() + '): 503 is reserved for an access failure with NO received status (M2, 3.2 line 179)')
            }
        }
        if ($resolveBody -match "return\s+'DATABASE_ERROR'" -or $resolveBody -match "code\s*=\s*'DATABASE_ERROR'") {
            $a36.Add("resolveErrorCode may not produce DATABASE_ERROR: it is not one of the six approved error codes (3.4)")
        }
        if ($resolveBody -match "UNAVAILABLE[^\n]{0,40}DATABASE_ERROR|DATABASE_ERROR[^\n]{0,40}UNAVAILABLE") {
            $a36.Add('resolveErrorCode couples DATABASE_ERROR with an unavailable/503 verdict: a received 500 DATABASE_ERROR is an optimistic-locking conflict (F-I-3) and must be reconciled, never answered 503 (4 line 388, 6 line 16)')
        }
        if ($resolveBody -notmatch "RECEIVED_STATUS_NOT_ROUTED|UNHANDLED_BACKEND_STATUS") {
            $a36.Add('resolveErrorCode must record the unrouted received status in a structured log instead of guessing (3.2 line 194)')
        }
    }
    # R2: the recovery stages must be classified exactly like the stage they retry.
    foreach ($alias in @(@('E5RetryGenerate', 'E5GenerateDocument'), @('E4ReconcileValidate', 'E4ValidateRequest'))) {
        if ($fj -notmatch ($alias[0] + [regex]::Escape(": '" + $alias[1] + "'"))) {
            $a36.Add(('FinalizeResponse must alias ' + $alias[0] + ' onto ' + $alias[1] + ' (STAGE_ALIAS): without it a failure of the R2 recovery stage would miss the 5e GENERATION_FAILED rule and the E4 rules, and would be answered 500/503 instead (R2, 3.2 line 171)'))
        }
    }
    $causesIdx = $fj.IndexOf('GENERATION_CAUSES = [')
    $causesEnd = $fj.IndexOf(']', $causesIdx)
    $causesText = ''
    if ($causesIdx -ge 0 -and $causesEnd -gt $causesIdx) { $causesText = $fj.Substring($causesIdx, $causesEnd - $causesIdx) }
    foreach ($cause in @('TEMPLATE_NOT_FOUND', 'DOCUMENT_GENERATION_ERROR')) {
        if ($causesText -notmatch [regex]::Escape($cause)) {
            $a36.Add(('GENERATION_CAUSES must keep ' + $cause + ': the 4 table allows the backend to return it from E5, and 3.2 line 171 requires the verbatim cause on GENERATION_FAILED'))
        }
    }
    $genRuleIdx = $fj.IndexOf("src === 'E5GenerateDocument'")
    # The HARD guard is the standalone statement of step (6); the identical text also appears
    # inline inside the E2 branches, so it is matched on its own line.
    $hardGuardIdx = $fj.IndexOf("`n  if (receivedFailure) return 'ERROR'")
    if ($genRuleIdx -lt 0) {
        $a36.Add("classifyOutcome has no rule for the E5 stage carrying a generation cause: a RECEIVED 5xx with TEMPLATE_NOT_FOUND / DOCUMENT_GENERATION_ERROR must be 200 GENERATION_FAILED (3.2 line 171), not 500")
    }
    elseif ($hardGuardIdx -lt 0) {
        $a36.Add("classifyOutcome has no standalone 'if (receivedFailure) return ERROR' guard (6): a received 4xx/5xx could then be answered as a 200 outcome (C4)")
    }
    elseif ($genRuleIdx -gt $hardGuardIdx) {
        $a36.Add("the GENERATION_FAILED rule for the E5 stage is evaluated AFTER the 'any received status -> ERROR' guard: a RECEIVED 5xx carrying a generation cause would then answer 500 instead of 200 GENERATION_FAILED (3.2 line 171)")
    }
}
if ($a36.Count -gt 0) {
    Write-Result 'A36' 'FAIL' (($a36 | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A36' 'PASS' ('BACKEND_UNAVAILABLE is returned only from a condition that tests accessFailure alone, so a RECEIVED status can never be answered 503 (M2, 4 line 388, 3.2 line 179); every received status that is not routed maps to INTERNAL_ERROR 500 plus a structured log; DATABASE_ERROR is never emitted as an error code and never mapped to 503 (M2, 4 line 388, 6 line 16); the GENERATION_FAILED rule for a received 5xx carrying TEMPLATE_NOT_FOUND / DOCUMENT_GENERATION_ERROR is evaluated before the hard receivedFailure guard, and the R2 recovery stages are aliased onto the stages they retry, so the 2nd and last E5 attempt is answered 200 GENERATION_FAILED with the verbatim cause')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# A37 - precedence order, fail-fast short-circuits, no fabricated status, no dead
#       branch and no dead response header (correction cycle 2, C4, C5, C6)
# ---------------------------------------------------------------------------
$a37 = New-Object System.Collections.Generic.List[string]
if ($fj -eq '') { $a37.Add('FinalizeResponse jsCode not found') }
else {
    $coIdx2 = $fj.IndexOf('const classifyOutcome')
    $end2 = $fj.IndexOf('// 3.4 bindings')
    if ($end2 -lt 0) { $end2 = $fj.Length }
    if ($coIdx2 -lt 0) { $a37.Add('FinalizeResponse does not define classifyOutcome') }
    else {
        $cb = $fj.Substring($coIdx2, $end2 - $coIdx2)
        $techIdx = $cb.IndexOf('receivedFailure')
        $busIdx = $cb.IndexOf("pickString('status')")
        if ($techIdx -lt 0) { $a37.Add('classifyOutcome must read the transport/HTTP evidence (receivedFailure) (C4)') }
        if ($busIdx -lt 0) { $a37.Add('classifyOutcome must read the persisted status for the business routing (3.3)') }
        if ($techIdx -ge 0 -and $busIdx -ge 0 -and $busIdx -lt $techIdx) {
            $a37.Add('classifyOutcome reads the business status before the technical evidence: the technical failure must take precedence (C4)')
        }
        if ($cb -notmatch 'if\s*\(\s*receivedFailure\s*\)\s*return\s+.ERROR') {
            $a37.Add('classifyOutcome must carry a hard guard mapping any remaining received 4xx/5xx or transport failure to ERROR before the business routing (C4)')
        }
        if ($cb -match "valid\s*===\s*false[^;]{0,80}MISSING_INFORMATION") {
            $a37.Add('classifyOutcome maps a refused extraction to MISSING_INFORMATION: a refused extraction for a never-created request is a technical refusal, never a clarification loop (3.2 line 178)')
        }
        if ($cb -match "orchestratorFailure\(\)\s*===\s*null" -and $cb.IndexOf("incoming.outcome") -lt 0) {
            $a37.Add('classifyOutcome must honour an approved outcome already carried by the item before any stage evidence (C5 short-circuit)')
        }
    }
}

# C5: the three orchestration Code nodes must fail fast on their own, with no HTTP
# call, no LLM call and no persistence after their fault output.
foreach ($orch in @('LoadPrompt', 'ParseExtraction', 'MergeContext')) {
    $src0 = @(Get-TargetsOf -Source $orch -Index 0)
    $src1 = @(Get-TargetsOf -Source $orch -Index 1)
    if ($src1.Count -eq 0) { $a37.Add($orch + ' has a single output: an orchestration fault would fall through to the next stage instead of stopping (C5, A8 fail-fast)') }
    elseif ($src1 -notcontains 'FinalizeResponse') {
        $a37.Add($orch + ' fault output must go straight to FinalizeResponse (A8: zero HTTP call after an orchestration fault); found [' + ($src1 -join ', ') + ']')
    }
    $stageNames = @($script:HttpNodeNames) + @('OllamaExtract')
    foreach ($t in $src1) {
        if ($stageNames -contains $t) { $a37.Add($orch + ' fault output reaches the stage ' + $t + ': an orchestration fault must stop the execution before any backend or LLM call (C5, A8)') }
    }
    $oj = [string]$script:JsCodeByNode[$orch]
    if ($oj -eq '') { $a37.Add($orch + ' jsCode not found') }
    elseif ($oj -notmatch 'return\s*\[\s*\[\s*\]\s*,\s*\[') {
        $a37.Add($orch + ' must return an explicit [[success], [fault]] two-output shape so a fault cannot be mistaken for a success item (C5)')
    }
}

# C6: no fabricated backend status at the clarification bound.
$ccb = [string]$script:JsCodeByNode['CheckClarificationBound']
if ($ccb -eq '') { $a37.Add('CheckClarificationBound jsCode not found') }
elseif ($ccb -match "status\s*:\s*'MISSING_INFORMATION'") {
    $a37.Add("CheckClarificationBound fabricates status='MISSING_INFORMATION': this gate runs BEFORE any E1/E2/E3/E4 call, so no backend status is known yet and none may be invented (3.2 verbatim rule)")
}

# C6 / R2: the two recovery Code nodes are adapters, not decision makers. Neither may
# write a business status or an outcome: a VALIDATED that the backend did not return,
# or an outcome invented on the recovery path, would be a fabricated success (3.2).
foreach ($adapter in @('PrepareE5Recovery', 'AdoptE4Recovery')) {
    $ajs = [string]$script:JsCodeByNode[$adapter]
    if ($ajs -eq '') { $a37.Add($adapter + ' jsCode not found') }
    else {
        if ($ajs -match "(?m)^\s*merged\.status\s*=" -or $ajs -match "status\s*:\s*'(GENERATED|VALIDATED|REJECTED|MISSING_INFORMATION|GENERATION_FAILED|FAILED)'") {
            $a37.Add($adapter + " assigns a backend status literal: it is an adapter on the verbatim rule, it may only read the status the backend returned (3.2, C6)")
        }
        if ($ajs -match "outcome\s*[:=]\s*'([A-Z][A-Z0-9_]+)'") {
            $a37.Add($adapter + " assigns an outcome: the outcome is decided by classifyOutcome from the evidence, never by an orchestration adapter (3.4, C5)")
        }
    }
}

# C6: no unreachable rule left in the E8 discrimination switch, and no dangling
# output index.
$ife = $script:NodeByName['IFExtractionUsable']
$ifeTargets = New-Object System.Collections.Generic.List[string]
foreach ($i in 0, 1, 2, 3) { foreach ($t in Get-TargetsOf -Source 'IFExtractionUsable' -Index $i) { $ifeTargets.Add([string]$t) } }
if (@(Get-TargetsOf -Source 'IFExtractionUsable' -Index 2).Count -gt 0) {
    $a37.Add('IFExtractionUsable output 2 is wired but the switch now has 1 rule plus one fallback output (2 outputs): a dangling output index (C6)')
}
$ifeConds = @(Get-RuleConditions -NodeName 'IFExtractionUsable')
$ifeRules = @()
if ($null -ne $ife -and $null -ne $ife.parameters -and $ife.parameters.PSObject.Properties.Name -contains 'rules') { $ifeRules = @($ife.parameters.rules.values) }
if ($ifeRules.Count -eq 0) { $a37.Add('IFExtractionUsable declares no rule: E8 acceptance would be decided by the fallback alone (C6)') }
foreach ($rule in $ifeRules) {
    $conds = @()
    if ($null -ne $rule.conditions) { $conds = @($rule.conditions.conditions) }
    foreach ($c in $conds) {
        $lv = [string]$c.leftValue
        if ($lv -match 'ERR_DOCUMENT_TYPE_NON_SUPPORTE' -and $lv -match 'errors') {
            $a37.Add("IFExtractionUsable still carries the rule testing errors[] for ERR_DOCUMENT_TYPE_NON_SUPPORTE: ExtractionController answers 400 whenever valid === false, so that body always arrives on the E8 ERROR output and the rule is unreachable (C6)")
        }
    }
}
if ($ifeConds.Count -eq 0 -or @($ifeConds | Where-Object { $_ -match 'valid' }).Count -eq 0) {
    $a37.Add("IFExtractionUsable must keep the acceptance rule on valid (E8) (C6)")
}
if ($fj -notmatch 'hasUnsupportedCode') {
    $a37.Add('FinalizeResponse must keep hasUnsupportedCode: the unsupported document type is discriminated on the E8 ERROR output (C6)')
}

# C6: the Webhook response header was dead code (the ingress item is empty there).
$webhookNode = $script:NodeByName['Webhook']
if ($null -ne $webhookNode -and $null -ne $webhookNode.parameters -and $null -ne $webhookNode.parameters.options) {
    if ($webhookNode.parameters.options.PSObject.Properties.Name -contains 'responseHeaders') {
        $a37.Add('Webhook options still carry responseHeaders: at webhook time $json is the raw body, so an X-Correlation-Id header cannot be set there; the header is emitted by RespondToWebhook (C6)')
    }
}
if ($a37.Count -gt 0) {
    Write-Result 'A37' 'FAIL' (($a37 | Sort-Object -Unique) -join '; ')
}
else {
    Write-Result 'A37' 'PASS' ('technical evidence is read before the business status and a hard receivedFailure guard precedes the business routing; LoadPrompt/ParseExtraction/MergeContext each expose an explicit [[success],[fault]] shape whose fault output reaches FinalizeResponse and no stage; CheckClarificationBound fabricates no status; PrepareE5Recovery and AdoptE4Recovery fabricate neither a backend status nor an outcome on the R2 recovery path; IFExtractionUsable keeps only its reachable acceptance rule and has no dangling output; the Webhook carries no dead responseHeaders')
}
Assert-Deadline

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
$total = $script:PASSED + $script:FAILED + $script:DEFERRED
$outcome = if ($script:FAILED -gt 0) { 'FAIL' } else { 'PASS' }
Write-Output ''
Write-Output 'SUMMARY'
Write-Output ('  checks run   : ' + $total.ToString())
Write-Output ('  PASSED       : ' + $script:PASSED.ToString())
Write-Output ('  FAILED       : ' + $script:FAILED.ToString())
Write-Output ('  DEFERRED     : ' + $script:DEFERRED.ToString())
if ($script:Failures.Count -gt 0) {
    Write-Output ('  failing ids   : ' + ($script:Failures -join ', '))
}
Write-Output ('  target        : ' + $WorkflowPath)
Write-Output ''
Write-Output ('RESULT: ' + $outcome)
Write-Output ("PASSED={0} FAILED={1} DEFERRED={2}" -f $script:PASSED, $script:FAILED, $script:DEFERRED)
Write-Output ("SCRIPT_RUNTIME_SECONDS={0:N2}" -f $sw.Elapsed.TotalSeconds)
if ($script:FAILED -gt 0) { exit 1 }
exit 0