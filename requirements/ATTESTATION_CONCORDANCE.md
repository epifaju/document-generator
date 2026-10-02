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
   (vide/null/espaces ⇒ absent) → sinon `MISSING_INFORMATION` (422).
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

### 10.1 Famille `MISSING_INFORMATION` — HTTP 422

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
`REQUEST_NOT_FOUND` / `DOCUMENT_NOT_FOUND` (404) ·
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
| OQ-7 | Cohérence `prenom`/`nom` avec `nomCorrect` (ex. : `nomCorrect` contient-il le prénom ?) : faut-il une règle de cohérence ou peut-elle produire de faux positifs ? | Validation | **REQUIRES_BUSINESS_VALIDATION** — non appliquée en itération 1 |
| OQ-8 | Format d'affichage de `referenceDemande` et de `dateNaissance` sur le document (UUID brut ? référence formatée ? « 12 mai 1985 » ?) | Template | **REQUIRES_BUSINESS_VALIDATION** |
| OQ-9 | Génération PDF et archivage (AGENTS.md §1, étapes 10–11 de la mission) : périmètre confirmé hors itération 1 ? | Périmètre | À confirmer |

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
- [ ] `normalizationRules` documentées (N1–N9).
- [ ] `templateVariables` mappées (placeholders) et marquées
      `REQUIRES_BUSINESS_VALIDATION`.
- [ ] `businessErrors` documentés par famille avec champ en erreur + message.
- [ ] Machine à états : 6 statuts, transitions autorisées et interdites
      documentées.
- [ ] ≥ 8 scénarios Given/When/Then fournis (16 fournis).
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
