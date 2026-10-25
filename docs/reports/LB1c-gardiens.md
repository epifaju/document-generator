# LB1c — Gardiens : port n8n et motif de date

Lot borné aux deux échecs de la baseline LB1. Trois lignes. Aucun commit. Schéma d'extraction, prompt, contrats, code métier, tests, fixtures et workflow inchangés.

Décision D1. Le port hôte `5681` est un contournement local introduit par le commit `6d7f02a` (2026-10-10), absent des contrats, des tests et de la documentation versionnée.

Décision D2. `docs/architecture.md` suit le schéma versionné : le motif `^\\d{4}-\\d{2}-\\d{2}$` des lignes 585 et 623 est remplacé par `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`. Le fichier `prompts/extraction/attestation_concordance.schema.json` et le prompt ne changent pas.

## STATUS

PASS côté exécution. `mvn -B test` se termine en code 0 (298 tests, 0 échec).

Tester : `VERDICT: PASS`.
Reviewer : `VERDICT: PASS`.
Security : `VERDICT: PASS`. n8n, ollama et l'API restent sur `127.0.0.1`.

## FINDINGS

### Correctif

| Fichier | Ligne | Avant | Après |
|---|---|---|---|
| `docker/docker-compose.yml` | 101 | `127.0.0.1:5681:5678` | `127.0.0.1:5678:5678` |
| `docs/architecture.md` | 585 | `^\\d{4}-\\d{2}-\\d{2}$` | `^[0-9]{4}-[0-9]{2}-[0-9]{2}$` |
| `docs/architecture.md` | 623 | `^\\d{4}-\\d{2}-\\d{2}$` | `^[0-9]{4}-[0-9]{2}-[0-9]{2}$` |

Diff : `docs/reports/evidence/LB1C-diff.txt`. Le commentaire du compose (`docker/docker-compose.yml:98`) parlait déjà de `http://127.0.0.1:5678`. Ollama reste `127.0.0.1:11434:11434` (`docker/docker-compose.yml:113`). L'API reste `127.0.0.1:8080:8080` (`docker/docker-compose.yml:61`). PostgreSQL reste non publié (ligne commentée, `docker/docker-compose.yml:32`).

Les oracles n'ont pas été assouplis. `RuntimeSecurityContractTest.java:117` exige `127.0.0.1:5678:5678`, aligné sur `requirements/N8N_CONTRACTS.md:57`. `ExtractionSchemaDocCoherenceTest.java:157-162` compare le bloc JSON §8 au schéma, dont le motif est déjà `[0-9]` (`prompts/extraction/attestation_concordance.schema.json:29`).

Origine, antérieure aux lots LA/LA2 (2026-10-25) : `5681` entre dans le compose par `6d7f02a` ; le motif `\d` de l'architecture date de `38c9697` (2026-10-02) et n'a pas suivi le passage du schéma à `[0-9]` dans ce même `6d7f02a`.

### Inventaire `5681`

Commande : `git grep -n -F "5681"`, sortie après la correction du compose dans `docs/reports/evidence/LB1C-grep-5681.txt`. Avant édition, cette commande listait aussi `docker/docker-compose.yml:101`. Après édition, le compose n'y figure plus.

`git grep` sans `--untracked` ignore les fichiers non suivis. Un passage `git grep --untracked -n -F "5681" -- docs/reports` ajoute deux comptes rendus de la baseline, non modifiés : `docs/reports/LB1-baseline-backend.md`, `docs/reports/evidence/LB1-mvn-test.txt`.

Documentation versionnée (`docs/`) : aucune occurrence de `5681`. `docs/architecture.md:765`, `:1079` et `:1253` documentent déjà `127.0.0.1:5678:5678`.

#### Scripts exécutables

Tous visent le port hôte `5681` (`http://127.0.0.1:5681`). Aucun n'a été exécuté. G6 = NOT_RUN. Quand le compose publié écoute sur `5678`, un appel vers `5681` n'a plus de processus en face : la connexion est refusée. Le port conteneur n8n reste `5678`. Effet prédit, non observé.

