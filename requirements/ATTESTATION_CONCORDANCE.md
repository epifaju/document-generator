# ATTESTATION_CONCORDANCE — Contrat métier du document pilote

| Rubrique | Valeur |
|---|---|
| documentType | `ATTESTATION_CONCORDANCE` |
| displayName | Attestation de concordance |
| Version du contrat | 1.0 |
| Itération | 1 — fondations techniques + document pilote uniquement |
| Statut du document | **PROPOSÉ** — validation humaine requise avant implémentation |
| Rédacteur | agent `business-analyst` |
| Langue de la documentation | Français (clés techniques en anglais/camelCase) |

> Rappel de règle (AGENTS.md §4.2) : le système ne doit **jamais** inventer de
> données administratives. Toutes les valeurs des champs de ce contrat sont
> des **entrées fournies par l'utilisateur** (ou extraites de ses propos par
> l'IA puis confirmées), sauf les champs explicitement marqués « généré par le
> système ».

---

## 1. OBJET (PURPOSE)

L'attestation de concordance est un document administratif qui **atteste la
concordance entre une forme de nom telle qu'elle est écrite dans un document
source (forme erronée) et la forme correcte / officielle du nom d'un
requérant**.

Elle établit un lien explicite entre :

- `nomIncorrect` : la forme erronée effectivement attestée dans le document
  source (faute de transcription, variante orthographique, translittération,
  homonymie…) ;
- `nomCorrect` : la forme correcte / officielle du nom du requérant.

Le document est produit à partir d'un **modèle DOCX officiel approuvé**
(fusion déterministe des données validées dans le template), jamais rédigé
librement par un LLM (AGENTS.md §4.1).

**REQUIRES_BUSINESS_VALIDATION** — le libellé officiel, la formulation légale,
l'autorité émettrice et les mentions obligatoires du document ne sont pas
disponibles dans le dépôt à ce jour. Ils doivent être fournis/validés par un
responsable administratif avant toute implémentation du template.

---

## 2. PUBLIC CIBLE / CAS D'USAGE

### 2.1 Public cible

- Requérants particuliers dont un document officiel (acte, passeport, diplôme,
  dossier bancaire, dossier de visa, inscription scolaire, etc.) porte une
  forme de nom différente de la forme officielle.
- Agents d'accueil / opérateurs conversationnels (n8n) agissant pour le
  compte d'un requérant.

### 2.2 Cas d'usage

1. L'utilisateur demande en langage naturel : « Génère une attestation de
   concordance pour Maria Gomes, née le 12/05/1985 à Bissau… ».
2. L'IA extrait les champs et les rend en JSON structuré (`ExtractionResult`).
3. Le backend valide de manière déterministe les champs, demande les informations
   manquantes, normalise les données.
4. Une fois `VALIDATED`, le backend fusionne les données dans le template
   DOCX officiel et retourne le document généré.

### 2.3 Hypothèses et limites de l'itération 1

- Seul `ATTESTATION_CONCORDANCE` est supporté (AGENTS.md §5). Tout autre
  documentType est rejeté.
- PDF : hors périmètre de l'itération 1 (génération DOCX uniquement).
- L'authentification/autorisation est hors périmètre de l'itération 1
  (voir `requirements/API_CONTRACTS.md`).
- Les champs décrits ci-dessous sont les champs **candidats** déduits du
  besoin exprimé ; ils ne constituent **pas** une liste légalement suffisante
  (voir §12, point OQ-1).

---

## 3. CHAMPS OBLIGATOIRES (REQUIRED_FIELDS)

Pipeline déterministe appliqué dans cet ordre :
**normalisation (§7) → validation (présence → format → cohérence inter-champs)**.
Un champ réduit à une chaîne vide après normalisation est considéré comme
**absent** (→ `MISSING_INFORMATION`), pas comme invalide.

| Clé technique | Libellé | Type | Format attendu | Exemple valide | Règle de validation | Message d'erreur (code → message FR) |
|---|---|---|---|---|---|---|
| `prenom` | Prénom | String | 1–100 caractères après normalisation ; lettres (accents inclus), espaces, apostrophes `'`, traits d'union `-` ; motif : `^[A-Za-zÀ-ÖØ-öø-ÿ' -]{1,100}$` | `Maria` | Obligatoire ; non vide après trim ; motif respecté | `ERR_CHAMP_OBLIGATOIRE_ABSENT` → « Le champ « prenom » est obligatoire. » · `ERR_FORMAT_TEXTE_INVALIDE` → « Le champ « prenom » contient des caractères non autorisés. » · `ERR_LONGUEUR_DEPASSEE` → « Le champ « prenom » dépasse la longueur maximale de 100 caractères. » |
| `nom` | Nom | String | 1–100 caractères après normalisation ; même motif que `prenom` | `Gomes` | Obligatoire ; non vide après trim ; motif respecté | `ERR_CHAMP_OBLIGATOIRE_ABSENT` → « Le champ « nom » est obligatoire. » · `ERR_FORMAT_TEXTE_INVALIDE` → « Le champ « nom » contient des caractères non autorisés. » · `ERR_LONGUEUR_DEPASSEE` → « Le champ « nom » dépasse la longueur maximale de 100 caractères. » |
| `dateNaissance` | Date de naissance | Date | ISO `yyyy-MM-dd` après normalisation ; formats d'entrée acceptés : `yyyy-MM-dd`, `dd/MM/yyyy`, `d/M/yyyy`, `dd-MM-yyyy` | `1985-05-12` (saisie acceptée : `12/05/1985`) | Obligatoire ; date de calendrier réelle (mois/jour cohérents, années bissextiles gérées) ; **pas postérieure à la date du jour (UTC)** | `ERR_DATE_FORMAT_INVALIDE` → « Format de date invalide. Formats acceptés : yyyy-MM-dd ou dd/MM/yyyy. » · `ERR_DATE_CALENDRIER_INVALIDE` → « La date de naissance n'est pas une date de calendrier valide. » · `ERR_DATE_FUTUR` → « La date de naissance ne peut pas être postérieure à la date du jour. » |
| `lieuNaissance` | Lieu de naissance | String | 1–200 caractères après normalisation ; lettres (accents inclus), espaces, `'`, `-`, `,`, `.`, `(`, `)` ; motif : `^[A-Za-zÀ-ÖØ-öø-ÿ'’\-,\.() ]{1,200}$` | `Bissau` | Obligatoire ; non vide après trim ; motif respecté | `ERR_CHAMP_OBLIGATOIRE_ABSENT` → « Le champ « lieuNaissance » est obligatoire. » · `ERR_FORMAT_TEXTE_INVALIDE` → « Le champ « lieuNaissance » contient des caractères non autorisés. » |
| `nomIncorrect` | Forme erronée attestée | String | 1–200 caractères après normalisation ; même motif que `nom` | `Maria Gomez` | Obligatoire ; non vide ; **doit différer de `nomCorrect` après normalisation** | `ERR_CHAMP_OBLIGATOIRE_ABSENT` → « Le champ « nomIncorrect » est obligatoire. » · `ERR_NOM_CONCORDANCE_IDENTIQUE` → « Les formes « nomIncorrect » et « nomCorrect » doivent différer : rien à attester. » |
| `nomCorrect` | Forme correcte / officielle | String | 1–200 caractères après normalisation ; même motif que `nom` | `Maria Gomes` | Obligatoire ; non vide ; **doit différer de `nomIncorrect` après normalisation** | `ERR_CHAMP_OBLIGATOIRE_ABSENT` → « Le champ « nomCorrect » est obligatoire. » · `ERR_NOM_CONCORDANCE_IDENTIQUE` → « Les formes « nomIncorrect » et « nomCorrect » doivent différer : rien à attester. » |

**Champs obligatoires dont l'absence empêche toute génération :**

```
missingFields = ["prenom", "nom", "dateNaissance", "lieuNaissance",
                 "nomIncorrect", "nomCorrect"]
