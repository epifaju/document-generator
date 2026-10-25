# LS1b — Scripts racine : déplacement, suppressions, garde d'hôte

Phase 2 appliquée. Les trois scripts de `tests/n8n/runtime/` n'ont pas été exécutés. Ce tour n'a fait aucun commit et n'a pas touché l'index de `n8n/backups/`. `git rm -f` n'a pas été utilisé.

Avant `git rm`, `git status --short` sur les 23 chemins était vide (aucune modification locale).

## STATUS

Les déplacements et les 23 suppressions sont dans le commit `115bc7f`. Ce tour ajoute la garde d'hôte, acceptée, dans les trois scripts. Elle est indexée (`git diff --cached --stat` : 4 lignes ajoutées dans chaque fichier). L'extrait de la garde, isolé, a été exécuté sans appel réseau. Les scripts eux-mêmes ne l'ont pas été.

Tester (références) : `REFS: STOP=0 OK=23`.
Reviewer : `VERDICT: PASS`.
Security : `VERDICT: PASS`. Aucun secret dans les 3 scripts. Garde d'hôte avant tout `WebClient`. Sondes 8080 et 11434 sur `127.0.0.1`.

## FINDINGS

### Conservés

`git mv` vers `tests/n8n/runtime/`, puis base lue ainsi dans les trois fichiers :

```powershell
$n8nBase = if ($env:N8N_BASE_URL) { $env:N8N_BASE_URL } else { 'http://127.0.0.1:5678' }
$n8nBase = $n8nBase.TrimEnd('/')
$n8nHost = ([Uri]$n8nBase).Host
if ($n8nHost -ne '127.0.0.1' -and $n8nHost -ne 'localhost' -and $env:N8N_BASE_URL_ALLOW_REMOTE -ne '1') {
    throw "Refus : N8N_BASE_URL doit viser 127.0.0.1 ou localhost. Pour un autre hôte, définir N8N_BASE_URL_ALLOW_REMOTE=1."
}
```

Le bloc est identique dans les trois fichiers, juste après `.TrimEnd('/')` et avant `New-Object System.Net.WebClient` (`tests/n8n/runtime/infra_check.ps1:3-9`, `run_s1.ps1:3-10`, `test_422.ps1:3-10`). L'URL du webhook est `"$n8nBase/webhook/document-generation"`. Les sondes `http://127.0.0.1:8080/actuator/health` et `http://127.0.0.1:11434/api/tags` de `infra_check.ps1` sont inchangées. Chaque script a en tête deux lignes : l'usage, et « G6 : exécution soumise à accord explicite ».

`PSScriptAnalyzer` n'est pas installé. Rien n'a été installé. L'analyseur du langage PowerShell, sans exécution, donne 0 erreur sur les trois fichiers, avant et après la garde (`docs/reports/evidence/LS1B-parse.txt`). Aucune occurrence de `5681` dans `tests/n8n/runtime/`.

### Supprimés de l'arbre (23)

Classe C : `api_tests.ps1`, `check_n8n.ps1`, `debug2.ps1`, `debug3.ps1`, `phase1_check.ps1`, `send_webhook.ps1`, `test_api.ps1`, `test_import.ps1`, `test_ir.ps1`, `test_msgs.ps1`, `test_urls.ps1`, `test_urls2.ps1`, `test_webhook.ps1`, `try_api2.ps1`, `try_import.ps1`, `webhook_test.ps1`.

Classe D : `debug_webhook.ps1`, `infra_check2.ps1`, `run.ps1`, `test_invokerequest.ps1`, `test_wc.ps1`, `webhook_test2.ps1`, `wh5681.ps1`.

Au moment de l'indexation, `git diff --cached --name-status` comptait 23 lignes `D` et 3 lignes `R` (`docs/reports/evidence/LS1B-status.txt`). Ce contenu est dans `115bc7f`. L'index de ce tour ne contient que les trois modifications de la garde. L'historique Git conserve les fichiers retirés. Les citations indexées avant suppression étaient seulement `docs/reports/LB1c-gardiens.md` et `docs/reports/evidence/LB1C-grep-5681.txt` (`docs/reports/evidence/LS1B-refs.txt`).

### Garde d'hôte — écrite et vérifiée hors script

Le texte accepté est celui du bloc ci-dessus. Seul cet extrait a été exécuté, isolé dans une fonction sans `Invoke-*` et sans appel réseau. Sortie brute : `docs/reports/evidence/LS1B-guard-test.txt`.

