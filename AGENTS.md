# AGENTS.md

# Administrative Document Generator

## 1. Mission

Build and maintain a production-grade conversational system for generating
administrative documents.

The system receives natural-language requests such as:

"Génère une attestation de concordance pour Maria Gomes,
née le 12/05/1985 à Bissau..."

The system must:

1. identify the requested document type;
2. extract applicant information;
3. validate mandatory fields;
4. ask for missing information;
5. normalize the data;
6. persist the request;
7. select the correct official DOCX template;
8. merge validated data into the template;
9. generate DOCX;
10. optionally generate PDF;
11. archive the result;
12. return the generated document.

The AI MUST NOT invent administrative information.

---

# 2. Target architecture

Frontend / Chat
|
v
n8n
|
+---- Ollama / LLM
|
+---- PostgreSQL
|
+---- Spring Boot Document API
|
v
DOCX template
|
v
DOCX / PDF

Responsibilities:

n8n:

- orchestration
- conversation flow
- calling APIs
- handling retries
- notifications

Spring Boot:

- business validation
- document generation
- template processing
- document storage API

PostgreSQL:

- persistent business data
- document requests
- templates metadata
- generated documents
- audit logs

Ollama / LLM:

- intent classification
- document-type classification
- structured information extraction

The LLM MUST NOT be the source of truth for administrative rules.

---

# 3. Repository structure

requirements/
Business requirements and document definitions.

n8n/workflows/
Exported n8n workflows.

backend/
Spring Boot application.

database/
SQL schema and migrations.

prompts/
Version-controlled AI prompts.

templates/
DOCX templates.

tests/
Automated test cases and fixtures.

docs/
Architecture and operational documentation.

docker/
Local infrastructure.

---

# 4. Core engineering principles

## 4.1 Deterministic document generation

Never ask an LLM to freely generate the final official document when an
approved template exists.

Correct pipeline:

natural language
-> AI extraction
-> structured JSON
-> deterministic validation
-> normalization
-> official template
-> document generation

---

## 4.2 No hallucinated administrative data

The system MUST NOT invent:

- names
- dates
- addresses
- identifiers
- passport numbers
- places of birth
- nationality
- administrative references
- legal statements
- missing applicant information

If mandatory information is missing:

status = MISSING_INFORMATION

The system must ask the user for it.

---

## 4.3 Structured AI output

AI extraction must return JSON validated against a schema.

Example:

{
"documentType": "ATTESTATION_CONCORDANCE",
"confidence": 0.97,
"data": {
"prenom": "Maria",
"nom": "Gomes",
"dateNaissance": "1985-05-12",
"lieuNaissance": "Bissau",
"nomIncorrect": "Maria Gomez",
"nomCorrect": "Maria Gomes"
},
"missingFields": []
}

Never parse critical business data from uncontrolled prose when structured
output can be used.

---

# 5. Initial pilot

The first supported document is:

ATTESTATION_CONCORDANCE

Do not add additional document types until the complete pipeline works for
this pilot.

The architecture must nevertheless make adding future document types easy.

Future examples:

- ACTE_NAISSANCE
- LAISSER_PASSER
- CERTIFICAT_COUTUME
- CERTIFICAT_NATIONALITE
- CERTIFICAT_DECES
- ATTESTATION_MARIAGE

---

# 6. Multi-agent organization

The project uses these agents:

orchestrator
architect
business-analyst
n8n-developer
backend-developer
database-engineer
ai-engineer
tester
reviewer
security

The orchestrator coordinates work.

Specialist agents should stay within their responsibilities.

---

# 7. Mandatory development lifecycle

Every feature follows:

BUSINESS ANALYSIS
|
v
ARCHITECTURE
|
v
IMPLEMENTATION
|
v
TESTER
|
+---- FAIL ----> IMPLEMENTATION
| |
| v
| TESTER
|
+---- PASS
|
v
REVIEWER
|
+------+------+
| |
FAIL PASS
| |
v v
IMPLEMENTATION SECURITY
|
+------+------+
| |
FAIL PASS
| |
v v
IMPLEMENTATION DONE

Maximum correction cycles:

MAX_REVIEW_CYCLES = 3

After three unsuccessful cycles:

STOP.

Produce a blocking report.

Do not continue modifying the project blindly.

---

# 8. Definition of Done

A feature is DONE only when all applicable items pass:

[ ] business requirements identified
[ ] mandatory fields documented
[ ] validation rules documented
[ ] architecture impact reviewed
[ ] implementation complete
[ ] database migration created when required
[ ] n8n workflow exported when modified
[ ] prompts version controlled
[ ] no credentials committed
[ ] unit tests pass
[ ] integration tests pass
[ ] business scenario tests pass
[ ] regression tests pass
[ ] reviewer = PASS
[ ] security = PASS
[ ] documentation updated

---

# 9. Git safety

Before modification:

git status --short
git diff

Never assume the working tree is clean.

Never discard unrelated user modifications.

Never execute destructive Git commands unless explicitly authorized.

Forbidden by default:

git reset --hard
git clean -fd
git push --force

Do not automatically commit unless explicitly requested.

---

# 10. Database rules

Never modify a production database directly.

Schema changes require migrations.

Migrations must be versioned.

Never silently delete business data.

Prefer foreign keys and database constraints for critical integrity rules.

Database migrations belong in:

database/migrations/

---

# 11. n8n rules

All production workflows must be exportable and stored under:

n8n/workflows/

Never place credentials inside exported workflow JSON.

Use environment variables or n8n credentials.

Separate:

- orchestration
- business logic
- AI extraction
- document generation

Complex deterministic business logic should normally live in the backend,
not in large Code nodes.

---

# 12. AI rules

LLMs may:

- classify requests
- identify document types
- extract fields
- normalize safe textual values
- detect potentially missing information

LLMs must not:

- invent missing values
- bypass validation
- decide authorization
- directly modify production data
- generate official administrative claims without deterministic validation

Low confidence must trigger review or clarification.

---

# 13. Security

Administrative documents may contain personal data.

Never log unnecessary personal data.

Never expose:

- database passwords
- API keys
- JWT secrets
- SMTP credentials
- private tokens

Secrets belong in environment variables or secure credential stores.

Generated document access must be authorized.

Audit important actions.

---

# 14. Error handling

Errors must be classified.

Examples:

VALIDATION_ERROR
MISSING_INFORMATION
AI_EXTRACTION_ERROR
TEMPLATE_NOT_FOUND
DOCUMENT_GENERATION_ERROR
DATABASE_ERROR
UNAUTHORIZED
INTERNAL_ERROR

Do not expose stack traces to end users.

Log technical correlation IDs.

---

# 15. Observability

Important operations should record:

requestId
documentType
status
timestamp
duration
errorCode

Avoid storing raw sensitive prompts in logs unless explicitly required and
properly protected.

---

# 16. Testing philosophy

Tests must cover:

happy path
missing fields
invalid values
AI malformed JSON
unknown document type
template missing
database errors
duplicate requests
authorization
document generation
regressions

The tester must test observable behavior, not merely implementation details.

---

# 17. Agent communication

Agents must report:

STATUS
FINDINGS
FILES_CHANGED
TESTS
RISKS
NEXT_ACTION

Agents must not claim success without evidence.

"Looks correct" is not a test result.

---

# 18. Human escalation

Stop and request human intervention when:

- requirements are contradictory;
- official document wording is ambiguous;
- a destructive migration is required;
- security implications are uncertain;
- three correction cycles failed;
- production credentials would be required;
- a legal/administrative rule cannot be verified.

Do not guess.
