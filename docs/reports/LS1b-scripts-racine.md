# LS1b — Scripts racine : déplacement et suppressions

Phase 2 appliquée. Aucun script exécuté. Aucun commit. `git rm -f` n'a pas été utilisé. `n8n/backups/` n'est pas modifié par ce lot : l'archive `n8n-data-before-loadprompt-fix.tar` reste suivie.

Avant `git rm`, `git status --short` sur les 23 chemins était vide (aucune modification locale).

## STATUS

PASS côté index : 3 renommages et 23 suppressions. La garde d'hôte est proposée et n'est pas écrite.

Tester (références) : `REFS: STOP=0 OK=23`.
Reviewer : `VERDICT: PASS`.
Security : `VERDICT: PASS`. Aucun secret dans les 3 scripts. Base n8n lue dans `$env:N8N_BASE_URL`.

## FINDINGS

### Conservés

`git mv` vers `tests/n8n/runtime/`, puis base lue ainsi dans les trois fichiers :

```powershell
$n8nBase = if ($env:N8N_BASE_URL) { $env:N8N_BASE_URL } else { 'http://127.0.0.1:5678' }
$n8nBase = $n8nBase.TrimEnd('/')
```

L'URL du webhook est `"$n8nBase/webhook/document-generation"`. Les sondes `http://127.0.0.1:8080/actuator/health` et `http://127.0.0.1:11434/api/tags` de `infra_check.ps1` sont inchangées. Chaque script a en tête deux lignes : l'usage, et « G6 : exécution soumise à accord explicite ».

`PSScriptAnalyzer` n'est pas installé. Rien n'a été installé. L'analyseur du langage PowerShell, sans exécution, donne 0 erreur sur les trois fichiers (`docs/reports/evidence/LS1B-parse.txt`). Aucune occurrence de `5681` dans `tests/n8n/runtime/`.

### Supprimés de l'arbre (23)

Classe C : `api_tests.ps1`, `check_n8n.ps1`, `debug2.ps1`, `debug3.ps1`, `phase1_check.ps1`, `send_webhook.ps1`, `test_api.ps1`, `test_import.ps1`, `test_ir.ps1`, `test_msgs.ps1`, `test_urls.ps1`, `test_urls2.ps1`, `test_webhook.ps1`, `try_api2.ps1`, `try_import.ps1`, `webhook_test.ps1`.

Classe D : `debug_webhook.ps1`, `infra_check2.ps1`, `run.ps1`, `test_invokerequest.ps1`, `test_wc.ps1`, `webhook_test2.ps1`, `wh5681.ps1`.

`git diff --cached --name-status` : 23 lignes `D` et 3 lignes `R` (`docs/reports/evidence/LS1B-status.txt`). L'historique Git conserve ces fichiers. Les citations indexées avant suppression étaient seulement `docs/reports/LB1c-gardiens.md` et `docs/reports/evidence/LB1C-grep-5681.txt` (`docs/reports/evidence/LS1B-refs.txt`).

### Garde d'hôte — proposée, non écrite

À placer dans les trois scripts, juste après `$n8nBase = $n8nBase.TrimEnd('/')`, si vous l'acceptez :

```powershell
$n8nHost = ([Uri]$n8nBase).Host
if ($n8nHost -ne '127.0.0.1' -and $n8nHost -ne 'localhost' -and $env:N8N_BASE_URL_ALLOW_REMOTE -ne '1') {
    throw "Refus : N8N_BASE_URL doit viser 127.0.0.1 ou localhost. Pour un autre hôte, définir N8N_BASE_URL_ALLOW_REMOTE=1."
}
```

Texte du refus : `Refus : N8N_BASE_URL doit viser 127.0.0.1 ou localhost. Pour un autre hôte, définir N8N_BASE_URL_ALLOW_REMOTE=1.`

### Nettoyage manuel

`webhook_test2.ps1` écrivait la réponse dans `C:\temp\wh_result.txt`. Ce fichier, s'il existe sur le poste, n'a pas été supprimé. À effacer à la main.

### Hors lot

`.gitignore` a deux lignes non indexées (`n8n/backups/*.tar`, `n8n/backups/**/database.sqlite`). Ce lot ne les a pas écrites et ne les a pas annulées. Les rapports non suivis des lots précédents restent non suivis.

## FILES_CHANGED

- `tests/n8n/runtime/infra_check.ps1` (renommé depuis la racine, base d'URL)
- `tests/n8n/runtime/run_s1.ps1` (idem)
- `tests/n8n/runtime/test_422.ps1` (idem)
- 23 scripts de la racine retirés de l'arbre par `git rm` (sans `-f`)
- `docs/reports/evidence/LS1B-refs.txt`
- `docs/reports/evidence/LS1B-status.txt`
- `docs/reports/evidence/LS1B-parse.txt`
- `docs/reports/LS1b-scripts-racine.md`

## TESTS

Aucun script exécuté. `mvn` non lancé (aucun fichier backend).

| Contrôle | Statut |
|---|---|
| `git grep` des 23 noms avant suppression | PASS — STOP=0 |
| Parse PowerShell des 3 scripts | PASS — 0 erreur. PSScriptAnalyzer NOT_RUN (absent) |
| `mvn -B test` | NOT_RUN |
| G1 à G6, G8 | NOT_RUN |
| G6 runtime des 3 scripts | NOT_RUN — exécution soumise à accord explicite |
| G7 | PASS | Aucun secret dans les 3 scripts. Base n8n en variable d'environnement. Archive non supprimée. |

## RISKS

- Sans la garde d'hôte, `$env:N8N_BASE_URL` peut viser un hôte autre que la boucle locale. Les sondes 8080 et 11434, elles, restent sur `127.0.0.1`.
- `C:\temp\wh_result.txt` peut encore contenir une ancienne réponse de webhook.
- `.gitignore` ignore déjà `n8n/backups/*.tar` dans la copie de travail, mais l'archive reste suivie tant qu'elle n'est pas retirée de l'index. Ce n'est pas ce lot.

## NEXT_ACTION

Accepter ou refuser la garde d'hôte ci-dessus. Elle n'est pas dans les scripts. L'archive `n8n/backups/` se traite dans son lot. Effacer à la main `C:\temp\wh_result.txt` s'il est présent.

## Verdicts recopiés

Références :

```
REFS: STOP=0 OK=23
```

Reviewer :

```
VERDICT: PASS
```

Security :

```
VERDICT: PASS
```
