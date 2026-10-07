PHASE I.1-B-D2 — EVIDENCE RECOVERY REPORT

CURRENT_HEAD: 83aab6d659b250e5f3974662991b17516c94abdd
CURRENT_GIT_STATUS: M n8n/workflows/document-generation-v1.json (crypto require removed; UTF-8 BOM fixed)
GIT_DIFF_CHECK: PASS (no output)

DOCKER_CLI_AVAILABLE: True
DOCKER_DAEMON_AVAILABLE: True
N8N_CONTAINER_PRESENT: True
N8N_CONTAINER_STATE: running

SQLITE_EXISTS: True
SQLITE3_AVAILABLE: False (sqlite3 CLI not in container $PATH)
N8N_DATA_MOUNT_TYPE: host path /home/node/.n8n
N8N_DATA_MOUNT_SOURCE: n8n container volume
N8N_DATA_MOUNT_DESTINATION: /home/node/.n8n inside container

LATEST_EXECUTIONS:
Pre-fix (executions 1-26): all failed at InitOrchestration with "Cannot find module 'crypto' [line 14]"
Post-fix (executions 27-29): all failed at InitOrchestration with "Invalid regular expression flag [line 14]"

POST_RECOVERY_EXECUTION_FOUND: True
POST_RECOVERY_EXECUTION_ID: 27 (earliest post-recovery execution)

EXECUTION_STATUS: failed (all post-recovery executions)

EXECUTED_NODE_SEQUENCE: Webhook → InitOrchestration (then termination)
- Webhook: started → finished
- InitOrchestration: started → finished
- E1-E6: NOT executed
- OllamaExtract: NOT executed
- ParseExtraction: NOT executed
- FinalizeResponse: NOT executed
- RespondToWebhook: NOT executed

LAST_EXECUTED_NODE: InitOrchestration

WEBHOOK_EXECUTED: True (executed in all post-recovery runs 27-29)
INIT_EXECUTED: True (progressed beyond Webhook, entered InitOrchestration)
IF_INGRESS_ACCEPTED_EXECUTED: Unknown (workflow failed before ingress acceptance verdict)
LOAD_PROMPT_EXECUTED: False (not reached)
OLLAMA_EXTRACT_EXECUTED: False (not reached)
PARSE_EXTRACTION_EXECUTED: False (not reached)

E1_EXECUTED: False
E2_EXECUTED: False
E3_EXECUTED: False
E4_EXECUTED: False
E5_EXECUTED: False
E6_EXECUTED: False

FINALIZE_EXECUTED: False
RESPOND_EXECUTED: False

INVALID_REQUEST_PRESENT: Unknown (workflow terminated at InitOrchestration before INVALID_REQUEST outcome could be produced as a node output)
INVALID_REQUEST_NODE: N/A (no downstream nodes executed)
INVALID_REQUEST_SOURCE: N/A
INGRESS_VALIDATION: N/A
EXTRACTION_VALIDATION: N/A
BACKEND_RESPONSE: N/A
ROUTING: InitOrchestration (blocked at orchestration level)
FINAL_RESPONSE: N/A

MISSING_FIELD_NAMES: N/A (not reached)

EMPTY_ARRAY_ORIGIN: BRANCH_WITH_ZERO_ITEMS / FINALIZE_OUTPUT (not applicable — [] observed in recovery webhook result because execution terminated at InitOrchestration before any output was produced; the empty array originates from the webhook response when InitOrchestration outcome is INVALID_REQUEST, but this [] originates from InitOrchestration fault output, not from FinalizeResponse)

ERROR_NODE: InitOrchestration
ERROR_TYPE: REGEX_ERROR (invalid regular expression flag)
ERROR_MESSAGE_SANITIZED: "Invalid regular expression flag [line 14]"

D2_BLOCKER: UNKNOWN
> The blocker is at the InitOrchestration jsCode level (regex pattern error on line 14), preventing any downstream node execution. The workflow correctly routes input to InitOrchestration, but the JavaScript code's regex pattern causes a runtime error before any downstream processing (E1-E6, OllamaExtract, etc.) can occur. This is a workflow code defect, not a test harness input issue nor a backend/routing defect.

D2_RESULT: BLOCKER_IDENTIFIED
> A post-recovery execution exists (executions 27-29) and its runData has been read from the n8n event log. The blocker is at the InitOrchestration level with an "Invalid regular expression flag [line 14]" error, preventing downstream execution. The original crypto module error has been resolved, but a new regex pattern error in the same code location (line 14 of the jsCode) now blocks workflow progression.

A27_RUNTIME_RESULT=NOT_PROVEN
A29_RUNTIME_RESULT=NOT_RUN

NEXT_ACTION: WAIT_FOR_HUMAN

STOP — WAIT_FOR_HUMAN_APPROVAL