status = MISSING_INFORMATION   # dès qu'au moins l'un de ces champs est absent
```

---

## 4. CHAMPS OPTIONNELS (OPTIONAL_FIELDS)

| Clé technique | Libellé | Type | Format attendu | Exemple valide | Règle de validation | Message d'erreur (code → message FR) |
|---|---|---|---|---|---|---|
| `sexe` | Sexe | Enum | `M` \| `F` (casse insensible en entrée) | `F` | Optionnel ; si présent, valeur de l'enum ; absent/vidé → ignoré | `ERR_ENUM_INVALIDE` → « Valeur invalide pour « sexe ». Valeurs acceptées : M, F. » |
| `nationalite` | Nationalité | String | 1–100 caractères ; texte libre **saisi par l'utilisateur** (jamais déduit par le système) | `Française` (exemple de format uniquement) | Optionnel ; si non vide après trim : motif texte `^[\p{L}][\p{L}\s'\-\.]{0,99}$` | `ERR_FORMAT_TEXTE_INVALIDE` → « Le champ « nationalite » contient des caractères non autorisés. » |
| `documentSourceReference` | Référence du document source | String | 1–100 caractères ; texte libre **communiqué par le requérant** | `ACTE-1985-000123` (référence fournie par l'utilisateur, jamais générée) | Optionnel ; si non vide : motif `^[A-Za-z0-9À-ÖØ-öø-ÿ'’\-,\.\/ ]{1,100}$` | `ERR_FORMAT_TEXTE_INVALIDE` → « Le champ « documentSourceReference » contient des caractères non autorisés. » |
| `langueDocument` | Langue du document | Enum | `FR` \| `PT` \| `EN` (casse insensible en entrée) ; **défaut système `FR` si absent** | `PT` | Optionnel ; si présent : valeur de l'enum. Le défaut est un choix technique de template, **pas une donnée administrative** | `ERR_ENUM_INVALIDE` → « Valeur invalide pour « langueDocument ». Valeurs acceptées : FR, PT, EN. » |
| `demandeur` | Demandeur (si différent du requérant) | String | 1–200 caractères ; même motif que `nom` | `João Silva` | Optionnel ; si non vide : motif respecté | `ERR_FORMAT_TEXTE_INVALIDE` → « Le champ « demandeur » contient des caractères non autorisés. » |
| `contactEmail` | Adresse e-mail de contact | String | Format e-mail RFC 5322 simplifié : `^[^\s@]+@[^\s@]+\.[^\s@]{2,}$`, max 254 caractères | `maria.gomes@example.com` | Optionnel ; si non vide : motif respecté | `ERR_EMAIL_INVALIDE` → « Le champ « contactEmail » n'est pas une adresse e-mail valide. » |
| `contactTelephone` | Téléphone de contact | String | `^\+?[0-9][0-9\s\-.]{5,19}$` (6–20 caractères) | `+33612345678` | Optionnel ; si non vide : motif respecté | `ERR_TELEPHONE_INVALIDE` → « Le champ « contactTelephone » n'est pas un numéro de téléphone valide. » |
| `motif` | Motif de la demande | String | 1–500 caractères ; texte libre saisi par l'utilisateur | `Dossier bancaire` | Optionnel ; si non vide : motif texte. **REQUIRES_BUSINESS_VALIDATION** : ce champ figure-t-il dans le document officiel ? | `ERR_LONGUEUR_DEPASSEE` → « Le champ « motif » dépasse la longueur maximale de 500 caractères. » |

---

## 5. CHAMPS RÉSERVÉS / CHAMPS INTERDITS

### 5.1 Champs générés par le système (jamais fournis en entrée)

| Clé technique | Généré par | Format | Règle |
|---|---|---|---|
| `referenceDemande` | Backend à la persistance | UUID v4 (minuscules) | **Identique au `requestId` de l'API** (un seul identifiant de demande). S'il est présent dans le payload `data` → rejet `ERR_CHAMP_RESERVE`. |
| `dateGeneration` | Backend au moment de la génération | `yyyy-MM-dd` (UTC) | Date technique de génération ; ce n'est **pas** une donnée administrative inventée. |
| `requestId`, `documentId`, `status` | Backend | UUID v4 / enum | Métadonnées API, hors bloc `data`. |

### 5.2 Interdiction absolue d'inventer des données (AGENTS.md §4.2)

Le système (backend, n8n, LLM) ne doit **JAMAIS** produire, déduire ou
« compléter » :

- noms, prénoms, dates, adresses ;
- identifiants, numéros de passeport ;
- lieux de naissance, nationalités ;
- références administratives ;
- énoncés légaux / mentions du document ;
- toute information manquante du demandeur.

**Toute donnée manquante ⇒ `status = MISSING_INFORMATION` + question posée à
l'utilisateur.** Aucune valeur par défaut « raisonnable » n'est jamais
appliquée à un champ obligatoire.

### 5.3 Clés inconnues interdites

Le bloc `data` accepte **uniquement** les clés listées aux §3 et §4
(`additionalProperties: false`). Toute autre clé est rejetée :

- `ERR_CHAMP_INCONNU` → « Champ inconnu ou non autorisé pour ce type de
  document : « <clé> ». »

Motivation : empêcher toute tentative de faire transiter des données
administratives hors schéma.

---

## 6. RÈGLES DE VALIDATION (VALIDATION_RULES)

Ordre déterministe d'évaluation (le premier échec bloquant termine
l'évaluation) :

1. **V enveloppe** — JSON bien formé, types de base corrects,
   `documentType == "ATTESTATION_CONCORDANCE"` et supporté.
2. **V clés** — aucune clé inconnue dans `data` ; aucune clé réservée
   (`referenceDemande`).
3. **V normalisation** — règles du §7 appliquées.
4. **V présence** — les 6 champs obligatoires ne sont pas absents
   (vide/null/espaces ⇒ absent) → sinon `MISSING_INFORMATION` (HTTP 422 sur POST /requests et POST .../validate ; HTTP 200 avec status = MISSING_INFORMATION sur le PATCH — ADR-002).
5. **V format** — motifs, longueurs, dates, enums, e-mail, téléphone.
6. **V cohérence inter-champs** — `nomIncorrect ≠ nomCorrect`
   (comparaison après normalisation, insensible à la casse et aux espaces).
7. Sinon → `VALIDATED`.

Règles explicites :

- `dateNaissance` : calendrier réel **et** `dateNaissance <= dateDuJour (UTC)`.
- `nomIncorrect`/`nomCorrect` : comparaison après normalisation **en
  minuscules et sans espaces superflus** (ex. `« Maria Gomez »` vs
  `« maria gomez »` ⇒ identiques ⇒ refus).
- Champ optionnel vide/null ⇒ **ignoré** (pas d'erreur, pas de valeur
  par défaut, sauf `langueDocument` ⇒ défaut `FR`).
- L'IA ne peut jamais faire échouer ou passer une validation : la validation
  est exclusivement déterministe côté backend (AGENTS.md §4.1, §12).
- `confidence` de l'extraction (le cas échéant) n'est **pas** utilisé pour
  contourner la validation métier (seuil éventuel : voir OQ-5).

### 6.1 Règles d'attribution identité / concordance (A-1, A-5, D-1)

> Portée : `ATTESTATION_CONCORDANCE` uniquement. Règles **déterministes**,
> sans aucun appel LLM (ancrage `N8N_CONTRACTS.md` §5 : budget §4 inchangé
> ≤ 19 appels/exécution, repli sur les codes métier existants §3.4). Elles
> précisent §3 (familles de champs) et §5.2 (interdiction d'inventer) ;
> l'ordre de validation V1–V7 (§6 ci-dessus) et les règles N1–N9 (§7) sont
> inchangés. Elles **ne ferment pas** OQ-7 (§12).

#### 6.1.1 A-1 — familles de champs

| Famille | Champs | Sens business |
|---|---|---|
| `IDENTITE` | `prenom`, `nom` | identité de la personne pour qui l'attestation est établie |
| `CONCORDANCE` | `nomIncorrect`, `nomCorrect` | formes attestées à concorder : forme erronée ↔ forme correcte |

**Règle A-1** — lorsque la demande exprime **explicitement** une forme
incorrecte et une forme correcte (marqueurs §6.1.2), l'extraction **DOIT**
renseigner `nomIncorrect` / `nomCorrect` avec les **formes complètes**
fournies par l'utilisateur ; elle **NE DOIT PAS** découper ces formes ni en
répartir les tokens pour remplir `prenom` / `nom`.

#### 6.1.2 A-5 — pas d'inférence d'identité depuis un segment concordance

**Segment concordance** : portion du message associée à un marqueur de
concordance ; frontière exacte au tableau ci-dessous. **Union des segments
concordance** : réunion de toutes les plages de tokens calculées selon
cette frontière (marqueurs et séparateurs de clause exclus).

**Liste fermée des marqueurs de concordance** (décision 1) —
reconnaissance après normalisation de comparaison (§6.1.3, étape 2) par
**contiguïté de tokens**, en égalité **entière** de token (jamais de
correspondance par sous-chaîne brute) ; le marqueur peut être entouré
d'autres tokens (« le nom correct est X » reconnaît bien `nom correct`) :

| Canal | Marqueurs acceptés (forme normalisée : minuscules, accents pliés, espaces réduits) |
|---|---|
| `nomIncorrect` | `nom incorrect` · `forme incorrecte` · `forme erronee` (« forme erronée ») · `nom errone` (« nom erroné ») · `nom figurant a l'etat civil est incorrect` (« nom figurant à l'état civil est incorrect ») · `nom figure a l'etat civil est incorrect` (« nom figuré à l'état civil est incorrect ») · `nom a l'etat civil est incorrect` (« nom à l'état civil est incorrect ») |
| `nomCorrect` | `nom correct` · `forme correcte` · `forme officielle` · `nom officiel` |

Règles de reconnaissance :

- égalité **entière** de token : `incorrect` ≠ `correct`, `erronee` ≠
  `correcte` — les deux canaux ne peuvent jamais se confondre ;
- en cas de marqueurs débutant au même token : le **plus long** prime ;
- toute paraphrase **hors** de cette liste n'est pas reconnue (limite
  assumée, §6.1.5) ;
- `je suis` **seul n'est jamais un marqueur** (ambigu avec « je suis
  né(e) le… »).

> **REQUIRES_BUSINESS_VALIDATION** : cette liste fermée est un choix de
> détection (pas une règle administrative vérifiée) ; extension des
> formulations officielles à valider par un responsable métier — NON
> BLOQUANT : une formulation non reconnue reste un faux négatif sans
> effet sur les codes ni sur les champs persistés.

**Frontière exacte d'un segment** (décision 3) — règle identique pour tout
marqueur (concordance ou identité) :

| Élément | Règle |
|---|---|
| Début | fin du dernier token du marqueur → espaces sautés → `:` éventuel (au plus 1) sauté → espaces sautés → verbe éventuel parmi la liste fermée `est`, `sont` (au plus 1 token) sauté → espaces sautés |
| Fin (exclusive) | le premier parmi : (a) séparateur de clause `« ; »`, `« . »`, `« ! »`, `« ? »`, `« , »` ; (b) début d'un autre marqueur (concordance ou identité) ; (c) fin du message |
| Séparateur | n'appartient **pas** au segment |
| Après calcul | trim (N1) + espaces réduits (N2) ; 0 token ⇒ segment **vide** ⇒ aucun champ renseigné (jamais de valeur supposée) |

**Déclaration explicite d'identité** (décision 5) — seules sources
acceptées pour `prenom` / `nom`. **Étiquettes ID-1 / ID-2 = sources
d'identité**, distinctes des endpoints n8n `E1`–`E8` (homonymie évitée) :

- **ID-1 — marqueur d'identité** (liste fermée, frontière identique au
  tableau ci-dessus) : `je m'appelle` · `je me nomme` · `mon prenom est` ·
  `mon nom est` · `le demandeur s'appelle` · `nom du demandeur` ;
- **ID-2 — présentation contextuelle** : la valeur apparaît dans le message
  **hors de l'union des segments concordance** (ex. « pour Maria Gomes »,
  « au nom de Maria Gomes »), sans marqueur requis.

