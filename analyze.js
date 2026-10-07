const fs = require('fs');
const raw = fs.readFileSync('/home/node/.n8n/workflows/document-generation-v1.json', 'utf8');
const idx = raw.indexOf('"name": "InitOrchestration"');
let pos = raw.indexOf('"jsCode": "', idx);
pos += '"jsCode": "'.length;
let end = pos;
let quoteCount = 0;
let inEscape = false;
while (end < raw.length) {
    const ch = raw[end];
    if (inEscape) { inEscape = false; end++; continue; }
    if (ch === '\\\\') { inEscape = true; end++; continue; }
    if (ch === '"') { quoteCount++; }
    if (quoteCount % 2 === 0) { break; }
    end++;
}
const jsCode = raw.substring(pos, end);
const lines = jsCode.split('\n');
console.log('Line count:', lines.length);
for (let i = 0; i < lines.length; i++) {
    const display = lines[i].length > 200 ? lines[i].substring(0, 200) + '...' : lines[i];
    console.log((i+1) + ': ' + display);
}