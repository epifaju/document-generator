# Audit de reprise — Itération 1 (vertical slice ATTESTATION_CONCORDANCE)

**Date** : 2026-10-02
**Mode** : RECOVERY — audit uniquement (aucun fichier créé ni modifié, aucun spécialiste d'implémentation lancé)
**Git** : `git status --short -uall` → arbre propre (aucune modification). Repo initialisé
(`38c9697 chore: initialize administrative document generator`, 64 fichiers suivis).

---

## Tableau d'état des composants

| COMPONENT | STATUS | EVIDENCE | BLOCKER | NEXT_ACTION |
|---|---|---|---|---|
| `requirements/` (contrat métier + API) | **DONE** | `ATTESTATION_CONCORDANCE.md` 32 083 o + `API_CONTRACTS.md` 26 115 o, UTF-8 valide, 8 endpoints, OQ-2/OQ-3 arbitrées | — | aucune |
| `docs/architecture.md` | **DONE** | 65 933 o / 1 059 lignes / 14 sections, ADR + plan 83 fichiers | — | aucune |
| `database/migrations/V1__init.sql` | **PARTIAL** | 4 tables (`document_request`, `generated_document`, `template_registry`, `audit_log`), 4 index + `uq_template_registry_active_code`, CHECK/FK présents, UTF-8 no BOM | Jamais exécuté sur un PostgreSQL réel | valider sur PG au démarrage backend/Docker, puis nettoyer |
| `database/migrations/V2__seed_template.sql` | **PARTIAL** | seed idempotent `ON CONFLICT DO NOTHING`, checksum = placeholder 64 zéros (vérifié par script) | dépend du template DOCX absent | créer template → SHA-256 réel → remplacer placeholder → tester mismatch |
| `backend/pom.xml` | **DONE** | Spring Boot 3.3.13, release 17, web/validation/data-jpa/actuator, flyway+postgresql, poi-ooxml 5.2.5, json-schema-validator 1.5.9, `spring-boot-starter-test` (test), copie ressources `../prompts/extraction` | — | aucune |
| `backend/src/main/resources/application.yml` | **DONE** | `ddl-auto: validate`, Flyway `filesystem:${MIGRATIONS_DIR:../database/migrations}`, `include-stacktrace: never`, `app.document.*`, `app.extraction.schema-path` | — | aucune |
| `backend` phases B+C (domain) | **DONE** | 20 fichiers (`RequestStatus`, `ErrorCode` (14+14 codes), `DocumentRequest`, 6 ports, 6 exceptions), `mvn compile` OK antérieur | — | aucune |
| `backend` phases D/E/F (application + api + infrastructure) | **MISSING** | `backend/src/main/java/com/adgendoc/` ne contient que `domain/` — aucun `application/`, `api/`, `infrastructure/` | Point de reprise = 1er livrable incomplet de la chaîne : pas de controller, pas de service, pas de JPA/persistence, pas de `PoiTemplateEngine`, pas de storage → slice vertical impossible | Phase D → E → F |
| `backend/src/test/` (tests Maven) | **MISSING** | dossier `backend/src/test` absent (0 fichier) ; gate humain : `mvn test` → `No tests to run` = GATE FAILED | code D/E/F absent + aucun test écrit | Phase K : ≥ 13 cas obligatoires, `tests > 0` + `BUILD SUCCESS` |
| `tests/` (fixtures JSON) | **MISSING** | `tests/` → 0 fichier | — | fixtures valides/invalides Phase K |
| `templates/attestation_concordance_v1.docx` | **MISSING** | `templates/` → 0 fichier | OQ-1 wording non validé (autorisé : template dev « NON APPROUVÉ POUR PRODUCTION ») | créer template technique → checksum → V2 |
| `n8n/workflows/document-generation-v1.json` | **MISSING** | `n8n/workflows/` → 0 fichier (la Phase I antérieure n'a rien livré) | — | workflow sans credentials ; sans règle métier de date (canonique ISO-8601 côté backend) |
| `prompts/extraction/` | **DONE** | `attestation_concordance.schema.json` (3 715 o, draft 2020-12, validé parse) + `system_prompt_attestation_concordance.md` (2 962 o) | — | aucune |
| `docker/` + `backend/Dockerfile` | **DONE** | `docker-compose.yml` 3 848 o (validé `docker compose config`), `.env.example` placeholders `CHANGE_ME`, Dockerfile multi-étapes non-root | — | aucune |
| `.gitignore` | **MISSING** | absent ; `backend/target/**` (18 `.class`, maven-status) est commité dans `38c9697` | hygiène repo | ajouter `.gitignore` (`target/`, `storage/`, `docker/.env`, `*.log`) + retirer `target/` du suivi |
| Chaîne verticale complète | **INVALID** | Request → extraction → validation → normalisation → PostgreSQL → template → DOCX → stockage → récupération : aucun segment exécutable (aucun endpoint, aucun service, aucun template) | phases D/E/F + G + tests manquants | reprise en Phase D |

---

## Notes de preuve

- Encodage : V1/V2/requirements/architecture = UTF-8 valide, no BOM (les mojibake PowerShell étaient un artefact console).
- Checksum V2 : placeholder confirmé à exactement 64 zéros.
- `mvn test` non relancé pendant cet audit (créerait des fichiers dans `target/`).
- Aucun serveur PostgreSQL ni Docker lancé ni laissé tourner.

## Décisions en vigueur

- Format canonique interne des dates : **ISO-8601 `YYYY-MM-DD`** ; `dd/MM/yyyy` uniquement format d'entrée toléré ou de présentation. La normalisation appartient au backend/contrat de données, pas à n8n.
- **OQ-1 = `REQUIRES_BUSINESS_VALIDATION`** : wording officiel non validé. Template de développement autorisé, explicitement « NON APPROUVÉ POUR PRODUCTION ». Le système ne peut pas être déclaré PRODUCTION_READY tant que OQ-1 n'est pas validé humainement.
- MVP-FIRST : seul le vertical slice `ATTESTATION_CONCORDANCE` est implémenté ; le plan des 83 fichiers ne doit pas être créé automatiquement.

## Statut

**STOP — WAIT_FOR_HUMAN_APPROVAL**
(L'orchestrateur ne poursuit l'implémentation qu'après validation humaine explicite.)
