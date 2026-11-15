# FileBackup

Self-hosted, single-service file backup with versioned history, content dedup, and integrity checking. One Spring Boot jar, two deployment targets chosen by Spring profile — `local` (Postgres + filesystem blobs, home server) or `aws` (DynamoDB + S3). Detailed design in `agent/plans/filebackup-lld.md` + `filebackup-hld.md`.

## Requirements

- Java 23, `./mvnw` (wrapper downloads Maven itself)
- Docker Postgres running (container `postgres:latest`, port 5432, user `postgres`, password `mysecretpassword`) with databases `filebackup` (dev) and `filebackup_test`, `filebackup_test_repo` (tests)

## Commands

```bash
./mvnw spring-boot:run   # dev profile → http://localhost:8080
./mvnw test              # needs the Docker Postgres up
```

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` → `{token}` (JWT, 30 days) |
| PUT | `/api/files/{username}/…` | upload (new version; path **must** start with your username) |
| POST | `/api/uploads` | presigned initiate: `{path, sha256, sizeBytes}` → committed or `{uploadUrl}` |
| POST | `/api/uploads/complete` | presigned finalize: verify blob, commit version |
| PUT | `/api/blob?key&expires&signature` | local-profile presigned receiver (signature = the auth, no JWT) |
| GET | `/api/files/{username}/…[?version=n]` | download (latest or specific version) |
| HEAD | `/api/files/{username}/…` | cheap latest-version check: `X-Checksum-Sha256`, `X-Version` |
| GET | `/api/files?prefix=` | browse (non-admins pinned to their own prefix) |
| DELETE | `/api/files/{username}/…` | soft delete (tombstone; nothing is removed) |
| GET | `/api/health` | liveness + blob integrity report (no auth) |

Auth: `Authorization: Bearer <jwt>` on everything except login and health. Cross-user access returns 404 (no path leaking); the admin user can reach all prefixes.

Interactive docs: **`/swagger-ui.html`** (OpenAPI JSON at `/v3/api-docs`) — no auth; log in via `/api/auth/login`, then click *Authorize* and paste the JWT.

## Configuration (`application-dev.yml` / env)

| Key | Meaning |
|---|---|
| `filebackup.users` | seeded on first boot (bcrypt); existing users are never modified |
| `filebackup.admin-user` | username that bypasses the per-user path prefix |
| `filebackup.jwt-secret` | HS256 signing key (≥ 32 bytes; **override in any real deployment**) |
| `filebackup.blobs-dir` | blob storage root (local profile) |
| `filebackup.keep-versions` | retention: keep last N versions per path (default 10) |

## How it works

- Upload: streamed (multi-GB safe, nothing buffered in RAM), SHA-256 computed while storing; blob key = hash, so identical content is stored once and a new version row just references it.
- Download: streamed with the checksum verified during transfer; `GET /api/health` reports corrupt blobs (`DEGRADED` + list).
- Delete: tombstone only — a buggy client can never silently erase your backups; re-uploading the same path re-activates it.
- Retention: uploads beyond the newest `keep-versions` are pruned (metadata only; deduped blobs are shared, garbage collection is post-v1).

### Presigned uploads (both profiles)

For large files, the client can ask the server to hand out a short-lived signed upload URL instead of streaming through `POST /api/files/…`:

```
1. POST /api/uploads {path, sha256, sizeBytes}          (JWT)
     → {"status":"committed","version":n}    blob already exists (dedup) — done, nothing to upload
     → {"status":"presigned","uploadUrl":"…","method":"PUT"}
2. PUT the bytes to uploadUrl                           (no JWT — the URL signature is the auth)
3. POST /api/uploads/complete {path, sha256, sizeBytes} (JWT) → 201 {path, version}   (missing/mismatch → 409)
```

The client never branches on backend: `aws` returns a real S3 presigned PUT (bytes go straight to S3, server untouched); `local` returns `PUT /api/blob?key=<sha256>&expires=<epoch-ms>&signature=<hmac>` (bytes hit our server, HMAC-verified against `filebackup.jwt-secret`, 15-min expiry). `complete` verifies against actual storage before committing — a client lying about the hash gets a 409, because the bytes land under their *true* hash (local) or a size/checksum mismatch (aws). Presigned URLs are bearer-equivalent: anyone holding one can upload that one blob for 15 minutes — HTTPS in front mitigates.

AWS note: enable bucket-level default SHA-256 checksums so `complete` can verify content, not just size. Abandoned presigned uploads (initiate without complete) leave orphan blobs/objects — GC is post-v1.

## Client

```bash
SERVER=http://your-server TOKEN=$(curl -s -X POST "$SERVER/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"shivam","password":"…"}' | jq -r .token) \
BACKUP_USER=shivam ./scripts/backup-client.sh ~/Documents
```

Walks the folder, hashes each file, and uploads only when the hash differs from the server's latest (`HEAD` check first). Run from cron. Files with spaces/odd characters are skipped (server path rules).

## AWS profile

`--spring.profiles.active=aws` swaps in `S3FileStorage` + `DynamoMetadataStore` (single table, PK=`path`, SK=`#meta`/`v000001…`). Needs env: `AWS_*` credentials via the default chain, `FILEBACKUP_BUCKET`, `FILEBACKUP_TABLE` (table created out of band), `FILEBACKUP_JWT_SECRET`, `FILEBACKUP_ADMIN_USER`, `FILEBACKUP_ADMIN_PASSWORD`. Always behind HTTPS.
