# LB2 — Wrapper Maven et profil Failsafe

Wrapper only-script Maven 3.9.10 et profil `it` ajoutés. Aucun `*IT` exécuté. Aucun commit. Aucun `git add` : l'index n'a pas été modifié (`git diff --cached --name-only` vide).

## STATUS

(a) `mvnw.cmd -v` : Apache Maven 3.9.10, code 0.
(b) `mvnw.cmd -B test` : 298 tests, 0 échec, code 0. G1 PASS.
(c) le profil `it` est actif seulement avec `-Pit` ; `verify -Pit -DskipITs=true` saute Failsafe. Code 0.
(d) trois fichiers de wrapper, aucun `maven-wrapper.jar`.

Reviewer : `VERDICT: PASS`.
Security : `VERDICT: PASS`. `distributionUrl` en https sur `repo.maven.apache.org`. Aucune dépendance modifiée.

## FINDINGS

### Wrapper

Commande exécutée depuis `backend/` :

```text
mvn -N -B org.apache.maven.plugins:maven-wrapper-plugin:wrapper -Dmaven=3.9.10 -Dtype=only-script
```

Maven a résolu `wrapper:3.3.4`. Le journal indique « Unpacked only-script type wrapper » et un téléchargement depuis `https://repo.maven.apache.org/maven2`. Aucun `.jar` n'a été généré sous `backend/.mvn/`. Le seul `.jar` vu ensuite est `backend/target/document-generator-1.0.0-SNAPSHOT.jar`, produit de build, ignoré par Git.

`backend/.mvn/wrapper/maven-wrapper.properties` :

```text
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.10/apache-maven-3.9.10-bin.zip
```

Fichiers (`docs/reports/evidence/LB2-wrapper-files.txt`) : `backend/mvnw` (11790 octets, LF), `backend/mvnw.cmd` (8481 octets, CRLF), `maven-wrapper.properties` (171 octets).

`mvnw.cmd -v` (`docs/reports/evidence/LB2-mvnw-version.txt`) : `Apache Maven 3.9.10 (5f519b97e944483d878815739f519b2eade0a91d)`, Java 17.0.10, `exit_code: 0`. Même version que `docs/reports/evidence/LB1-env.txt:8`.

Sous Windows, à lancer à la main une fois le fichier dans l'index. Ce lot ne l'a pas exécutée :

```text
git update-index --chmod=+x backend/mvnw
```

### Profil `it`

Seul changement de `backend/pom.xml` : le profil après `</build>` (`docs/reports/evidence/LB2-diff.txt`). Pas de `<version>` sur Failsafe. `java.version` 17 et Spring Boot 3.3.13 inchangés. Aucune dépendance ajoutée.

POM effectif, lignes de version seulement (`docs/reports/evidence/LB2-plugin-versions.txt`) : le parent gère `maven-failsafe-plugin` et `maven-surefire-plugin` en **3.2.5**, Failsafe dans `pluginManagement` uniquement. Le `verify` de ce lot le confirme : `failsafe:3.2.5` (`docs/reports/evidence/LB2-verify-skipits.txt:565`).

### Profils actifs et verify

`-Pit help:active-profiles` : `- it (source: com.adgendoc:document-generator:1.0.0-SNAPSHOT)` (`docs/reports/evidence/LB2-active-profiles.txt:15`), code 0.

Sans `-Pit` : « The following profiles are active: » puis aucune ligne de profil (`docs/reports/evidence/LB2-active-profiles.txt:39-41`), code 0.

`mvnw.cmd -B -Pit -DskipITs=true verify` : 298 tests, 0 échec (`LB2-verify-skipits.txt:555`), puis `Tests are skipped` pour `integration-test` et `verify` (`:566`, `:569`). Aucune occurrence de `HttpVerticalSliceIT` ni `PostgresPersistenceIT`. `BUILD SUCCESS`, `exit_code: 0`.

### Gitignore et attributs

`.gitignore` n'a pas été modifié. `git check-ignore -v -- backend/target` répond `.gitignore:2:target/`. La ligne 1 contient déjà `backend/target/`.

