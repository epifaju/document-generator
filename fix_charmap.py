#!/usr/bin/env python3
import json
import re

input_path = r'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
output_path = r'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'

with open(input_path, 'r', encoding='utf-8') as f:
    wf = json.load(f)

# Character mapping for mojibake -> correct
char_map = {
    'Ǹ': 'é',  # U+01F8 -> é
    'ǹ': 'é',  # U+01F9 -> é (if present)
    'ǟ': 'é',  # U+01DF -> é (if present)
    'ǎ': 'à',  # U+01CE -> à
    'ě': 'è',  # U+01C7 -> è
    'ǐ': 'î',  # U+01D0 -> î
    'ǒ': 'ô',  # U+01D2 -> ô
    'ǔ': 'ù',  # U+01D4 -> ù
    'ǖ': 'û',  # U+01D6 -> û
    'ǘ': 'ü',  # U+01D8 -> ü
    'ǚ': 'û',  # U+01DA -> û (alt)
    'ǜ': 'ü',  # U+01DC -> ü (alt)
    'Ǟ': 'ä',  # U+01DE -> ä
    'Ǡ': 'ā',  # U+01E0 -> ā
    'ǡ': 'ă',  # U+01E1 -> ă
    'ǣ': 'æ',  # U+01E3 -> æ
    'Ǳ': 'DŽ', # U+01F1 -> DŽ
    'ǲ': 'Dž', # U+01F2 -> Dž
    'ǳ': 'dž', # U+01F3 -> dž
}

def fix_mojibake(text):
    for bad, good in char_map.items():
        text = text.replace(bad, good)
    return text

with open(input_path, 'r', encoding='utf-8') as f:
    wf = json.load(f)

# Fix all jsCode in all nodes
for node in wf['nodes']:
    if node['type'] == 'n8n-nodes-base.code' and 'jsCode' in node['parameters']:
        node['parameters']['jsCode'] = fix_mojibake(node['parameters']['jsCode'])
    if 'notes' in node['parameters']:
        node['parameters']['notes'] = fix_mojibake(node['parameters']['notes'])

# Fix Webhook notes specifically
for node in wf['nodes']:
    if node['name'] == 'Webhook' and 'notes' in node['parameters']:
        node['parameters']['notes'] = fix_mojibake(node['parameters']['notes'])

# Write back
with open(output_path, 'w', encoding='utf-8') as f:
    json.dump(wf, f, ensure_ascii=False, separators=(',', ':'), indent=None)

print("Character-level fix applied")