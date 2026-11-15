#!/usr/bin/env bash
# FileBackup client: pushes a folder to the server, uploading only files whose
# content hash differs from the server's latest version. No local state.
#
# Usage:   ./backup-client.sh <source-dir>
# Env:     SERVER (default http://localhost:8080)
#          TOKEN  (JWT from POST /api/auth/login)   required
#          BACKUP_USER (your username = first path segment)   required
#
# Limitation: file/dir names with spaces, quotes, or non [A-Za-z0-9._/-] chars are skipped (server path rules).

set -euo pipefail

SERVER="${SERVER:-http://localhost:8080}"
: "${TOKEN:?Set TOKEN (JWT from POST /api/auth/login)}"
: "${BACKUP_USER:?Set BACKUP_USER (your username)}"
SRC="${1:?Usage: $0 <source-dir>}"

[ -d "$SRC" ] || { echo "Not a directory: $SRC" >&2; exit 1; }

count_total=0; count_uploaded=0; count_skipped=0; count_failed=0

find "$SRC" -type f | while IFS= read -r file; do
  rel="${file#"$SRC"/}"
  path="$BACKUP_USER/$rel"
  case "$path" in
    *[!A-Za-z0-9._/-]*) echo "SKIP (unsupported chars): $path" >&2; continue ;;
  esac
  count_total=$((count_total+1))

  local_hash="$(shasum -a 256 "$file" | cut -d' ' -f1)"

  # Ask the server what it has (404 = never backed up)
  remote="$(curl -sS -I --fail-with-body -H "Authorization: Bearer $TOKEN" "$SERVER/api/files/$path" 2>/dev/null | tr -d '\r' || true)"
  remote_hash="$(printf '%s\n' "$remote" | awk -F': ' 'tolower($1)=="x-checksum-sha256"{print $2}')"

  if [ "$local_hash" = "$remote_hash" ]; then
    count_skipped=$((count_skipped+1))
    echo "skip  $path"
  else
    if curl -sS -X PUT --fail-with-body -o /dev/null \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/octet-stream" \
        --data-binary "@$file" "$SERVER/api/files/$path"; then
      count_uploaded=$((count_uploaded+1))
      echo "sent  $path"
    else
      count_failed=$((count_failed+1))
      echo "FAIL  $path" >&2
    fi
  fi
done

echo "done: $count_uploaded uploaded, $count_skipped unchanged, $count_failed failed"
