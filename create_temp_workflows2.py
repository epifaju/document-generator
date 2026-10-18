import json
import os

# Read the original workflow
with open('n8n/workflows/document-generation-v1.json', 'r', encoding='utf-8') as f:
    original = json.load(f)

# Find the OllamaExtract node
ollama_node = None
for node in original['nodes']:
    if node.get('name') == 'OllamaExtract':
        ollama_node = node
        break

if not ollama_node:
    raise Exception("OllamaExtract node not found")

# Test cases - Fixed T-A29-2 to match the expected regex pattern
test_cases = [
    {
        'name': 'T-A29-2_expr_clamped3',
        'description': 'Expression with provable ceiling 3: Math.min(Math.max($env.AI_MAX_ATTEMPTS || 3, 1), 3)',
        'retryOnFail': True,
        'maxTries': "={{ Math.min(Math.max($env.AI_MAX_ATTEMPTS || 3, 1), 3) }}",
        'expected': 'PASS'
    },
    {
        'name': 'T-A29-3_maxtries_5',
        'description': 'maxTries=5 (Int32)',
        'retryOnFail': True,
        'maxTries': 5,
        'expected': 'FAIL'
    },
    {
        'name': 'T-A29-4_maxtries_2',
        'description': 'maxTries=2 (Int32)',
        'retryOnFail': True,
        'maxTries': 2,
        'expected': 'FAIL'
    },
    {
        'name': 'T-A29-5_retryfalse',
        'description': 'retryOnFail=false, maxTries=3',
        'retryOnFail': False,
        'maxTries': 3,
        'expected': 'FAIL'
    },
    {
        'name': 'T-A29-6_maxtries_absent',
        'description': 'maxTries absent/null',
        'retryOnFail': True,
        'maxTries': None,
        'expected': 'FAIL'
    },
    {
        'name': 'T-A29-7_maxtries_string',
        'description': 'maxTries="3abc" (non-numeric string)',
        'retryOnFail': True,
        'maxTries': "3abc",
        'expected': 'FAIL'
    }
]

temp_dir = os.path.join(os.environ.get('TEMP', 'C:\\Temp'), 'opencode')

for tc in test_cases:
    # Create a copy of the original workflow
    wf = json.loads(json.dumps(original))
    
    # Find and modify the OllamaExtract node in the copy
    for node in wf['nodes']:
        if node.get('name') == 'OllamaExtract':
            node['retryOnFail'] = tc['retryOnFail']
            if tc['maxTries'] is not None:
                node['maxTries'] = tc['maxTries']
            else:
                # Remove maxTries property
                if 'maxTries' in node:
                    del node['maxTries']
            break
    
    # Write to temp file
    output_path = os.path.join(temp_dir, f"{tc['name']}.json")
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(wf, f, ensure_ascii=False)
    
    print(f"Created: {output_path}")
    print(f"  Expected: {tc['expected']}")

print("\nAll temp workflows created.")