| Script | Cible | Ce qui casse |
|---|---|---|
| `api_tests.ps1` | API n8n (`/api/v1/workflows`, executions) | lecture API refusée |
| `check_n8n.ps1` | webhook `POST /webhook/document-generation` | webhook injoignable |
| `debug2.ps1` | racine, `/webhook`, webhook document | sondes HTTP refusées |
| `debug3.ps1` | chemins `/.ext/n8n`, `/n8n`, `/v1`, `/api` | sondes HTTP refusées |
| `debug_webhook.ps1` | webhook document | webhook injoignable |
| `infra_check.ps1` | webhook document | webhook injoignable |
| `infra_check2.ps1` | webhook document | webhook injoignable |
| `phase1_check.ps1` | API et métadonnées n8n | lecture API refusée |
| `run.ps1` | webhook document | webhook injoignable |
| `run_s1.ps1` | webhook document | webhook injoignable |
| `send_webhook.ps1` | webhook document | webhook injoignable |
| `test_422.ps1` | webhook document | webhook injoignable |
| `test_api.ps1` | `/.ext/n8n`, `/n8n/nodes`, `/health` | sondes HTTP refusées |
| `test_import.ps1` | base `http://127.0.0.1:5681` | import injoignable |
| `test_invokerequest.ps1` | webhook sur `5678` et sur `5681` | l'URL `5678` peut répondre ; l'URL `5681` est refusée |
| `test_ir.ps1` | webhook document | webhook injoignable |
| `test_msgs.ps1` | webhook document | webhook injoignable |
| `test_urls.ps1` | `/`, `/webhook`, `/api` | sondes HTTP refusées |
| `test_urls2.ps1` | GET et POST sur la racine | sondes HTTP refusées |
| `test_wc.ps1` | webhook document | webhook injoignable |
| `test_webhook.ps1` | webhook document | webhook injoignable |
| `try_api2.ps1` | `/api/workflows`, `/workflows`, `/v1/workflows` | lecture API refusée |
| `try_import.ps1` | `/api/v1/workflows`, `/rest/workflows` | import injoignable |
| `webhook_test.ps1` | webhook document | webhook injoignable |
| `webhook_test2.ps1` | webhook document | webhook injoignable |
| `wh5681.ps1` | webhook document | webhook injoignable |

#### Comptes rendus historiques

Non modifiés. Ils décrivent un runtime passé sur le port 5681. Ils ne s'exécutent pas.

- `FIX4.2_REPORT.md`
- `FIX4.3_REPORT.md`
- `FIX4.4_REPORT.md`
- `INDEPENDENT_RUNTIME_AUDIT.md`
- `R3_FINAL_REPORT.md`
- `R5_PRERESTART_VERIFICATION.md`
- `REVIEWER_REPORT.md`
- `S1_REPORT.md`
- `SESSION_FINAL_REPORT.md`
- `reviewer_verification.md`
- `n8n/backups/n8n-data-before-loadprompt-fix.tar` (fichier binaire signalé par git grep, non ouvert, non modifié)
- `docs/reports/LB1-baseline-backend.md` (non suivi)
- `docs/reports/evidence/LB1-mvn-test.txt` (non suivi)

#### Documentation

Aucune. Pas de `5681` sous `docs/` dans `git grep`.

### Procédure manuelle — port 5678 déjà occupé

Non appliquée dans ce lot. Aucun `docker-compose.override.yml` n'a été créé. Docker n'a pas été lancé. `docker compose version` n'a pas été exécuté.

Si le port hôte 5678 est déjà pris sur le poste :

