import json
with open('n8n/workflows/document-generation-v1.json', 'r', encoding='utf-8') as f:
    data = json.load(f)
for node in data['nodes']:
    if 'OllamaExtract' in node.get('name', '') or 'ollama' in node.get('name', '').lower():
        print(f"Node: {node['name']}")
        print(f"Type: {node.get('type')}")
        print(f"ID: {node.get('id')}")
        print(f"Parameters: {json.dumps(node.get('parameters', {}), indent=2)}")
        print('---')