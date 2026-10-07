const fs = require('fs');
let r = JSON.parse(fs.readFileSync('/home/node/.n8n/workflows/document-generation-v1.json', 'utf8'));
// Set active to true
r.active = true;
// Write back
fs.writeFileSync('/home/node/.n8n/workflows/document-generation-v1.json', JSON.stringify(r, null, 2));
console.log('Set active to true');