`.gitattributes` a été créé (il n'existait pas), avec exactement :

```text
backend/mvnw text eol=lf
backend/mvnw.cmd text eol=crlf
```

### distributionSha256Sum — lot de suivi, non appliqué

La propriété attend un SHA-256 de l'archive `apache-maven-3.9.10-bin.zip`. Maven Central publie le SHA-512 à côté de cette archive (`…-bin.zip.sha512`), pas un SHA-256 de référence. Démarche proposée, à faire dans un lot séparé, sans l'écrire ici :

1. Télécharger l'archive à l'URL `distributionUrl` et le fichier `.sha512` voisin, tous deux en `https` sur `repo.maven.apache.org`.
2. Calculer le SHA-512 de l'archive et le comparer au contenu publié. S'il diffère, STOP.
3. Calculer le SHA-256 des mêmes octets.
4. Ajouter `distributionSha256Sum=<sha256>` dans `maven-wrapper.properties`.
5. Relancer `mvnw.cmd -v` : le wrapper doit accepter l'archive. Ne pas inventer l'empreinte.

## FILES_CHANGED

- `backend/mvnw`
- `backend/mvnw.cmd`
- `backend/.mvn/wrapper/maven-wrapper.properties`
- `backend/pom.xml` (profil `it` seul)
- `.gitattributes` (créé)
- `docs/reports/evidence/LB2-mvnw-version.txt`
- `docs/reports/evidence/LB2-mvn-test.txt`
- `docs/reports/evidence/LB2-active-profiles.txt`
- `docs/reports/evidence/LB2-verify-skipits.txt`
- `docs/reports/evidence/LB2-wrapper-files.txt`
- `docs/reports/evidence/LB2-plugin-versions.txt`
- `docs/reports/evidence/LB2-diff.txt`
- `docs/reports/LB2-wrapper-failsafe.md`

`.gitignore` non modifié.

## TESTS

| Contrôle | Statut |
|---|---|
| (a) `mvnw.cmd -v` | PASS — 3.9.10, code 0 |
| (b) `mvnw.cmd -B test` | PASS — 298, 0 échec, code 0, mur 41,255 s. Aucun `*IT` |
| (c) profils actifs | PASS — `it` présent avec `-Pit`, absent sans |
| (c) `verify -Pit -DskipITs=true` | PASS — Failsafe 3.2.5 saute les tests, code 0, mur 38,805 s |
| (d) fichiers wrapper | PASS — only-script, aucun `maven-wrapper.jar` |
| G1 | PASS — (b) code 0 |
| G2 | NOT_RUN — `*IT` non exécutés, Docker non autorisé |
| G3, G4, G5, G6, G8 | NOT_RUN |
| G7 | PASS — https officiel, aucun secret, dépendances inchangées |

## RISKS

- Sans `distributionSha256Sum`, le premier lancement de `mvnw` ne vérifie pas l'empreinte de l'archive Maven. Démarche de suivi ci-dessus, non appliquée.
- `git update-index --chmod=+x backend/mvnw` n'a pas été exécuté. Le bit exécutable n'est pas dans l'index.
- `-Pit` sans `-DskipITs=true` lancerait `HttpVerticalSliceIT` et `PostgresPersistenceIT`. Ce lot ne l'a pas fait.
- `docs/reports/evidence/LB2-mvn-test.txt` reprend la sortie Maven des tests de sanitisation déjà existants : fixtures synthétiques, et le nom du compte Windows dans une ligne de démarrage Spring. Ce ne sont pas des secrets du lot. Les valeurs ne sont pas recopiées ici.

## NEXT_ACTION

Aucun commit. Lot de suivi pour `distributionSha256Sum`. Rendre `backend/mvnw` exécutable dans Git avec `git update-index --chmod=+x backend/mvnw`, à la main, après l'avoir mis dans l'index.

## Verdicts recopiés

Reviewer ([relecture LB2](02dddc49-830f-4197-9cb6-4c63d4d4255d)) :

```
VERDICT: PASS
```

Security ([revue LB2](45780989-1eda-4982-b058-31b0097c3c5e)) :

```
VERDICT: PASS
```