| Entrée | Résultat observé |
|---|---|
| `http://127.0.0.1:5678` | accepté, host=127.0.0.1 |
| `http://localhost:5678` | accepté, host=localhost |
| `http://127.0.0.1@exemple.test:5678` | refusé |
| `http://127.0.0.1.exemple.test:5678` | refusé |
| `http://exemple.test:5678` | refusé |
| `http://exemple.test:5678` avec `N8N_BASE_URL_ALLOW_REMOTE=1` | accepté, host=exemple.test |
| valeur vide | accepté, défaut `http://127.0.0.1:5678` |

### Nettoyage manuel

`webhook_test2.ps1` écrivait la réponse dans `C:\temp\wh_result.txt`. Ce fichier, s'il existe sur le poste, n'a pas été supprimé. À effacer à la main.

### Hors lot

Ce tour n'a ni ré-indexé ni désindexé `n8n/backups/`. Les rapports non suivis des lots précédents restent non suivis. `docs/reports/evidence/LS1B-guard-test.txt` est nouveau et non indexé.

## FILES_CHANGED

- `tests/n8n/runtime/infra_check.ps1` (renommé depuis la racine, base d'URL)
- `tests/n8n/runtime/run_s1.ps1` (idem)
- `tests/n8n/runtime/test_422.ps1` (idem)
- 23 scripts de la racine retirés de l'arbre par `git rm` (sans `-f`)
- `docs/reports/evidence/LS1B-refs.txt`
- `docs/reports/evidence/LS1B-status.txt`
- `docs/reports/evidence/LS1B-parse.txt` (seconde lecture, 0 erreur)
- `docs/reports/evidence/LS1B-guard-test.txt` (extrait isolé, non indexé)
- `docs/reports/LS1b-scripts-racine.md`

## TESTS

Aucun script exécuté. `mvn` non lancé (aucun fichier backend).

| Contrôle | Statut |
|---|---|
| `git grep` des 23 noms avant suppression | PASS — STOP=0 |
| Parse PowerShell des 3 scripts | PASS — 0 erreur avant et après la garde. PSScriptAnalyzer NOT_RUN (absent) |
| Extrait isolé de la garde (7 entrées) | PASS — sorties conformes dans `LS1B-guard-test.txt`. Les scripts n'ont pas été lancés |
| `mvn -B test` | NOT_RUN |
| G1 à G6, G8 | NOT_RUN |
| G6 runtime des 3 scripts | NOT_RUN — exécution soumise à accord explicite |
| G7 | PASS — revue sécurité de la garde : aucun secret, hôtes n8n refusés hors boucle locale sauf `N8N_BASE_URL_ALLOW_REMOTE=1` |

## RISKS

- Pendant LS1b, la suppression de `n8n/backups/n8n-data-before-loadprompt-fix.tar` s'est retrouvée dans l'index sans faire partie de la commande `git rm` des 23 scripts. Elle a été retirée de l'index (`git restore --staged`) pour que ce lot ne la porte pas. L'utilisateur la refera dans un commit séparé. Ce tour n'a ni ré-indexé ni désindexé `n8n/backups/`.
- `C:\temp\wh_result.txt` peut encore contenir une ancienne réponse de webhook. À effacer à la main.
- Les sondes 8080 et 11434 restent en dur sur `127.0.0.1`. Un hôte n8n autre que `127.0.0.1` ou `localhost` n'est atteint que si `N8N_BASE_URL_ALLOW_REMOTE` vaut `1`.

## NEXT_ACTION

Aucun commit. Effacer à la main `C:\temp\wh_result.txt` s'il est présent. G6 des trois scripts reste soumis à un accord explicite. L'index de ce tour ne porte que les trois scripts ; le rapport et `LS1B-guard-test.txt` ne sont pas indexés.

## Verdicts recopiés

Références :

```
REFS: STOP=0 OK=23
```

Reviewer ([relecture garde](8989fb3b-054c-4fdc-81ea-4434e87d15ec)) :

```
VERDICT: PASS
```

`git diff --cached` n'a pas été exécuté par le reviewer (mode Ask). L'orchestrateur l'a exécuté : l'index ne contient que `M` sur les trois scripts de `tests/n8n/runtime/`, aucun chemin `n8n/backups/`.

Security ([revue garde](14ddaeae-b628-45d2-a806-846f3c40976c)) :

```
VERDICT: PASS
```
