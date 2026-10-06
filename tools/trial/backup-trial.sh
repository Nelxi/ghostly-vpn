#!/bin/bash
# Ghostly backend + config backup before trial-attestation rollout.
# YOU run this manually per host. It only reads: pg_dump + config copy.
# Usage: ./backup-trial.sh user@host [pg_db_name]
# Output on the remote: /root/ghostly-backup/YYYY-MM-DD/
set -euo pipefail

HOST="${1:?usage: backup-trial.sh user@host [db]}"
DB="${2:-ghostly}"
SSH_OPTS="-p 2149 -i C:/Users/kille/Videos/krazeshit_new.ppk"
DATE="$(date +%F)"
DIR="/root/ghostly-backup/$DATE"

echo "== backup $HOST db=$DB -> $DIR =="
# shellcheck disable=SC2029
ssh $SSH_OPTS "$HOST" "set -e
  mkdir -p '$DIR'
  if command -v pg_dump >/dev/null; then
    pg_dump -Fc '${DB}' > '$DIR/db.dump' && echo saved db.dump
  else
    echo 'pg_dump not found, skipping db dump'
  fi
  for p in /etc/ghostly /opt/ghostly /root/ghostly /etc/bot /opt/bot; do
    [ -e \"\$p\" ] && cp -a \"\$p\" '$DIR/' && echo \"saved \$p\" || true
  done
  (sha256sum '$DIR'/* 2>/dev/null || true) | tee '$DIR/SHA256SUMS'
  ls -la '$DIR'
"
echo "Hosts: FI 78.17.1.122 | NL-old 5.253.63.153 | NL-new 185.244.49.33 | DE 179.254.127.78 | EE 13.143.239.51"
