-- V2__seed_template.sql — seed du template pilote.
-- Checksum SHA-256 RÉEL de templates/attestation_concordance_v1.docx
-- (template technique NON APPROUVÉ POUR PRODUCTION — OQ-1 toujours ouvert).
-- Calcul : (Get-FileHash -Algorithm SHA256 templates\attestation_concordance_v1.docx).Hash.ToLower()
--
-- POLITIQUE FLYWAY (pré-release, développement) :
-- * Base fraîche : V1 + V2 s'appliquent proprement (aucune action).
-- * Une base de DÉVELOPPEMENT ayant déjà appliqué l'ANCIENNE V2
--   (checksum placeholder 64×'0') échouera VALIDATION Flyway avec
--   « Migration checksum mismatch » à l'amorçage. C'est VOLONTAIRE :
--   l'erreur n'est jamais masquée. Deux issues contrôlées uniquement :
--     1. (préféré) recréer la base de développement ;
--     2. flyway repair MANUEL après vérification du fichier V2, et
--        uniquement sur une base de développement connue.
-- * Ne JAMAIS exécuter flyway repair automatiquement sur une base
--   inconnue ou de production.
-- En exécution, fichier absent ou checksum mismatch registry/fichier ⇒
-- TEMPLATE_NOT_FOUND (500, statut FAILED) ; jamais de document produit à
-- partir d'un template altéré (§6.2 architecture).
-- Idempotent : rejeu sans effet (ON CONFLICT DO NOTHING).

INSERT INTO template_registry (code, version, file_path, checksum, active)
VALUES (
    'ATTESTATION_CONCORDANCE',
    '1.0',
    'attestation_concordance_v1.docx',
    'e98a91339d2aa093bc42a4a3b9c989b5f2127a67b3789d8c803af011cc9ebc29',
    TRUE
)
ON CONFLICT DO NOTHING;