L'extraction **DOIT** renseigner `prenom` / `nom` depuis ID-1 ou ID-2
lorsqu'ils y figurent ; sinon elle les laisse **absents**. Une valeur de
`prenom` / `nom` dont la **seule** occurrence dans le message est à
l'intérieur d'un segment concordance est une **inférence** : jamais
acceptée (A-5), retirée si produite (D-1).

**Règle A-5** — une valeur introduite comme forme incorrecte ou forme
correcte **ne doit jamais** être découpée automatiquement pour remplir
`prenom` / `nom`. En cas d'ambiguïté d'attribution (segment vide, valeur
non dérivable sans supposition) : les champs **`prenom` / `nom` (famille
`IDENTITE`) restent absents** → `MISSING_INFORMATION` + question
(décision 6) ; **jamais** de choix silencieux du premier segment trouvé.
Des segments concordance **divergeant entre eux** sont **hors périmètre
de D-1** : la valeur extraite de `nomIncorrect` / `nomCorrect` reste en
place (D-1 ne l'a pas touchée) et la validation existante (V5/V6)
s'applique — jamais de relecture « intelligente ».

#### 6.1.3 D-1 — détection déterministe des attributions suspectes

**But** : détecter qu'un champ `IDENTITE` a été (re)fabriqué à partir d'un
segment `CONCORDANCE`, le retirer, puis recalculer `missingFields`.

**Garanties** (décisions 9–10) : aucun champ ajouté à la syntaxe
`ExtractionResult`, **aucun code d'erreur API ajouté**, **aucun appel
LLM**, budget `N8N_CONTRACTS.md` §4 inchangé (≤ 19 appels/exécution),
`MAX_CLARIFICATION_ROUNDS = 5` inchangé, contrats E1–E8 inchangés, graphe
d'orchestration acyclique préservé (garde en test conditionnel
déterministe, aucun nœud de boucle ajouté).

**Entrée** : `data` + `missingFields` de l'`ExtractionResult` du round
courant ; message utilisateur du round courant ; état de la demande
persisté avant ce round — **si cet état est déjà chargé au moment de la
garde** (cf. disponibilité en P3) ; `documentType`.
**Sortie** : forme d'`ExtractionResult` identique, `data` éventuellement
privée de `prenom` / `nom`, `missingFields` recalculé.

**Étape 0 — garde d'entrée.** Si `documentType ≠
ATTESTATION_CONCORDANCE` **ou** si **aucun** marqueur de concordance
(§6.1.2) n'est reconnu dans le message ⇒ **sortie sans modification**
(une demande sans concordance — dont le scénario S01 — n'est jamais
affectée).

**Étape 1 — segmentation.** Scan gauche→droite des tokens du message ;
reconnaissance des marqueurs (plus long prime au même index) ; calcul des
segments `CONCORDANCE` (canaux `nomIncorrect` / `nomCorrect`) et des
segments d'identité ID-1 selon la frontière du §6.1.2.

**Étape 2 — tokenisation** (décision 2), appliquée aux segments et aux
valeurs `IDENTITE` du round :

1. normalisation Unicode **NFC** ; `’` → `'` ;
2. séparateurs : toute séquence d'espaces (espace, tabulation, retour de
   ligne) **et** tout caractère hors `[Lettre | Chiffre | ' | -]` (donc
   `; . , : ! ? ( ) /` ferment un token) ;
