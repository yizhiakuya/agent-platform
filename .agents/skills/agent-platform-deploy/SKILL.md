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
- Use remote base64 scripts for PowerShell-to-SSH operations that contain `$VAR`, `$(...)`, heredocs, or quotes.
- Verify with retries. `agent-service` can take several seconds after `up -d` before `/actuator/health` is ready.
- Health ports: `agent-service=8082`, `chat-service=8084`, `gateway=8080`, `web=80` inside compose; public Caddy is `https://agent.rainaki.top:8443`.

## Standard Workflow

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

4. Package and push images from already-built artifacts:

```powershell
powershell -ExecutionPolicy Bypass -File .agents\skills\agent-platform-deploy\scripts\build-ghcr-images.ps1 `
  -Services agent-service,web `
  -TagPrefix final-reply
```

The script emits `AGENT_IMAGE=...` and/or `WEB_IMAGE=...`.
Use `-PlanOnly` first when validating paths and intended tags without building or pushing.
Use comma form for `-Services`, such as `agent-service,web`. Do not pass services as a space-separated positional list.

5. Deploy only the changed services:

```powershell
powershell -ExecutionPolicy Bypass -File .agents\skills\agent-platform-deploy\scripts\deploy-megumin.ps1 `
  -AgentImage ghcr.io/yizhiakuya/agent-platform-agent-service:<tag> `
  -WebImage ghcr.io/yizhiakuya/agent-platform-web:<tag>
```

Use `-PlanOnly` first to print the target host, services, images, and public URL without SSH or remote mutation.

6. Report:

- branch and commit
- exact image tags
- backup compose file name
- local tests/builds run
- live health and public URL result
- any residual risk or untracked files

## Build Script Notes

`scripts/build-ghcr-images.ps1`:

- Starts Docker Desktop if the Docker server is unavailable.
- Supports `-PlanOnly` to verify artifact paths and output intended image tags without Docker build/push.
- Packages `agent-service` from `agent-service/target/agent-service-0.0.1-SNAPSHOT.jar`.
- Packages `web` from `web/dist` plus `web/nginx.conf`.
- Uses tiny temporary Docker contexts under `%TEMP%`.
- Does not install packages during Docker build. Do not add `apk add curl`; Alpine repository access can fail and business images do not need curl for runtime.
- Pushes to:
  - `ghcr.io/yizhiakuya/agent-platform-agent-service:<tag>`
  - `ghcr.io/yizhiakuya/agent-platform-web:<tag>`

If a jar or `web/dist` is missing, run the local Maven/npm build first.

## Deploy Script Notes

`scripts/deploy-megumin.ps1`:

- Mutates only `agent-service` and/or `web` image lines in `/opt/agent-platform/docker-compose.ghcr-photo.yml`.
- Supports `-PlanOnly` to print the intended remote deployment without SSH.
- Creates `docker-compose.ghcr-photo.yml.bak-<label>-<timestamp>`.
- Runs `docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull <services>`.
- Runs `up -d --no-build --force-recreate <services>`.
- Verifies:
  - `agent-service` health from inside its container with `wget`
  - local Caddy HTTP on `http://localhost:8480/chat`
  - public HTTPS on `https://agent.rainaki.top:8443/chat`
  - public asset hashes from the served index
  - container image tags in `docker compose ps`

## Rollback

Use the compose backup named by the deploy output, then pull/recreate the affected services:

```powershell
$backup = 'docker-compose.ghcr-photo.yml.bak-<label>-<timestamp>'
$remote = @'
set -euo pipefail
cd /opt/agent-platform
cp "__BACKUP__" docker-compose.ghcr-photo.yml
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull agent-service web
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml up -d --no-build --force-recreate agent-service web
'@
$remote = $remote.Replace('__BACKUP__', $backup)
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
ssh root@192.168.0.109 "echo $b64 | base64 -d | bash"
```

Then rerun the deploy script's verification or manually check health/public headers.
