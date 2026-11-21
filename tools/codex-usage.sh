#!/usr/bin/env bash
# Prints Codex 5h + weekly usage. Usage: bash codex-usage.sh   (add --raw for full JSON)
out=$( (printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"usage","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"initialized"}' \
 '{"jsonrpc":"2.0","id":2,"method":"account/rateLimits/read"}'; sleep 5) | timeout 12 codex app-server 2>/dev/null | grep '"id":2')
if [ "$1" = "--raw" ]; then echo "$out"; exit 0; fi
node -e '
const r=JSON.parse(process.argv[1]).result.rateLimits;
const f=(w,n)=>console.log(n+": "+w.usedPercent+"% used, resets "+new Date(w.resetsAt*1000).toLocaleString());
f(r.primary,"5-hour"); f(r.secondary,"weekly");
console.log("plan: "+r.planType+" | limit reached: "+(r.rateLimitReachedType||"no"));
' "$out"
