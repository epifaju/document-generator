import json
import hashlib

with open('n8n/workflows/document-generation-v1.json', 'r') as f:
    wf = json.load(f)

for node in wf['nodes']:
    if node.get('name') == 'InitOrchestration':
        js = node['parameters']['jsCode']
        lines = js.split('\n')
        print('Repo workflow after import:')
        for i, line in enumerate(lines, 1):
            if 'replace' in line.lower() or 'generateu' in line.lower() or 'crypto' in line.lower() or line.strip() == ';':
                print('  Line %d: %s' % (i, line))
        good = '.replace(/[xy]/, function (c) {' in js
        # Bad format: missing comma after [xy]
        bad_no_space = '.replace(/[xy]/function(c) {' in js
        bad_with_space = '.replace(/[xy]/function (c) {' in js
        print('Good format present:', good)
        print('Bad format (no space):', bad_no_space)
        print('Bad format (with space):', bad_with_space)
        break

# Also compute SHA256
for node in wf['nodes']:
    if node.get('name') == 'InitOrchestration':
        js = node['parameters']['jsCode']
        sha = hashlib.sha256(js.encode('utf-8')).hexdigest()
        print('InitOrchestration SHA256:', sha)
        break