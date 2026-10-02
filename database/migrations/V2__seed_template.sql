-- V2__seed_template.sql — seed du template pilote.
-- IMPORTANT : le checksum est calculé APRÈS création de
-- templates/attestation_concordance_v1.docx :
--   PowerShell : (Get-FileHash -Algorithm SHA256 <fichier>).Hash.ToLower()
-- La valeur ci-dessous est un placeholder 64 zéros à remplacer avant exécution.
-- TODO Phase G : remplacer le placeholder 64×'0' par le checksum SHA-256 réel
-- de templates/attestation_concordance_v1.docx.
-- En exécution, mismatch checksum ⇒ TEMPLATE_NOT_FOUND (jamais de document
-- produit à partir d'un template altéré).
-- Idempotent : rejeu sans effet (ON CONFLICT DO NOTHING).

INSERT INTO template_registry (code, version, file_path, checksum, active)
VALUES (
    'ATTESTATION_CONCORDANCE',
    '1.0',
    'attestation_concordance_v1.docx',
    '0000000000000000000000000000000000000000000000000000000000000000',
    TRUE
)
ON CONFLICT DO NOTHING;
