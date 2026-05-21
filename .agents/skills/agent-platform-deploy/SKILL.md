---
name: agent-platform-deploy
description: Deploy Agent Platform from D:\agent-platform to Megumin. Use when building local Maven/Web artifacts into GHCR images, updating /opt/agent-platform/docker-compose.ghcr-photo.yml on root@192.168.0.109, verifying live health/assets, recovering from Docker Desktop not running, or rolling back agent-service/web image tags.
---

# Agent Platform Deploy

## Purpose

Ship Agent Platform changes safely from this Windows workstation to Megumin (`root@192.168.0.109`, `/opt/agent-platform`) using local builds, GHCR images, remote compose backup, targeted service restart, and readiness checks.

Use the broader `agent-platform` skill for architecture and service ownership. Use this skill for the release mechanics.

## Release Rules

- Build and test locally before image packaging. Docker is packaging/runtime only, not the Maven or npm build runner.
- Keep the worktree clean before image builds. Untracked intentional files such as `APP.txt` may remain uncommitted, but call them out.
- If Docker is not running, start Docker Desktop first and wait for `docker version` to report a server.
- Do not use `docker compose down -v`.
- Do not print secrets from `.env`, registry auth, provider keys, or tokens.
- Use timestamp plus short SHA tags, never floating-only `latest` for production compose updates.
- Back up `/opt/agent-platform/docker-compose.ghcr-photo.yml` before editing it.
- After editing compose, `grep` the exact new image tags before `pull` or `up`.
- Use the Python entrypoint's LF-normalized base64 SSH runner for remote mutation scripts that contain `$VAR`, `$(...)`, heredocs, or quotes.
- Verify with retries. `agent-service` can take several seconds after `up -d` before `/actuator/health` is ready.
- Health ports: `agent-service=8082`, `chat-service=8084`, `gateway=8080`, `web=80` inside compose; public Caddy is `https://agent.rainaki.top:8443`.

## Standard Workflow

Preferred one-command path:

```powershell
python .agents\skills\agent-platform-deploy\scripts\deploy-agent-platform.py `
  --services web `
  --tag-prefix live-reply-cleanup
```

Common variants:

```powershell
# Show planned image tags and target without building or touching Megumin.
python .agents\skills\agent-platform-deploy\scripts\deploy-agent-platform.py `
  --services web,agent-service `
  --tag-prefix release-check `
  --plan-only `
  --allow-dirty

# Deploy an already pushed image without rebuilding.
python .agents\skills\agent-platform-deploy\scripts\deploy-agent-platform.py `
  --deploy-only `
  --web-image ghcr.io/yizhiakuya/agent-platform-web:<tag> `
  --label rollback-or-hotfix
```

The Python entrypoint is the stable deploy wrapper. It runs git/GitNexus checks,
local builds, GHCR packaging, LF-only SSH deployment, compose backup, exact image
grep, targeted compose pull/up, and live URL/asset verification.

1. Inspect state:

```powershell
git status --short --branch
npx gitnexus detect-changes --repo "D:\agent-platform"
docker version
```

2. Run targeted tests/builds for the changed services:

```powershell
$env:JAVA_HOME = 'D:\Apps\Temurin\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd "-pl" "agent-service" "-am" "-Dtest=<Tests>" "-Dsurefire.failIfNoSpecifiedTests=false" test
Push-Location web; npm run build; Pop-Location
git diff --check
```

3. Commit and push the branch before deploying.

4. Package, push, and deploy images through the Python entrypoint:

```powershell
python .agents\skills\agent-platform-deploy\scripts\deploy-agent-platform.py `
  --services agent-service,web `
  --tag-prefix final-reply
```

The script emits `AGENT_IMAGE=...`, `WEB_IMAGE=...`, `COMPOSE_BACKUP=...`, live
asset hashes, and `DEPLOY_OK=1` when successful. Use `--plan-only` first when
validating paths and intended tags without building, pushing, or touching Megumin.

5. Deploy only already-pushed images:

```powershell
python .agents\skills\agent-platform-deploy\scripts\deploy-agent-platform.py `
  --deploy-only `
  --agent-image ghcr.io/yizhiakuya/agent-platform-agent-service:<tag> `
  --web-image ghcr.io/yizhiakuya/agent-platform-web:<tag> `
  --label rollback-or-hotfix
```

Use `--plan-only` with `--deploy-only` to print the target host, services, images,
and public URL without SSH or remote mutation.

6. Report:

- branch and commit
- exact image tags
- backup compose file name
- local tests/builds run
- live health and public URL result
- any residual risk or untracked files

## Deploy Script Notes

`scripts/deploy-agent-platform.py`:

- Default service is `web`; pass `--services web,agent-service` for both.
- Runs local builds unless `--skip-local-build` is passed.
- Refuses tracked dirty worktrees unless `--allow-dirty` is passed; untracked
  files such as `APP.txt` are reported but not deployed.
- Starts Docker Desktop if the Docker server is unavailable.
- Packages `agent-service` from `agent-service/target/agent-service-0.0.1-SNAPSHOT.jar`.
- Packages `web` from `web/dist` plus `web/nginx.conf`.
- Uses tiny temporary Docker contexts under `%TEMP%`.
- Does not install packages during Docker build. Do not add `apk add curl`; Alpine repository access can fail and business images do not need curl for runtime.
- Pushes to:
  - `ghcr.io/yizhiakuya/agent-platform-agent-service:<tag>`
  - `ghcr.io/yizhiakuya/agent-platform-web:<tag>`
- Deploys via SSH with an LF-normalized base64 bash script sent over stdin. This avoids shell CRLF/quoting corruption in remote `set -euo pipefail` scripts.
- Supports `--plan-only`, `--build-only`, and `--deploy-only`.
- Prints `AGENT_IMAGE=...`, `WEB_IMAGE=...`, `COMPOSE_BACKUP=...`, live asset
  hashes, and `DEPLOY_OK=1` when successful.

## Rollback

Use the compose backup named by the deploy output, then pull/recreate the affected services:

```powershell
python .agents\skills\agent-platform-deploy\scripts\deploy-agent-platform.py `
  --rollback-backup docker-compose.ghcr-photo.yml.bak-<label>-<timestamp> `
  --services agent-service,web `
  --skip-git-check
```

The rollback path restores the backup, pulls/recreates the requested services,
and reruns the same health/public checks.
