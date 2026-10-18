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

# Test T-A29-2 with different expression formats to find one that matches the regex
# The regex is: Math\.min\([^)]*Math\.max\([^)]*\|\|\s*3\s*\)[^)]*,\s*3\)
# This expects: Math.min( <no )> Math.max( <no )> || 3 ) <no )> , 3 )
# The problem: nested calls have ) inside
# Let me try: Math.min(3, Math.max($env.AI_MAX_ATTEMPTS || 3, 1)) - equivalent but outer is min(3, ...)
# Actually that's still nested. What if the regex matches Math.max first?
# No, regex starts with Math.min

# Let me try an expression where Math.max appears later, not immediately
# Math.min( some_var, Math.max(...) ) - but n8n expressions don't have variables

# Actually, let me check if the regex might work with the expression stored differently
# Maybe the expression should NOT have the ={{ }} wrapper in the JSON value?
# But n8n requires ={{ }} for expressions

# Let me try a few variants
test_exprs = [
    "={{ Math.min(3, Math.max($env.AI_MAX_ATTEMPTS || 3, 1)) }}",  # swapped args
    "={{ Math.min(Math.max($env.AI_MAX_ATTEMPTS || 3, 1), 3) }}",  # original
    "={{ Math.max(1, Math.min(3, $env.AI_MAX_ATTEMPTS || 3)) }}",  # max outer
    "={{ (($env.AI_MAX_ATTEMPTS || 3) > 3 ? 3 : (($env.AI_MAX_ATTEMPTS || 3) < 1 ? 1 : ($env.AI_MAX_ATTEMPTS || 3))) }}",  # ternary
    "3",  # just integer 3 (should work per validator)
]

temp_dir = os.path.join(os.environ.get('TEMP', 'C:\\Temp'), 'opencode')

for i, expr in enumerate(test_exprs):
    wf = json.loads(json.dumps(original))
    for node in wf['nodes']:
        if node.get('name') == 'OllamaExtract':
            node['retryOnFail'] = True
            node['maxTries'] = expr
            break
    output_path = os.path.join(temp_dir, f"T-A29-2_variant_{i}.json")
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(wf, f, ensure_ascii=False)
    print(f"Created variant {i}: {expr}")

print("Done.")