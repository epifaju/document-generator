# LS1 — Inventaire des scripts racine (port 5681)

Phase 1, lecture seule. Aucun script exécuté, modifié, déplacé ou supprimé. Aucun commit. Docker, n8n et Ollama non démarrés. Le classement ci-dessous est une proposition. La phase 2 (déplacement, paramétrage, archivage, suppression) attend une décision humaine fichier par fichier.

## STATUS

Inventaire terminé. Décision de tri non prise.

Tester : inventaire 26 scripts, `git log -1` pour chacun.
Sécurité (secrets) : `SECRETS: 0` sur les scripts. Archive listée par noms, non extraite.
Business-analyst : `CLASSEMENT: A=3 B=0 C=16 D=7`.
Reviewer : `VERDICT: PASS`.
Security (relecture du lot) : `VERDICT: PASS`.

## FINDINGS

`git grep -l -F "5681"` renvoie 26 scripts `*.ps1` à la racine et 14 non-scripts (`docs/reports/evidence/LS1-inventory.txt:1-41`, `:282-299`). Les 26 datent de `6d7f02a` (2026-10-10, 22 scripts) ou `7348adb` (2026-10-07, 4 scripts : `send_webhook.ps1`, `test_import.ps1`, `test_webhook.ps1`, `try_import.ps1`). Aucun n'est cité par `requirements/`, `tests/` ni `n8n/workflows/`. Les seules citations du nom de fichier sont le rapport LB1c et le grep LB1C.

Aucun secret en dur dans les 26 scripts (jeton, clé d'API, mot de passe, URL de connexion, clé de chiffrement, Bearer). Douze scripts portent une fixture nominative de webhook ; les valeurs ne sont pas recopiées ici. `webhook_test2.ps1` écrit la réponse dans `C:\temp\wh_result.txt`. `debug3.ps1` parcourt des répertoires n8n locaux.

### Archive `n8n/backups`

Suivie par Git, commit `6d7f02a` (`docs/reports/evidence/LS1-security.txt`). `tar -tf` sans extraction. Noms utiles : `database.sqlite` (base), `config` (configuration), `ssh/` (aucun fichier enfant listé), `n8nEventLog.log` et `n8nEventLog-1.log` à `n8nEventLog-3.log`. Pas de nom `.env`, `credentials` ou `encryptionKey` dans la liste. Six JSON de workflow du même dossier sont aussi suivis ; leur contenu n'a pas été ouvert. Décision humaine séparée : ne pas extraire cette archive dans ce lot.

### Classement proposé

| Fichier | Classe | Justification |
|---|---|---|
| `infra_check.ps1` | A | Joignabilité n8n, webhook, backend 8080 et Ollama. Emplacement proposé : `tests/n8n/runtime/`. Lire `$env:N8N_BASE_URL`, défaut `http://127.0.0.1:5678`. |
| `run_s1.ps1` | A | Scénario S1 avec lecture de `outcome`, `correlationId`, `requestId`. Même emplacement et même variable d'URL. |
| `test_422.ps1` | A | Remontée du statut HTTP et du corps d'erreur. Même emplacement et même variable d'URL. |
| `api_tests.ps1` | C | Sondes GET d'API n8n sur 5681. |
| `check_n8n.ps1` | C | Compare le webhook sur 5681 et sur 5678. |
| `debug2.ps1` | C | HTTP verbeux sur la racine, `/webhook` et le webhook métier. |
| `debug3.ps1` | C | Répertoires n8n locaux et API internes. |
| `phase1_check.ps1` | C | Endpoints internes `/.ext/n8n`. |
| `send_webhook.ps1` | C | Webhook du 7 octobre, message tronqué. |
| `test_api.ps1` | C | Trois chemins API ou health. |
| `test_import.ps1` | C | Découverte de chemins GET. |
| `test_ir.ps1` | C | POST via Invoke-WebRequest, message complet. |
| `test_msgs.ps1` | C | Trois payloads webhook comparés. |
| `test_urls.ps1` | C | GET `/`, `/webhook`, `/api` sur 5681 et 5678. |
| `test_urls2.ps1` | C | GET et POST racine, complément de `test_urls.ps1`. |
| `test_webhook.ps1` | C | Précurseur du 7 octobre, parsing partiel. |
| `try_api2.ps1` | C | Trois chemins d'export de workflows. |
| `try_import.ps1` | C | Lit le workflow versionné et tente un import API. |
| `webhook_test.ps1` | C | POST minimal, corps seul. |
| `debug_webhook.ps1` | D | Doublon de `webhook_test.ps1`. |
| `infra_check2.ps1` | D | Mêmes sondes que `infra_check.ps1`, erreurs plus verbeuses. |
| `run.ps1` | D | Doublon de `webhook_test.ps1`. |
| `test_invokerequest.ps1` | D | Doublon de `check_n8n.ps1` (5678 et 5681). |
| `test_wc.ps1` | D | Même série de trois POST que `test_msgs.ps1`, client WebClient. |
| `webhook_test2.ps1` | D | Même appel que `webhook_test.ps1`, plus écriture sous `C:\temp`. |
| `wh5681.ps1` | D | Même parsing d'enveloppe que `run_s1.ps1`. |

(B) = 0. L'inventaire ne montre pas que le validateur statique `tests/n8n` couvre ces appels HTTP.

Les trois (A) ne sont pas déplacés. Le correctif `$env:N8N_BASE_URL` n'est pas appliqué. Aucun (D) n'est supprimé.

## FILES_CHANGED

Aucun fichier de projet. Écritures de ce lot :

- `docs/reports/evidence/LS1-inventory.txt`
- `docs/reports/evidence/LS1-security.txt`
- `docs/reports/LS1-scripts-racine.md`

## TESTS

Aucun script exécuté. `mvn` non lancé.

| Gate | Statut |
|---|---|
| G1 à G6 | NOT_RUN |
| G7 | PASS | Relecture du rapport. 0 secret en dur dans les 26 scripts. Archive non extraite ; risque ouvert (`database.sqlite`, `config`, `ssh/`). |
| G8 | NOT_RUN |

## RISKS

- Les 26 scripts appellent encore `http://127.0.0.1:5681`. Les exécuter maintenant rate le n8n publié sur 5678 (lot LB1c). Non exécutés ici.
- L'archive suivie `n8n/backups/n8n-data-before-loadprompt-fix.tar` contient, d'après les noms, `database.sqlite`, `config` et un répertoire `ssh/`. Elle reste dans Git. Contenu non lu.
- `webhook_test2.ps1` écrirait une réponse HTTP sous `C:\temp` s'il était lancé.
- Supprimer les (D) ou déplacer les (C) sans accord fichier par fichier est interdit (AGENTS.md §9).

## NEXT_ACTION

Décision humaine, un fichier à la fois, avant toute phase 2. Proposition en attente : conserver et paramétrer les 3 (A), archiver les 16 (C) hors de la racine, supprimer les 7 (D) seulement après accord explicite de chaque fichier. L'archive tar se décide à part, sans extraction.

## Verdicts recopiés

Business-analyst :

```
CLASSEMENT: A=3 B=0 C=16 D=7
```

Sécurité (secrets, avant relecture) :

```
SECRETS: 0
```

Reviewer :

```
VERDICT: PASS
```

Security :

```
VERDICT: PASS
```
