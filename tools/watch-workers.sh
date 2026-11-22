#!/usr/bin/env bash
# Watches Codex workers without relying on their process exiting.
# Usage: bash tools/watch-workers.sh <name> [<name> ...]
#   <name> matches work/logs/<name>-final.md (the -o report) and the worktree ../Paperless-wt/<name>.
# Prints one line per event (for the Monitor tool): REPORT, DIED, KILLED-LINGERING, HEARTBEAT, ALL-DONE.
set -u
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
POLL=${POLL:-60}
HEARTBEAT=${HEARTBEAT:-600}
declare -A state
for n in "$@"; do state[$n]=running; done
start=$(date +%s); last_beat=$start

pids_for() {  # codex processes whose command line mentions the worktree
  powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'codex%'\" | Where-Object { \$_.CommandLine -match 'Paperless-wt[\\\\/]$1' } | ForEach-Object { \$_.ProcessId }" 2>/dev/null | tr -d '\r'
}

while :; do
  pending=0
  for n in "$@"; do
    [ "${state[$n]}" = running ] || continue
    report="work/logs/$n-final.md"
    pids=$(pids_for "$n")
    if [ -s "$report" ]; then
      state[$n]=done
      echo "REPORT $n ($(wc -c < "$report") bytes)"
      if [ -n "$pids" ]; then
        sleep 30
        pids=$(pids_for "$n")
        if [ -n "$pids" ]; then
          for p in $pids; do powershell.exe -NoProfile -Command "Stop-Process -Id $p -Force -ErrorAction SilentlyContinue"; done
          echo "KILLED-LINGERING $n pids: $(echo $pids)"
        fi
      fi
    elif [ -z "$pids" ]; then
      state[$n]=died
      echo "DIED $n (no report; see work/logs/$n.log)"
    else
      pending=$((pending + 1))
    fi
  done
  now=$(date +%s)
  if [ $pending -eq 0 ]; then echo "ALL-DONE after $(( (now - start) / 60 )) min"; exit 0; fi
  if [ $((now - last_beat)) -ge "$HEARTBEAT" ]; then
    running=$(for n in "$@"; do [ "${state[$n]}" = running ] && printf '%s ' "$n"; done)
    echo "HEARTBEAT $(( (now - start) / 60 )) min, still running: $running"
    last_beat=$now
  fi
  sleep "$POLL"
done