3. un token = plus longue séquence de `[Lettre | Chiffre | ' | -]` ;
   l'apostrophe et le tiret **intérieurs** font partie du token :
   `Jean-Pierre` = **1 token**, `d'Almeida` = **1 token** ; un `-` ou `'`
   isolé (entouré d'espaces) est une frontière — un token réduit à `-` ou
   `'` est **supprimé** ;
4. forme de comparaison : minuscules + **pliage des diacritiques**
   (`é`→`e`, `ç`→`c`) : les accents **ne sont pas significatifs** pour la
   comparaison D-1, mais les **valeurs conservées ne sont jamais
   réécrites** (N1–N9 restent les seules normalisations de stockage,
   décision 8) ;
5. les particules N6 (`da`, `van`, `de`…) sont des **tokens à part
   entière**, comparées comme tout autre token.

**Étape 3 — comparaison fenêtrée de tokens** (décision 2 : uniquement des
**séquences de tokens** ; la correspondance par **sous-chaîne brute est
interdite** — `substring` jamais utilisé) :

- égalité = égalité **entière** de token (`gomes` ≠ `gomez`, `pedro` ≠
  `petro`, `correct` ≠ `incorrect`) ;
- fenêtre glissante sur les tokens de chaque segment `CONCORDANCE`,
  longueur de fenêtre **≥ 2** ;
- **T1 (champ individuel)** : pour `f ∈ {prenom, nom}` présent dans `data`
  et dont `tokens(f)` compte ≥ 2 tokens, `tokens(f)` apparaît comme
  sous-séquence **contiguë** dans un segment `CONCORDANCE` ;
- **T2 (famille)** : `tokens(prenom) ++ tokens(nom)` (≥ 2 tokens, les deux
  champs présents dans `data`) apparaît comme sous-séquence contiguë dans
  un segment `CONCORDANCE` — c'est le cas type « prénom seul + nom seul »
  (exécution 86, S17) ;
- si **ni T1 ni T2** ne déclenche : **aucune modification**.

**Étape 4 — protections** (décisions 4, 5, 7) : un champ (ou le run T2)
qui remplit T1/T2 reste **protégé — donc conservé —** si l'une des
conditions tient :

- **P1** : le run apparaît **aussi** dans au moins un segment d'identité
  ID-1 (déclaration explicite, y compris composée : « je m'appelle
  Jean-Pierre Dupont ») ;
- **P2** : le run apparaît **aussi** dans le message **hors de l'union des
  segments concordance** (présentation contextuelle ID-2) — avec la
  **même sémantique que l'étape 3** : sous-séquence **contiguë** de
  tokens, égalité **entière** de token, **jamais** de correspondance par
  sous-chaîne brute ;
- **P3** : la valeur est **déjà présente dans la demande persistée** avant
  ce round, **après normalisation N1–N9** (déclarée lors d'un round
  antérieur) — D-1 n'évalue que les valeurs fournies **en vol** par le
  round courant et **ne réécrit, ne retire ni ne corrige jamais une donnée
  stockée** (décision 8 ; preuve de déclaration antérieure : l'état de la
  demande, seul témoignage disponible — l'historique de messages n'est pas
  persisté, `N8N_CONTRACTS.md` §7). **Disponibilité de l'état** : dans le
  flux réel, E2 (état persisté) s'exécute **après** E8 ; si l'état n'est
  **pas encore chargé** au moment de la garde (placement pré-E8), **P3 =
  faux** — sans effet observable, car D-1 ne touche jamais aux données
  stockées et le merge PATCH ne supprime jamais un champ persisté.
  Variante pleine-fidélité documentée : garde **post-E2 / pré-E3** (P3
  évaluable) + garde **pré-E1 en round 1** (état non pertinent, P3 faux).

**Décision 5 — explicite vs inféré** : une valeur de `prenom`/`nom` est
**explicite** si P1 ou P2 tient (ou si elle provient d'un round
antérieur : P3) ; elle est **inférée** sinon — c'est exactement le cas
retiré par D-1.

**Étape 5 — retrait (strip)** de chaque champ `IDENTITE` suspect **non
protégé** : le champ est **retiré du payload** et traité comme **absent**
(règle N9). Interdictions cumulatives :

- **jamais** de réaffectation de la valeur retirée à un autre champ (pas
  de « promotion » d'un segment `CONCORDANCE` vers `prenom`/`nom`, ni
  l'inverse) — A-1, A-5, décision 6 ;
- **jamais** de modification des champs `CONCORDANCE` ni des autres champs
  du payload (leur validité relève de V4–V6, inchangés) ;
- **jamais** de correction d'orthographe : `Gomez` ne devient `Gomes`
  **que** sur déclaration explicite de l'utilisateur (décision 8,
  §10 — rien à attester en cas d'identique ⇒ V6).

**Étape 6 — recalcul de `missingFields` et repli sur les codes
existants** : **hors backend**, le `missingFields` recalculé est **limité
au payload du round courant après retrait** — l'état persisté ne sert
qu'à la protection P3 et **n'entre pas** dans ce calcul — et il respecte
l'invariant `allOf` de `N8N_CONTRACTS.md` §5 **entendu payload-scoped**
(toutes les clés obligatoires encore absentes **de ce payload** ; sinon
E8 rejetterait un round partiel pourtant correct → `502`). Le recalcul
**fusionné payload + état** est **l'exclusivité du backend après merge**
(V4 + N9) : lui seul décide `status`. Si ce recalcul constate ≥ 1 champ
obligatoire absent ⇒ `status = MISSING_INFORMATION`. Sur le PATCH (E3),
la réponse est HTTP **200** avec ce statut (ADR-002). Le HTTP **422**
reste celui de `POST /requests` et de `POST .../validate`. Code métier
existant `ERR_CHAMP_OBLIGATOIRE_ABSENT` (§10.1) — **aucun code
d'erreur API ajouté** (décision 9). La question posée à l'utilisateur
cible **uniquement** les clés de `missingFields` : dans S17, **une seule
question explicite d'identité**, **pas** de re-demande corrective des
formes concordance (elles sont présentes et valides).

**Étape 7 — placement et coûts** (décision 10) : la garde s'applique au
payload du round **avant toute persistance**. **Placement retenu en
itération 1 : n8n** — nœud déterministe entre l'extraction et E8/E3, seul
environnement disposant du message utilisateur et de l'état du round (via
E2). La variante « validation backend entre V3 et V4 » est **conditionnée
à une décision de contrat future** (E1/E3/E8 devraient transporter le
message et l'état — aujourd'hui le backend ne reçoit ni l'un ni l'autre) :
elle est donc **hors périmètre de l'itération 1** et incompatible avec
`N8N_CONTRACTS.md` §7 non-objectifs (« Toute modification des contrats
E1–E8 ») tant qu'aucune évolution de contrat n'est arbitrée (voir OQ-10).
Pour le placement retenu : **0 appel LLM supplémentaire**, budget
`N8N_CONTRACTS.md` §4 inchangé, boucle de conversation §5 inchangée
(round ≥ 2 : E2 → extraction delta → E8 → E3 PATCH partiel), **aucune
modification des contrats E1–E8**.

#### 6.1.4 Cas positifs (doivent rester valides)

| # | Cas | Message / état | Résultat attendu |
|---|---|---|---|
| CP-1 | Identité composée déclarée | « je m'appelle Jean-Pierre Dupont » (aucun marqueur concordance) | Étape 0 : sortie sans modification — `prenom=Jean-Pierre` (1 token), `nom=Dupont` conservés |
| CP-2 | Happy path API (S01) | payload complet, sans message conversationnel | Étape 0 : aucun marqueur ⇒ aucune modification des 6 champs |
| CP-3 | Concordance + présentation contextuelle | « Génère une attestation pour Maria Gomes, forme erronée Maria Gomez, forme correcte Maria Gomes. » | `nomIncorrect=Maria Gomez` et `nomCorrect=Maria Gomes` en **formes complètes** (A-1) ; `prenom=Maria`, `nom=Gomes` conservés (occurrence hors segments ⇒ P2) |
| CP-4 | Particules N6 | « je m'appelle Joao da Silva » | `da` = token à part entière ; identité conservée |
| CP-5 | Round ≥ 2, valeurs légitimes | `dateNaissance` / `lieuNaissance` déjà persistés ; le round fournit `nomCorrect` seul | E3 ne transmet que le champ fourni (`N8N_CONTRACTS.md` §5) ; D-1 ne retire rien (aucun T1/T2, ou protection P3) |

#### 6.1.5 Limites connues de la détection (faux positifs / faux négatifs)

- Paraphrases **hors** des listes fermées (§6.1.2) : marqueur non reconnu
  ⇒ attribution suspecte non détectée (faux négatif).
- Chevauchement d'**un seul token** : sous le seuil ≥ 2 (ex. extraction
  ne produisant que `nom=Miguel`, ou `prenom=Pedro` seul sans `nom`) ⇒
  non détecté (T2 exige les deux champs).
