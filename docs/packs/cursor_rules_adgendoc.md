# Pack de règles Cursor — adgendoc

Chaque bloc ci-dessous correspond à un fichier à créer dans `.cursor/rules/`. À adapter aux chemins réels après l'audit G0 (les globs sont indicatifs).

---

## `.cursor/rules/00-project-context.mdc`

```markdown
---
description: Contexte projet adgendoc et règles d'or (toujours appliqué)
alwaysApply: true
---

# adgendoc — contexte
Backend Spring Boot EXISTANT de génération de documents administratifs. Premier type : ATTESTATION_CONCORDANCE.
Parcours : demande → extraction IA → validation déterministe → normalisation → PostgreSQL → sélection template DOCX → génération → stockage → récupération.
L'IA propose, le code déterministe décide.

# Sources de vérité (ordre)
1. requirements/API_CONTRACTS.md, requirements/N8N_CONTRACTS.md, requirements/ATTESTATION_CONCORDANCE.md
2. Migrations Flyway
3. Code et tests existants
4. ADR, docs/architecture.md
5. PRD_adgendoc_v2_1_Cursor.md (les sources 1–4 gagnent ; signaler tout écart)

# Règles d'or
- Ne jamais recréer le projet ni réécrire un module entier.
- Ne jamais renommer ni inventer endpoints, statuts, codes d'erreur : les lire dans les contrats.
- Ne jamais modifier une migration Flyway déjà appliquée : en ajouter une nouvelle.
- Un changement = une portée = des tests = un rapport. Pas de refactoring mélangé à une correction.
- Avant de modifier : lister les fichiers lus et le plan, attendre l'approbation.
- Citer `fichier:ligne` pour toute affirmation sur l'existant ; si non vérifié, écrire « inconnu ».
- Un gate non exécuté = NOT_RUN, jamais PASS. Coller les sorties réelles des commandes.
- Demander l'accord avant tout accès réseau, Docker, base, n8n, fournisseur IA.
- Ne pas supprimer les fichiers non suivis (=ro, opencode.json, fixtures).
```

---

## `.cursor/rules/10-architecture.mdc`

```markdown
---
description: Architecture hexagonale et dépendances autorisées
globs: backend/src/main/java/**
alwaysApply: false
---

- Respecter les packages/ports/adapters existants avant d'en créer de nouveaux.
- `domain` : aucune dépendance à Spring, JPA, Apache POI, client IA ou HTTP.
- `application` : orchestre via des ports (ExtractionPort, RequestRepository, TemplateRenderer, DocumentStorage).
- `adapter` : implémente les ports ; c'est le seul endroit où vivent POI, JPA, clients externes.
- Les contrôleurs ne contiennent aucune logique métier : ils mappent DTO ↔ commande et délèguent.
- Les transitions d'état (T1–T14, cf. PRD §8) passent exclusivement par RequestStateMachine et écrivent un AuditEvent dans la même transaction.
- Constructeur d'injection uniquement ; pas d'injection par champ.
- Toute nouvelle dépendance Maven nécessite une justification (ADR ou note dans le rapport de lot).
- Toute violation doit être détectable par un test ArchUnit.
```

---

## `.cursor/rules/20-testing.mdc`

```markdown
---
description: Conventions de tests
globs: backend/src/test/**
alwaysApply: false
---

- JUnit 5 + AssertJ. Nommage : `methode_condition_resultatAttendu`.
- Écrire d'abord le test qui échoue, lié à un identifiant FR-xx du PRD.
- Unitaires : aucun I/O, aucun appel IA ; domaine et validation couverts (dates, champs obligatoires, clés inconnues, règles métier).
- Intégration : Testcontainers PostgreSQL (jamais une base partagée) ; Flyway appliqué.
- IA et n8n : stubs/WireMock, jamais d'appel réel en CI.
- Fixtures 100 % synthétiques : aucune donnée personnelle réelle.
- Tests paramétrés pour le mapping endpoint × scénario → HTTP → code → statut (PRD §12.1) et outcome n8n → HTTP (§12.2).
- Vérification DOCX : ouverture POI, extraction du texte (corps, tableaux, en-têtes/pieds), absence de motif de placeholder résiduel, comparaison avec les valeurs normalisées.
- Ne jamais affaiblir ou supprimer un test pour le faire passer ; signaler plutôt l'écart.
```

---

## `.cursor/rules/30-api-contracts.mdc`

