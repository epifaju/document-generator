#!/usr/bin/env python3
import json

input_path = r'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
output_path = r'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'

with open(input_path, 'r', encoding='utf-8') as f:
    wf = json.load(f)

# Fix FinalizeResponse
for node in wf['nodes']:
    if node['name'] == 'FinalizeResponse':
        code = node['parameters']['jsCode']
        
        # Replace SAFE_MESSAGES block
        new_safe = '''const SAFE_MESSAGES = {
  MISSING_INFORMATION: 'Des informations supplémentaires sont nécessaires pour poursuivre la demande.',
  CLARIFICATION_LIMIT_REACHED: 'Nombre maximal de demandes de clarification atteint. Veuillez relancer une nouvelle demande.',
  REJECTED: 'La demande a été rejetée : correction impossible sur cette demande (statut terminal en itération 1).',
  GENERATION_FAILED: 'La génération du document a échoué. Vous pouvez relancer la demande.',
  UNSUPPORTED_DOCUMENT_TYPE: 'Seul le type de document ATTESTATION_CONCORDANCE est pris en charge en itération 1.',
  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',
  INVALID_REQUEST: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',
  AI_EXTRACTION_ERROR: 'L\'extraction automatique a échoué. Veuillez reformuler votre demande.',
  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incomplètes. Veuillez reformuler votre demande.',
  BACKEND_UNAVAILABLE: 'Le service de génération est momentanément indisponible. Veuillez réessayer plus tard.',
  INTERNAL_ERROR: 'Le traitement de la demande a échoué. Veuillez réessayer plus tard.'
};'''
        
        # Replace ERROR_MESSAGES block
        new_error = '''const ERROR_MESSAGES = {
  VALIDATION_ERROR: 'Votre message ne respecte pas le format attendu. Veuillez le reformuler.',
  REQUEST_NOT_FOUND: 'Demande introuvable. Veuillez relancer une nouvelle demande.',
  AI_EXTRACTION_ERROR: 'L\'extraction automatique a échoué. Veuillez reformuler votre demande.',
  EXTRACTION_SCHEMA_INVALID: 'Les informations extraites sont invalides ou incomplètes. Veuillez reformuler votre demande.',
  BACKEND_UNAVAILABLE: 'Le service de génération est momentanément indisponible. Veuillez réessayer plus tard.',
  INTERNAL_ERROR: 'Le traitement de la demande a échoué. Veuillez réessayer plus tard.'
};'''
        
        # Use string replacement with markers
        import re
        code = re.sub(r'const SAFE_MESSAGES = \{[\s\S]*?\n\};', new_safe, code, count=1)
        code = re.sub(r'const ERROR_MESSAGES = \{[\s\S]*?\n\};', new_error, code, count=1)
        
        node['parameters']['jsCode'] = code
        print("Fixed FinalizeResponse")

# Fix InitOrchestration
for node in wf['nodes']:
    if node['name'] == 'InitOrchestration':
        code = node['parameters']['jsCode']
        new_ingress = '''const SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.'
};'''
        import re
        code = re.sub(r'const SAFE_INGRESS_MESSAGES = \{[\s\S]*?\n\};', 
                      '''const SAFE_INGRESS_MESSAGES = {
  UNEXPECTED_FIELDS: 'La requête contient des champs non autorisés.',
  INVALID_REQUEST_ID: 'Le champ « requestId » est absent ou invalide.',
  MESSAGE_REQUIRED: 'Le champ « message » est obligatoire.',
  MESSAGE_LENGTH_INVALID: 'Le champ « message » doit contenir entre 1 et 4000 caractères.
};''', code, count=1)
        node['parameters']['jsCode'] = code
        print("Fixed InitOrchestration")

# Fix Webhook notes
for node in wf['nodes']:
    if node['name'] == 'Webhook':
        node['parameters']['notes'] = node['parameters']['notes'].replace('posÃƒÂ©', 'posé')
        print("Fixed Webhook notes")

# Write back with proper UTF-8 (no BOM)
with open(output_path, 'w', encoding='utf-8') as f:
    json.dump(wf, f, ensure_ascii=False, separators=(',', ':'), indent=None)

print("All fixes applied")