- Run également présent **hors** segments (P2) : valeur conservée — faux
  négatif **assumé**, priorité à la préservation d'une déclaration
  légitime (décision 7).
- `prenom`/`nom` extraits dans l'ordre inversé ou agrégés autrement que
  `prenom ++ nom` : T2 peut ne pas correspondre.
- Séparateur de clause **dans** une forme légitime (virgule ou point) :
  troncature prématurée du segment.
- Pliage diacritique : `José` ≡ `Jose` pour la comparaison (cela **élargit**
  la détection) ; les valeurs persistées restent intactes.
- Donnée suspecte **déjà persistée** avant l'introduction de D-1 : jamais
  réécrite rétroactivement (P3) — correction = action explicite de
  l'utilisateur.
- **D-1 est une heuristique déterministe bornée : elle ne prétend pas
  détecter toutes les attributions erronées ni toutes les hallucinations
  du LLM.** Elle réduit le risque observé en itération 1 sans le
  supprimer ; elle n'invente, ne complète et ne corrige aucune valeur en
  cascade.

#### 6.1.6 Décisions de conception couvertes

| # | Décision | Où |
|---|---|---|
| 1 | Liste fermée des marqueurs de concordance | §6.1.2 (tableau canaux) |
| 2 | Tokenisation (espaces, apostrophes/tirets intérieurs, NFC, casse, diacritiques, particules N6) | §6.1.3 étape 2 + étape 3 |
| 3 | Frontière exacte du segment (marqueur → séparateur de clause / marqueur suivant / fin de message) | §6.1.2 (tableau frontière) |
| 4 | Noms composés et déclarations explicites préservés | §6.1.3 étape 4 (P1) + §6.1.4 CP-1/CP-3/CP-4 |
| 5 | Identité explicite (ID-1/ID-2) vs inférée (segment concordance) | §6.1.2 (sources ID-1/ID-2) + §6.1.3 étape 4 (P1–P3) |
| 6 | Ambiguïté → champ absent → `MISSING_INFORMATION` + question | §6.1.2 (règle A-5) + §6.1.3 étape 5 |
| 7 | Préservation des valeurs légitimes (E3 partiel, P1–P3) | §6.1.3 étapes 4–5, §6.1.4 CP-3/CP-5, `N8N_CONTRACTS.md` §5 |
| 8 | Jamais de correction silencieuse de données persistées | §6.1.3 étape 4 (P3), étape 5, §6.1.5 |
| 9 | Aucun code d'erreur API ajouté (`MISSING_INFORMATION` / `ERR_CHAMP_OBLIGATOIRE_ABSENT` uniquement) | §6.1.3 garanties + étape 6, `N8N_CONTRACTS.md` §5 |
| 10 | Aucun appel Ollama supplémentaire (budget ≤ 19, graphe acyclique) | §6.1.3 garanties + étape 7, `N8N_CONTRACTS.md` §4/§5 |

---

## 7. RÈGLES DE NORMALISATION (NORMALIZATION_RULES)

