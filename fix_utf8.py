#!/usr/bin/env python3
import sys

input_path = r'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'
output_path = r'C:\Users\epifa\opencode_workspace\dev\document-generator\n8n\workflows\document-generation-v1.json'

with open(input_path, 'rb') as f:
    data = bytearray(f.read())

def replace_bytes(data, search, replace):
    """Replace all occurrences of search bytes with replace bytes."""
    search = bytes(search)
    replace = bytes(replace)
    if search not in data:
        return data
    return data.replace(search, replace)

data = bytearray(open(input_path, 'rb').read())

# Fix double-encoded UTF-8 sequences
# é = 0xC3 0xA9 was double-encoded as 0xC3 0x83 0xC2 0xA9
data = data.replace(bytes([195,131,194,169]), bytes([195,169]))  # é
data = data.replace(bytes([195,131,194,168]), bytes([195,168]))  # è
data = data.replace(bytes([195,131,194,160]), bytes([195,160]))  # à
data = data.replace(bytes([195,131,194,170]), bytes([195,170]))  # ê
data = data.replace(bytes([195,131,194,174]), bytes([195,174]))  # î
data = data.replace(bytes([195,131,194,180]), bytes([195,180]))  # ô
data = data.replace(bytes([195,131,194,167]), bytes([195,167]))  # ç
data = data.replace(bytes([195,131,194,185]), bytes([195,185]))  # ù
data = data.replace(bytes([195,131,194,187]), bytes([195,187]))  # û
data = data.replace(bytes([195,131,194,162]), bytes([195,162]))  # â
data = data.replace(bytes([195,131,194,164]), bytes([195,164]))  # ä
data = data.replace(bytes([195,131,194,186]), bytes([195,186]))  # æ
data = data.replace(bytes([195,131,194,182]), bytes([195,182]))  # ö
data = data.replace(bytes([195,131,194,188]), bytes([195,188]))  # ü
data = data.replace(bytes([195,131,194,174]), bytes([195,174]))  # î
data = data.replace(bytes([195,131,194,179]), bytes([195,179]))  # ô

# Fix Webhook notes "posé"
data = data.replace(bytes([112,111,115,195,131,194,169]), bytes([112,111,115,195,169]))

with open(output_path, 'wb') as f:
    f.write(data)

print("Done")