1. Laisser `docker/docker-compose.yml` à `127.0.0.1:5678:5678`.
2. Créer à côté un `docker-compose.override.yml` non versionné (ne pas l'ajouter à git).
3. Vérifier `docker compose version` avant d'utiliser la balise `!override`. Compose fusionne les listes `ports` en les ajoutant : un override sans `!override` publierait à la fois `5678` et le port de secours. Avec un Compose récent, `!override` remplace la liste. Un Compose plus ancien peut rejeter cette balise : dans ce cas, ne pas inventer un contournement dans le fichier versionné ; escalader.
4. Garder le loopback. Exemple, non écrit dans le dépôt :

```yaml
services:
  n8n:
    ports: !override
      - "127.0.0.1:5681:5678"
```

## FILES_CHANGED

Correctifs :

- `docker/docker-compose.yml` (ligne 101)
- `docs/architecture.md` (lignes 585 et 623)

Preuves et rapport :

- `docs/reports/evidence/LB1C-diff.txt`
- `docs/reports/evidence/LB1C-grep-5681.txt`
- `docs/reports/evidence/LB1C-mvn-targeted.txt`
- `docs/reports/evidence/LB1C-mvn-test.txt`
- `docs/reports/LB1c-gardiens.md`

Aucun autre fichier. Les scripts et comptes rendus qui citent `5681` n'ont pas été modifiés.

## TESTS

Ciblés, `backend/`, `mvn -B -Dtest=ExtractionSchemaDocCoherenceTest,RuntimeSecurityContractTest test` (`LB1C-mvn-targeted.txt:1-5`, `:36-45`) :

```
exit_code: 0
duration_seconds: 6.482
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ExtractionSchemaDocCoherenceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in RuntimeSecurityContractTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  4.605 s
```

Complet, `mvn -B test` (`LB1C-mvn-test.txt:1-5`, `:525`, `:539`, `:560-566`) :

```
exit_code: 0
duration_seconds: 30.157
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ExtractionSchemaDocCoherenceTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in RuntimeSecurityContractTest
[INFO] Tests run: 298, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  27.811 s
[INFO] Finished at: 2026-10-25T12:53:45+01:00
```

Baseline LB1 : 298 tests, 2 échecs, code 1. Ce run : 298 tests, 0 échec, code 0. Aucune ligne `Running` pour `HttpVerticalSliceIT` ni `PostgresPersistenceIT`.

### Gates

| Gate | Statut | Preuve |
|---|---|---|
| G1 `mvn -B test` | PASS | Code 0. Tests unitaires et standalone seulement ; `*IT` NOT_RUN |
| G2 persistance | NOT_RUN | `PostgresPersistenceIT` non lancé |
| G3 DOCX hors suite | NOT_RUN | `PoiTemplateEngineTest` a tourné dans G1 (14 tests, 0 échec, `LB1C-mvn-test.txt:550`) |
| G4 HTTP E2E | NOT_RUN | `HttpVerticalSliceIT` non lancé |
| G5 fixtures | NOT_RUN | Validateur n8n non lancé |
| G6 n8n runtime | NOT_RUN | Scripts `5681` non exécutés. Docker non démarré |
| G7 sécurité | PASS | n8n `127.0.0.1:5678:5678`, API `127.0.0.1:8080:8080`, ollama `127.0.0.1:11434:11434`. PostgreSQL non publié. Scan CVE : NOT_RUN |
| G8 CI | NOT_RUN | Aucune CI |

## RISKS

- Les 26 scripts de la racine appellent encore `127.0.0.1:5681`. Après ce lot, les lancer tels quels rate le n8n publié sur `5678`. Effet prédit seulement : G6 NOT_RUN.
- `test_invokerequest.ps1` tente les deux ports. L'URL `5678` peut réussir ; l'URL `5681` échoue.
- Un override Compose qui ajoute un `ports` sans `!override` publie le port versionné et le port de secours en même temps. La procédure ci-dessus n'a pas été appliquée.
- Les comptes rendus et l'archive `n8n/backups/n8n-data-before-loadprompt-fix.tar` gardent la mention `5681`. Les réécrire effacerait l'historique. L'archive n'a pas été ouverte.
- JDK : ce run Maven est celui de la baseline (Java 17 pour Maven). Non recontrôlé au-delà des logs de test.

## NEXT_ACTION

Lot de suivi proposé, hors LB1c : reprendre les 26 scripts exécutables pour qu'ils visent `http://127.0.0.1:5678`, ou les retirer du dépôt s'ils ne servent plus. Ne pas réécrire les comptes rendus historiques ni l'archive tar. N'exécuter ces scripts qu'avec un accord G6 (Docker et n8n).

## Verdicts recopiés

Tester :

```
VERDICT: PASS
```

Reviewer :

```
VERDICT: PASS
```

Security :

```
VERDICT: PASS
```