| # | Règle | S'applique à | Entrée | Sortie |
|---|---|---|---|---|
| N1 | `trim` des extrémités | toutes les chaînes | `"  Maria "` | `"Maria"` |
| N2 | Espaces multiples → un seul espace | toutes les chaînes | `"Maria   Gomes"` | `"Maria Gomes"` |
| N3 | Conversion des dates non ISO | `dateNaissance` | `"12/05/1985"`, `"12-05-1985"`, `"12/5/1985"` | `"1985-05-12"` |
| N4 | Normalisation du séparateur d'apostrophe | chaînes nom/prénom | `"Maria Gomes\u2019"` (’ typographique) | apostrophe `'` |
| N5 | Capitalisation des noms propres | `prenom`, `nom`, `nomIncorrect`, `nomCorrect`, `demandeur`, `lieuNaissance` (1ʳᵉ lettre) | `"maria gomes"` | `"Maria Gomes"` |
| N6 | Particules maintenues en minuscules | idem, sauf en 1ʳᵉ position | `"joao da silva"` | `"Joao da Silva"` |
| N7 | Enums en majuscules | `sexe`, `langueDocument` | `"f"`, `"pt"` | `"F"`, `"PT"` |
| N8 | Nationalité : 1ʳᵉ lettre en majuscule | `nationalite` | `"française"` | `"Française"` |
| N9 | Chaîne vide après trim ⇒ champ **absent** | tous champs | `"   "`, `""`, `null` | absent (→ `missingFields`) |

**N6 — liste des particules** (minuscules si pas en première position) :
`de`, `da`, `do`, `dos`, `das`, `du`, `des`, `la`, `le`, `van`, `von`,
`bin`, `el`, `al`, `di`, `del`, `della`, `der`, `den`, `ter`, `ten`.

> **REQUIRES_BUSINESS_VALIDATION** : cette liste de particules est un choix
> de normalisation, pas une règle administrative vérifiée (OQ-4).

**Non normalisés (volontairement) :** les dates non reconnues, les enums hors
liste, les caractères hors motif ⇒ **erreur** (jamais de « meilleure
interprétation » approximative).

---

## 8. VARIABLES DE TEMPLATE (TEMPLATE_VARIABLES)

Mapping champ métier → placeholder du DOCX. Le modèle officiel n'étant pas
disponible, la **liste exacte et les libellés définitifs sont soumis à
validation humaine** :

**REQUIRES_BUSINESS_VALIDATION**

| Champ métier / système | Placeholder template | Origine de la valeur |
|---|---|---|
| `prenom` | `{{prenom}}` | utilisateur |
| `nom` | `{{nom}}` | utilisateur |
| `dateNaissance` | `{{date_naissance}}` (format d'affichage du template, ex. `12 mai 1985` — format exact à valider) | utilisateur (normalisé ISO puis formaté) |
| `lieuNaissance` | `{{lieu_naissance}}` | utilisateur |
| `nomIncorrect` | `{{nom_incorrect}}` | utilisateur |
| `nomCorrect` | `{{nom_correct}}` | utilisateur |
| `sexe` | `{{sexe}}` | utilisateur (optionnel) |
| `nationalite` | `{{nationalite}}` | utilisateur (optionnel) |
| `documentSourceReference` | `{{document_source_reference}}` | utilisateur (optionnel) |
| `langueDocument` | `{{langue_document}}` | utilisateur / défaut `FR` (optionnel) |
| `demandeur` | `{{demandeur}}` | utilisateur (optionnel) |
| `motif` | `{{motif}}` | utilisateur (optionnel, à valider) |
| `referenceDemande` (= `requestId`) | `{{reference_demande}}` | **système** (UUID v4 ; format d'affichage à valider) |
| `dateGeneration` | `{{date_generation}}` | **système** (date UTC de génération) |

Règles de fusion :

- Un placeholder sans valeur (champ optionnel absent) doit être vidé par le
  moteur de template, **jamais** remplacé par une valeur inventée.
- Le wording du document (phrases, mentions légales, formules) appartient au
  template approuvé ; il n'est jamais généré par le LLM (AGENTS.md §4.1).
- Template absent → `TEMPLATE_NOT_FOUND` (voir `API_CONTRACTS.md`).

---

## 9. STATUTS ET TRANSITIONS (STATUS_RULES)

### 9.1 États

| Statut | Libellé FR | Signification |
|---|---|---|
| `DRAFT` | Brouillon | Demande persistée, validation non encore exécutée. **État transitoire interne** (persistance → validation, uniquement à la création) : jamais exposé aux clients en itération 1. |
| `MISSING_INFORMATION` | Information manquante | Validation exécutée ; ≥ 1 champ obligatoire absent. Génération impossible. |
| `VALIDATED` | Validée | Toutes les règles déterministes passent. Génération autorisée. |
| `REJECTED` | Rejetée | ≥ 1 règle métier violée (données invalides). Terminale en itération 1 (correction = nouvelle demande). |
| `GENERATED` | Générée | DOCX généré avec succès. Terminale en itération 1. |
| `FAILED` | Échec | Génération en échec (template absent, erreur technique). Reprise possible par re-validation. |

### 9.2 Transitions autorisées

| # | Depuis | Vers | Déclencheur | Condition |
|---|---|---|---|---|
| T1 | — | `DRAFT` | `POST /api/v1/requests` (payload structurellement recevable) | persistance immédiate, avant validation |
| T2 | `DRAFT` | `VALIDATED` | validation suite à `POST /requests`, à `PATCH .../{id}` ou à `POST .../validate` | aucune règle en échec, aucun champ obligatoire absent |
| T3 | `DRAFT` | `MISSING_INFORMATION` | idem | ≥ 1 champ obligatoire absent |
| T4 | `DRAFT` | `REJECTED` | idem | ≥ 1 règle métier violée |
| T5 | `MISSING_INFORMATION` | `VALIDATED` | `PATCH /api/v1/requests/{id}` (déclencheur principal en it.1) ou `POST .../validate` | tous les champs obligatoires désormais présents + règles OK |
| T6 | `MISSING_INFORMATION` | `REJECTED` | `PATCH .../{id}` ou `POST .../validate` | règle métier violée détectée |
| T6b | `MISSING_INFORMATION` | `MISSING_INFORMATION` | `PATCH .../{id}` (complétude toujours non atteinte) ou `POST .../validate` | champs toujours absents (boucle) |
| T7 | `VALIDATED` | `MISSING_INFORMATION` | `PATCH /api/v1/requests/{id}` | fusion rend un champ obligatoire absent (null / vide ⇒ absent) |
| T8 | `VALIDATED` | `GENERATED` | `POST .../generate` | template trouvé + fusion réussie |
| T9 | `VALIDATED` | `FAILED` | `POST .../generate` | `TEMPLATE_NOT_FOUND` ou erreur technique |
| T10 | `VALIDATED` | `REJECTED` | `PATCH /api/v1/requests/{id}` | règle métier violée par les données fusionnées |
| T11 | `VALIDATED` | `VALIDATED` | `PATCH .../{id}` (données toujours valides) ou `POST .../validate` | re-check idempotent (garde avant génération) |
| T12 | `FAILED` | `VALIDATED` | `POST .../validate` ou `PATCH .../{id}` | règles OK |
| T12b | `FAILED` | `REJECTED` | `POST .../validate` ou `PATCH .../{id}` | règle métier violée détectée lors de la re-validation ou par la fusion |
| T12c | `FAILED` | `MISSING_INFORMATION` | `PATCH /api/v1/requests/{id}` | fusion rend un champ obligatoire absent |
| T13 | `GENERATED` | aucune | — | **terminale** en itération 1 |
| T14 | `REJECTED` | aucune | — | **terminale** en itération 1 |

Mapping des réponses sur les transitions :

- `POST .../validate` : `200` → T2 / T5 / T11 / T12 ; `422` → boucle T6b ;
  `400` → T4 / T6 / T12b (depuis `DRAFT`, `MISSING_INFORMATION`, `FAILED`).
- `PATCH .../{id}` : `200` → T5 / T6b (depuis `MISSING_INFORMATION`),
  T11 / T7 (depuis `VALIDATED`), T12 / T12c (depuis `FAILED`),
  T2 / T3 (depuis `DRAFT`) ; `400` → T4 / T6 / T10 / T12b ;
  `409 REQUEST_ALREADY_CLOSED` → aucune transition (statuts terminaux
  T13 / T14).

Transitions **interdites** (→ `409 INVALID_STATUS` ou
`409 REQUEST_ALREADY_CLOSED`) : toute non listée, dont :

- `generate` depuis un statut ≠ `VALIDATED` (notamment `MISSING_INFORMATION`,
  `FAILED`, `GENERATED`, `REJECTED`) ;
- `validate` depuis `GENERATED` ou `REJECTED` ;
- `PATCH` depuis `GENERATED` ou `REJECTED` → `409 REQUEST_ALREADY_CLOSED`
  (demande close).

---

## 10. CODES D'ERREUR MÉTIER (BUSINESS_ERRORS)

### 10.1 Famille `MISSING_INFORMATION` — HTTP 422 (E1, E4) ; HTTP 200 sur E3 (ADR-002)

| Code | Champs en erreur | Message FR |
|---|---|---|
| `ERR_CHAMP_OBLIGATOIRE_ABSENT` | un ou plusieurs de : `prenom`, `nom`, `dateNaissance`, `lieuNaissance`, `nomIncorrect`, `nomCorrect` (liste retournée dans `missingFields[]`) | « Information manquante : le champ « <clé> » est obligatoire pour générer l'attestation. » |

### 10.2 Famille `VALIDATION_ERROR` — HTTP 400

| Code | Champs en erreur | Message FR |
|---|---|---|
| `ERR_DOCUMENT_TYPE_NON_SUPPORTE` | `documentType` | « Type de document non pris en charge pour cette itération. Seul « ATTESTATION_CONCORDANCE » est supporté. » |
| `ERR_PAYLOAD_INVALIDE` | — (enveloppe) | « Corps de requête invalide ou JSON malformé. » |
| `ERR_CHAMP_INCONNU` | clé incriminée | « Champ inconnu ou non autorisé pour ce type de document : « <clé> ». » |
| `ERR_CHAMP_RESERVE` | `referenceDemande` | « Le champ « referenceDemande » est réservé au système. » |
| `ERR_FORMAT_TEXTE_INVALIDE` | champ textuel concerné | « Le champ « <clé> » contient des caractères non autorisés. » |
| `ERR_LONGUEUR_DEPASSEE` | champ concerné | « Le champ « <clé> » dépasse la longueur maximale autorisée. » |
| `ERR_DATE_FORMAT_INVALIDE` | `dateNaissance` | « Format de date invalide. Formats acceptés : yyyy-MM-dd ou dd/MM/yyyy. » |
| `ERR_DATE_CALENDRIER_INVALIDE` | `dateNaissance` | « La date de naissance n'est pas une date de calendrier valide. » |
| `ERR_DATE_FUTUR` | `dateNaissance` | « La date de naissance ne peut pas être postérieure à la date du jour. » |
| `ERR_ENUM_INVALIDE` | `sexe` \| `langueDocument` | « Valeur invalide pour « <clé> ». Valeurs acceptées : <liste>. » |
| `ERR_EMAIL_INVALIDE` | `contactEmail` | « Le champ « contactEmail » n'est pas une adresse e-mail valide. » |
| `ERR_TELEPHONE_INVALIDE` | `contactTelephone` | « Le champ « contactTelephone » n'est pas un numéro de téléphone valide. » |
| `ERR_NOM_CONCORDANCE_IDENTIQUE` | `nomIncorrect`, `nomCorrect` | « Les formes « nomIncorrect » et « nomCorrect » doivent différer : rien à attester. » |

### 10.3 Familles système (rappel AGENTS.md §14 — hors champ de ce contrat)

`INVALID_STATUS` (409) · `REQUEST_ALREADY_CLOSED` (409 : demande `GENERATED`
ou `REJECTED` — rejet d'un `PATCH`) ·
`REQUEST_NOT_FOUND` / `DOCUMENT_NOT_FOUND` (404 ; sur E6, le seul code est `DOCUMENT_NOT_FOUND`) ·
`TEMPLATE_NOT_FOUND` (500) · `DOCUMENT_GENERATION_ERROR` (500) ·
`DATABASE_ERROR` (500) · `INTERNAL_ERROR` (500) · `UNAUTHORIZED` (401) ·
`FORBIDDEN` (403) — les deux derniers en itération 2 ·
`AI_EXTRACTION_ERROR` (flux n8n) ·
`EXTRACTION_SCHEMA_INVALID` (400, endpoint d'extraction).

Voir `requirements/API_CONTRACTS.md` pour le mapping HTTP ↔ codes.

---

## 11. SCÉNARIOS DE TEST MÉTIER (Given / When / Then)

| # | Nom | Given | When | Then |
|---|---|---|---|---|
| S01 | Happy path complet | Payload complet et valide : `prenom=Maria`, `nom=Gomes`, `dateNaissance=1985-05-12`, `lieuNaissance=Bissau`, `nomIncorrect=Maria Gomez`, `nomCorrect=Maria Gomes` | `POST /api/v1/requests` | `201` ; `status=VALIDATED` ; `requestId` = UUID ; `missingFields=[]` ; `fieldErrors=[]` |
| S02 | Champs obligatoires manquants | Payload **sans** `dateNaissance` ni `lieuNaissance` | `POST /api/v1/requests` | `422` ; `code=MISSING_INFORMATION` ; `missingFields=[dateNaissance, lieuNaissance]` ; `status=MISSING_INFORMATION` ; `requestId` présent ; le système (n8n) pose une question à l'utilisateur ; **aucune valeur inventée** |
| S03 | Normalisation date FR → ISO | Payload complet avec `dateNaissance="12/05/1985"` | `POST /api/v1/requests` | `201` ; `dateNaissance` stockée/retournée = `"1985-05-12"` ; `status=VALIDATED` |
| S04 | Date de calendrier invalide | Payload complet avec `dateNaissance="31/02/1985"` | `POST /api/v1/requests` | `400` ; `code=VALIDATION_ERROR` ; `fieldErrors=[{field:dateNaissance, code:ERR_DATE_CALENDRIER_INVALIDE}]` ; `status=REJECTED` ; `requestId` présent |
| S05 | Date de naissance dans le futur | Payload complet avec `dateNaissance` = demain (UTC) | `POST /api/v1/requests` | `400` ; `code=VALIDATION_ERROR` ; `fieldErrors=[{field:dateNaissance, code:ERR_DATE_FUTUR}]` ; `status=REJECTED` |
| S06 | Nom vide (« nom vide ») | Payload contenant les 6 clés obligatoires mais `nom=""` (variante de test : `nom="   "`) | `POST /api/v1/requests` | `422` ; `code=MISSING_INFORMATION` ; `missingFields=[nom]` (vide après trim ⇒ **absent**, pas invalide) ; `status=MISSING_INFORMATION` |
| S07 | Nom avec caractères interdits | Payload complet mais `nom="Gomes@123"` | `POST /api/v1/requests` | `400` ; `fieldErrors=[{field:nom, code:ERR_FORMAT_TEXTE_INVALIDE}]` ; `status=REJECTED` |
| S08 | Concordance identique (« cas ambigu ») | Payload complet avec `nomIncorrect="Maria Gomez"` et `nomCorrect="maria gomez "` (identiques après normalisation) | `POST /api/v1/requests` | `400` ; `code=ERR_NOM_CONCORDANCE_IDENTIQUE` ; `status=REJECTED` ; message invitant à fournir les deux formes distinctes ; aucune relecture « intelligente » des noms |
| S09 | Extraction IA incomplète sans invention | `ExtractionResult` avec `confidence=0.61` et `data.nomCorrect` **absent** (l'IA n'a rien inventé) | `POST /api/v1/requests` | `422` ; `missingFields=[nomCorrect]` ; `status=MISSING_INFORMATION` ; n8n relance une clarification à l'utilisateur ; `confidence` ne sert jamais à fabriquer une valeur |
| S10 | Type de document non supporté | `documentType="ACTE_NAISSANCE"` | `POST /api/v1/requests` | `400` ; `code=ERR_DOCUMENT_TYPE_NON_SUPPORTE` ; **non persistée** : aucun `requestId` retourné |
| S11 | JSON malformé | Corps HTTP non JSON / tronqué | `POST /api/v1/requests` | `400` ; `code=ERR_PAYLOAD_INVALIDE` ; non persisté ; pas de stack trace exposée |
| S12 | Champ inconnu dans `data` | Payload complet + `data.passeport="X123"` | `POST /api/v1/requests` | `400` ; `code=ERR_CHAMP_INCONNU` ; `status=REJECTED` (persisté, audité) ; aucune donnée hors schéma stockée |
| S13 | Génération sans validation | `status=MISSING_INFORMATION` | `POST /api/v1/requests/{id}/generate` | `409` ; `code=INVALID_STATUS` ; message citant le statut actuel et `VALIDATED` attendu ; aucune génération |
| S14 | Génération happy path | `status=VALIDATED`, template DOCX présent | `POST /api/v1/requests/{id}/generate` | `201` ; `documentId` présent ; `status=GENERATED` ; puis `GET .../documents/{documentId}` → `200` binaire DOCX, `Content-Disposition: attachment` |
| S15 | Template manquant | `status=VALIDATED` mais template introuvable sur disque | `POST /api/v1/requests/{id}/generate` | `500` ; `code=TEMPLATE_NOT_FOUND` ; `status=FAILED` ; reprise : `POST .../validate` → `200 VALIDATED` puis re-`generate` |
| S16 | Consultation état inconnu | `requestId` inexistant | `GET /api/v1/requests/{requestId}` | `404` ; `code=REQUEST_NOT_FOUND` ; aucun détail technique fuité |
| S17 | Attribution erronée d'identité depuis un segment concordance (exécution 86, valeurs fictives) | **Round 2** (`requestId` connu) ; état persisté avant le round : `MISSING_INFORMATION` avec `dateNaissance=1993-04-17` et `lieuNaissance=Nova Cidade`, **sans** `prenom`/`nom`/`nomIncorrect`/`nomCorrect` et sans déclaration d'identité antérieure ; message utilisateur : « …je suis né(e) le 17/04/1993 à Nova Cidade. Le nom figurant à l'état civil est incorrect : Pedro Miguel Santos ; le nom correct est Pedro Miguel Santhos. » ; **extraction défaillante constatée** (qwen2.5:1.5b) : `prenom=Pedro`, `nom=Miguel`, `dateNaissance=1993-04-17`, `lieuNaissance=Nova Cidade`, `nomIncorrect`/`nomCorrect` absents → **outcome constaté** `422` `MISSING_INFORMATION` `missingFields=[nomIncorrect, nomCorrect]` | round 2 : extraction → **D-1 (§6.1.3)** → E8 → E3 PATCH partiel | **Attendu avec A-1 + A-5 + D-1** : l'extraction remplit `nomIncorrect=Pedro Miguel Santos`, `nomCorrect=Pedro Miguel Santhos` (formes complètes) ; identité **non déclarée explicitement** (aucun ID-1/ID-2) ⇒ `prenom`/`nom` **absents** — jamais inférés. Si un LLM produit malgré tout `prenom=Pedro`, `nom=Miguel` : T2 détecte `[pedro, miguel]` contigu dans les segments concordance, aucune protection P1/P2/P3 ⇒ retrait de `prenom`/`nom`, recalcul `missingFields=[prenom, nom]` (ordre canonique §3) ⇒ outcome webhook `200` `MISSING_INFORMATION` (E3 `200`, ADR-002) posant **une seule question explicite d'identité** (pas de re-demande corrective des formes concordance) ; **branche exécution 86 réelle** : si cette même extraction n'a **pas** renseigné les formes concordance (pattern exécution 86 sans A-1), `missingFields=[prenom, nom, nomIncorrect, nomCorrect]` (les 4 champs restent requis, **aucune forme n'est inventée**) ; codes **existants** uniquement ; ≤ `MAX_CLARIFICATION_ROUNDS=5` ; **0 appel LLM supplémentaire** ; **aucune valeur inventée, aucune correction silencieuse** ; `status=MISSING_INFORMATION` ; `requestId` conservé |

---

## 12. OPEN_BUSINESS_QUESTIONS (Points ouverts / à escalader)

| ID | Question | Impact | Statut |
|---|---|---|---|
| OQ-1 | Quels sont les **champs légaux exactement obligatoires** de l'attestation de concordance (mentions obligatoires, autorité émettrice, wording officiel) ? Les 6 champs obligatoires ci-dessus sont des candidats techniques, **pas** une base légale vérifiée. | Template + validation | **REQUIRES_BUSINESS_VALIDATION** |
| OQ-2 | Pas d'endpoint de modification en itération 1 : une demande `MISSING_INFORMATION` ou `REJECTED` est-elle complétée par **une nouvelle création** (décision retenue provisoirement, anciennes demandes conservées pour audit), ou faut-il un `PUT /api/v1/requests/{requestId}` ? | Flux n8n, données orphelines | **ARBITRÉE — RÉSOLUE** : Orchestrateur — « PATCH ajouté » : `PATCH /api/v1/requests/{requestId}` (complétion partielle, voir `API_CONTRACTS.md` §3.3) ; plus de nouvelle création nécessaire |
| OQ-3 | Le `422 MISSING_INFORMATION` persiste-t-il bien la demande (décision retenue : **oui**, avec `requestId`), ou doit-on ne rien persister tant que les champs sont incomplets ? | API, base de données | **ARBITRÉE — CONFIRMÉE** : Orchestrateur — « persistance 422 confirmée » (statut `MISSING_INFORMATION` + `requestId`, voir `API_CONTRACTS.md` §1.4) |
| OQ-4 | Liste des particules de normalisation (N6) et règles de capitalisation : valides pour toutes les origines de noms (luso-africaines, arabes, asiatiques…) ? | Normalisation des noms | **REQUIRES_BUSINESS_VALIDATION** |
| OQ-5 | Y a-t-il un **seuil de confiance IA** (champ `confidence`) à appliquer avant de soumettre une extraction à validation, et qui décide du comportement en cas de confiance basse ? | n8n / ai-engineer | À définir (hors contrat pilote) |
| OQ-6 | Le champ `motif` et le champ `sexe` figurent-ils réellement dans le document officiel ? | Template | **REQUIRES_BUSINESS_VALIDATION** |
| OQ-7 | Cohérence `prenom`/`nom` avec `nomCorrect` (ex. : `nomCorrect` contient-il le prénom ?) : faut-il une règle de cohérence ou peut-elle produire de faux positifs ? | Validation | **REQUIRES_BUSINESS_VALIDATION** — non appliquée en itération 1. **Réf. §6.1 (A-1/A-5/D-1)** : la distinction des familles `IDENTITE`/`CONCORDANCE` et la garde anti-attribution **ne sont pas** la règle de cohérence OQ-7 et **ne ferment pas** cet OQ |
| OQ-8 | Format d'affichage de `referenceDemande` et de `dateNaissance` sur le document (UUID brut ? référence formatée ? « 12 mai 1985 » ?) | Template | **REQUIRES_BUSINESS_VALIDATION** |
| OQ-9 | Génération PDF et archivage (AGENTS.md §1, étapes 10–11 de la mission) : périmètre confirmé hors itération 1 ? | Périmètre | À confirmer |
| OQ-10 | Placement d'exécution exact de la garde D-1 (§6.1.3) : nœud déterministe n8n entre extraction et E8/E3, ou validation backend entre V3 et V4 ? | Implémentation / budget n8n | **ARBITRÉE — RÉSOLUE** : placement **n8n** retenu pour l'itération 1 (§6.1.3 étape 7 : seul n8n dispose du message et de l'état du round) ; la branche backend est **reportée** — elle nécessiterait une évolution des contrats E1/E3/E8, incompatible avec `N8N_CONTRACTS.md` §7 non-objectifs. **Non bloquante** pour l'implémentation : 0 appel LLM, aucun code nouveau, budget §4 inchangé |

---

## 13. ACCEPTANCE CRITERIA — Definition of Done restreinte aux fondations

- [ ] Contrat `requirements/ATTESTATION_CONCORDANCE.md` créé et revu.
- [ ] Contrat `requirements/API_CONTRACTS.md` créé et revu (cohérence des
      codes/statuts avec le présent document).
- [ ] `documentType`, `displayName`, `description` définis.
- [ ] `requiredFields` documentés (6 champs) avec type, format, exemple,
      règle de validation et message d'erreur **pour chaque champ**.
- [ ] `optionalFields` documentés avec mêmes colonnes.
- [ ] `validationRules` explicites et déterministes (ordre d'évaluation
      défini, aucun cas ambigü résiduel).
- [ ] Règles d'attribution A-1/A-5/D-1 documentées (§6.1) : familles
      `IDENTITE`/`CONCORDANCE`, listes fermées de marqueurs, algorithme
      D-1 pas-à-pas, cas positifs (CP-1–CP-5) et limites connues.
- [ ] `normalizationRules` documentées (N1–N9).
- [ ] `templateVariables` mappées (placeholders) et marquées
      `REQUIRES_BUSINESS_VALIDATION`.
- [ ] `businessErrors` documentés par famille avec champ en erreur + message.
- [ ] Machine à états : 6 statuts, transitions autorisées et interdites
      documentées.
- [ ] ≥ 8 scénarios Given/When/Then fournis (17 fournis : S01–S17).
- [ ] Interdiction d'inventer des données rappelée et traduite en règles
      techniques (clés inconnues rejetées, clés réservées, défauts interdits).
- [ ] Tous les points d'ambiguïté métier listés en §12 et escaladés
      (aucune donnée administrative inventée, aucun wording légal inventé).
- [ ] Aucun code d'implémentation produit par l'analyse métier.
- [ ] Arbitrages de l'orchestrateur tracés (OQ-2 et OQ-3 **RÉSOLUES** :
      `PATCH` ajouté, persistance `422` confirmée) ; validation humaine
      obtenue sur OQ-1 avant démarrage de l'implémentation du backend et du
      template.

**Hors périmètre de cette DoD (itérations suivantes)** : implémentation,
migrations, workflows n8n, prompts, tests automatisés, revue reviewer /
security, PDF, authentification.