```markdown
---
description: Respect des contrats API
globs: **/api/**,**/web/**,requirements/**,**/openapi*
alwaysApply: false
---

- Les 8 endpoints, schémas et statuts viennent de requirements/API_CONTRACTS.md : ne pas redessiner.
- Tout changement de contrat exige un ADR et un accord explicite ; sinon changement additif et rétrocompatible uniquement.
- Erreurs : un seul format, `ErrorResponse` (code, message FR, correlationId, missingFields[], requestId?, status?, fieldErrors[]). Pas de ProblemDetail. Conserver les codes ERR_* existants.
- Statuts : DRAFT, MISSING_INFORMATION, VALIDATED, REJECTED (terminal), GENERATED (terminal), FAILED. Ne pas en inventer (REQUIRES_BUSINESS_VALIDATION n'existe pas).
- Endpoints E1–E8 sous /api/v1 ; X-Correlation-Id propagé et renvoyé ; corps max 65 536 octets (413).
- It.1 sans authentification : ne pas ajouter de Spring Security sans ADR ; JWT = it.2.
- Le LLM (Ollama) est appelé par n8n, pas par le backend. Contrat webhook : requirements/N8N_CONTRACTS.md (200 = résultat métier, jamais de 409 relayé, jamais de binaire).
- Si un contrat est ambigu ou contradictoire (cf. PRD §12.3), le signaler et s'arrêter ; ne pas trancher seul.
- Dates canoniques ISO yyyy-MM-dd ; dd/MM/yyyy réservé aux entrées/présentations prévues par le contrat.
- Les clés JSON inconnues sont rejetées, pas ignorées.
- Idempotence : suivre la règle du contrat ; ne jamais produire deux documents pour une même demande dupliquée.
- Maintenir openapi.yaml synchronisé avec le contrat et couvert par des tests.
```

---

## `.cursor/rules/40-docx-generation.mdc`

```markdown
---
description: Génération DOCX et templates
globs: **/docx/**,**/template*/**,templates/**
alwaysApply: false
---

- Template : templates/attestation_concordance_v1.docx ; ne jamais le modifier sans nouvelle version (v2) et mise à jour de l'empreinte SHA-256.
- Placeholders attendus (14) : prenom, nom, date_naissance, lieu_naissance, nom_incorrect, nom_correct, sexe, nationalite, document_source_reference, langue_document, demandeur, motif, reference_demande, date_generation.
- POI XWPF : un placeholder peut être éclaté en plusieurs runs ; fusionner les runs avant remplacement. Traiter corps, tableaux imbriqués, en-têtes et pieds de page.
- Échapper/neutraliser les valeurs injectées ; aucune macro, aucun champ actif.
- Dates présentées selon langue_document ; valeur canonique conservée en ISO.
- Après génération : vérifier l'absence de placeholder résiduel, sinon échouer en GENERATION_FAILED (jamais de document partiel stocké).
```

---

## `.cursor/rules/50-security-pii.mdc`

```markdown
---
description: Sécurité, PII, secrets, logs (toujours appliqué)
alwaysApply: true
---

- Aucun secret en clair dans le code, les fichiers de config versionnés, les fixtures ou les rapports.
- Aucune PII réelle ; utiliser des données synthétiques évidentes.
- Ne jamais logger : valeurs de champs personnels, contenu de documents, binaires, prompts complets, réponses IA brutes. Logger identifiants techniques (requestId), codes d'erreur, durées.
- Toute entrée est validée côté serveur (taille, type MIME, schéma).
- La sortie IA est une donnée non fiable : schéma strict, puis validation déterministe, puis normalisation.
- Le texte utilisateur n'est jamais traité comme instruction pour l'IA (anti prompt-injection).
- Accès aux documents contrôlé par rôle et par demande ; pas d'URL publique permanente.
```

---

## `AGENTS.md` (synthèse à ajouter ou fusionner avec l'existant)

```markdown
# AGENTS.md — adgendoc
Lire d'abord `.cursor/rules/00-project-context.mdc` et `PRD_adgendoc_v2_1_Cursor.md`.
Commandes de référence (à confirmer à l'audit) : `./mvnw verify` · tests ciblés `./mvnw -Dtest=NomTest test` · validateur fixtures `tests/n8n/validate_i1_fixtures.ps1`.
Workflow : plan → approbation → test d'abord → changement minimal → vérification avec sorties réelles → rapport `docs/reports/<lot>.md`.
Statuts de gates : PASS / FAIL / NOT_RUN uniquement.
```
