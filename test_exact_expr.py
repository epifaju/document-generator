import json
import os

# Read the original workflow
with open('n8n/workflows/document-generation-v1.json', 'r', encoding='utf-8') as f:
    original = json.load(f)

# Test the EXACT expression from the test matrix
test_expr = "={{ Math.min(Math.max(Number($env.AI_MAX_ATTEMPTS) || 3, 1), 3) }}"

wf = json.loads(json.dumps(original))
for node in wf['nodes']:
    if node.get('name') == 'OllamaExtract':
        node['retryOnFail'] = True
        node['maxTries'] = test_expr
        break

temp_dir = os.path.join(os.environ.get('TEMP', 'C:\\Temp'), 'opencode')
output_path = os.path.join(temp_dir, "T-A29-2_exact_matrix.json")
with open(output_path, 'w', encoding='utf-8') as f:
    json.dump(wf, f, ensure_ascii=False)

print(f"Created: {output_path}")
print(f"Expression: {test_expr}")