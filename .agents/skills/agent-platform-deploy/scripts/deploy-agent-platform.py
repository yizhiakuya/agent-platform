#!/usr/bin/env python3
"""
Stable one-command deployment for Agent Platform.

The script keeps the fragile parts in one place:
- local build validation
- GHCR image packaging through the existing PowerShell builder
- LF-only remote bash execution over SSH
- compose image replacement with backup and public asset verification
"""

from __future__ import annotations

import argparse
import base64
import os
import shutil
import subprocess
import sys
from pathlib import Path


ALLOWED_SERVICES = ("agent-service", "web")
DEFAULT_HOST = "root@192.168.0.109"
DEFAULT_REMOTE_DIR = "/opt/agent-platform"
DEFAULT_PUBLIC_URL = "https://agent.rainaki.top:8443/chat"
DEFAULT_OWNER = "yizhiakuya"


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve() if args.repo_root else Path(__file__).resolve().parents[4]
    services = resolve_services(args.services)
    deploy_services = resolve_deploy_services(args, services)
    build_script = repo_root / ".agents" / "skills" / "agent-platform-deploy" / "scripts" / "build-ghcr-images.ps1"

    log(f"REPO_ROOT={repo_root}")
    log(f"SERVICES={','.join(deploy_services)}")

    if not args.skip_git_check:
        check_git_state(repo_root, allow_dirty=args.allow_dirty)
        if not args.skip_gitnexus:
            run(["npx", "gitnexus", "detect-changes", "--repo", str(repo_root)], cwd=repo_root)
        run(["git", "diff", "--check"], cwd=repo_root)

    if args.plan_only:
        log("PLAN_ONLY=1")

    images: dict[str, str] = {}
    if args.agent_image:
        images["AGENT_IMAGE"] = args.agent_image
    if args.web_image:
        images["WEB_IMAGE"] = args.web_image

    if not args.deploy_only:
        if not args.skip_local_build and not args.plan_only:
            run_local_builds(repo_root, services, java_home=args.java_home)
        images.update(build_images(repo_root, build_script, services, args))

    for key, value in images.items():
        log(f"{key}={value}")

    if args.build_only:
        log("BUILD_ONLY=1")
        return 0

    if args.plan_only:
        log(f"HOST={args.host}")
        log(f"REMOTE_DIR={args.remote_dir}")
        log(f"PUBLIC_URL={args.public_url}")
        log(f"LABEL={args.label}")
        return 0

    if not images:
        raise SystemExit("No images were built or provided. Use --services or --agent-image/--web-image.")

    deploy_remote(
        host=args.host,
        remote_dir=args.remote_dir,
        public_url=args.public_url,
        label=args.label,
        agent_image=images.get("AGENT_IMAGE", ""),
        web_image=images.get("WEB_IMAGE", ""),
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build, publish, and deploy Agent Platform to Megumin.")
    parser.add_argument("--services", default="web", help="Comma-separated services: web,agent-service")
    parser.add_argument("--tag-prefix", default="deploy", help="Tag prefix used by the GHCR build script.")
    parser.add_argument("--label", default=None, help="Remote compose backup label. Defaults to --tag-prefix.")
    parser.add_argument("--repo-root", default=None, help="Repository root. Auto-detected from this script by default.")
    parser.add_argument("--owner", default=DEFAULT_OWNER, help="GHCR owner.")
    parser.add_argument("--host", default=DEFAULT_HOST, help="SSH host for Megumin.")
    parser.add_argument("--remote-dir", default=DEFAULT_REMOTE_DIR, help="Remote compose directory.")
    parser.add_argument("--public-url", default=DEFAULT_PUBLIC_URL, help="Public URL to verify after deploy.")
    parser.add_argument("--agent-image", default="", help="Use an already pushed agent-service image.")
    parser.add_argument("--web-image", default="", help="Use an already pushed web image.")
    parser.add_argument("--java-home", default=r"D:\Apps\Temurin\jdk-21", help="JAVA_HOME for local Maven builds.")
    parser.add_argument("--skip-local-build", action="store_true", help="Use existing artifacts in target/ or web/dist.")
    parser.add_argument("--skip-git-check", action="store_true", help="Skip git status, git diff --check, and GitNexus checks.")
    parser.add_argument("--skip-gitnexus", action="store_true", help="Skip GitNexus detect-changes only.")
    parser.add_argument("--allow-dirty", action="store_true", help="Allow tracked working-tree changes.")
    parser.add_argument("--plan-only", action="store_true", help="Print intended images/deploy target without building or mutating remote.")
    parser.add_argument("--build-only", action="store_true", help="Build and push images, but do not deploy.")
    parser.add_argument("--deploy-only", action="store_true", help="Deploy provided --agent-image/--web-image without building images.")
    parser.add_argument("--docker-timeout-seconds", type=int, default=180, help="Docker startup timeout for builder script.")
    args = parser.parse_args()
    if args.label is None:
        args.label = args.tag_prefix
    if args.deploy_only and not (args.agent_image or args.web_image):
        parser.error("--deploy-only requires --agent-image or --web-image")
    return args


def resolve_services(value: str) -> list[str]:
    services: list[str] = []
    for item in value.split(","):
        service = item.strip()
        if not service:
            continue
        if service not in ALLOWED_SERVICES:
            raise SystemExit(f"Unsupported service {service!r}. Allowed: {', '.join(ALLOWED_SERVICES)}")
        if service not in services:
            services.append(service)
    if not services:
        raise SystemExit("At least one service is required.")
    return services


def resolve_deploy_services(args: argparse.Namespace, build_services: list[str]) -> list[str]:
    if not args.deploy_only:
        return build_services
    services: list[str] = []
    if args.agent_image:
        services.append("agent-service")
    if args.web_image:
        services.append("web")
    return services


def run(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    printable = " ".join(command)
    log(f"$ {printable}")
    return subprocess.run(
        command,
        cwd=str(cwd),
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=True,
    )


def check_git_state(repo_root: Path, *, allow_dirty: bool) -> None:
    status = run(["git", "status", "--porcelain"], cwd=repo_root, capture=True).stdout.splitlines()
    tracked = [line for line in status if not line.startswith("?? ")]
    untracked = [line for line in status if line.startswith("?? ")]
    if tracked and not allow_dirty:
        print("\n".join(tracked), file=sys.stderr)
        raise SystemExit("Tracked changes are present. Commit/stash them or pass --allow-dirty.")
    if untracked:
        log("UNTRACKED_FILES=" + "; ".join(line[3:] for line in untracked))


def run_local_builds(repo_root: Path, services: list[str], *, java_home: str) -> None:
    if "agent-service" in services:
        env = os.environ.copy()
        if java_home:
            env["JAVA_HOME"] = java_home
            env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
        mvnw = repo_root / "mvnw.cmd"
        run([str(mvnw), "-pl", "agent-service", "-am", "-DskipTests", "package"], cwd=repo_root, env=env)
    if "web" in services:
        run(["npm", "run", "build"], cwd=repo_root / "web")


def build_images(repo_root: Path, build_script: Path, services: list[str], args: argparse.Namespace) -> dict[str, str]:
    if not build_script.exists():
        raise SystemExit(f"Missing build script: {build_script}")
    ps = shutil.which("powershell") or shutil.which("powershell.exe")
    if not ps:
        raise SystemExit("PowerShell is required for GHCR packaging on this workstation.")
    command = [
        ps,
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(build_script),
        "-Services",
        ",".join(services),
        "-TagPrefix",
        args.tag_prefix,
        "-RepoRoot",
        str(repo_root),
        "-Owner",
        args.owner,
        "-DockerTimeoutSeconds",
        str(args.docker_timeout_seconds),
    ]
    if args.plan_only:
        command.append("-PlanOnly")
    result = run(command, cwd=repo_root, capture=True)
    print(result.stdout, end="" if result.stdout.endswith("\n") else "\n", flush=True)
    images: dict[str, str] = {}
    for line in result.stdout.splitlines():
        if line.startswith("AGENT_IMAGE="):
            images["AGENT_IMAGE"] = line.split("=", 1)[1].strip()
        if line.startswith("WEB_IMAGE="):
            images["WEB_IMAGE"] = line.split("=", 1)[1].strip()
    return images


def deploy_remote(
    *,
    host: str,
    remote_dir: str,
    public_url: str,
    label: str,
    agent_image: str,
    web_image: str,
) -> None:
    services = []
    if agent_image:
        services.append("agent-service")
    if web_image:
        services.append("web")
    service_list = " ".join(services)
    remote_script = remote_deploy_script(
        remote_dir=remote_dir,
        public_url=public_url,
        label=label,
        services=service_list,
        agent_image=agent_image,
        web_image=web_image,
    )
    encoded = base64.b64encode(remote_script.encode("utf-8")).decode("ascii")
    log(f"$ ssh {host} 'base64 -d | bash'")
    proc = subprocess.run(
        ["ssh", host, "base64 -d | bash"],
        input=encoded,
        text=True,
        encoding="ascii",
        check=True,
    )
    if proc.returncode != 0:
        raise SystemExit(proc.returncode)


def shell_single_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def log(message: str) -> None:
    print(message, flush=True)


def remote_deploy_script(
    *,
    remote_dir: str,
    public_url: str,
    label: str,
    services: str,
    agent_image: str,
    web_image: str,
) -> str:
    script = f"""\
set -euo pipefail
cd {shell_single_quote(remote_dir)}
agent_image={shell_single_quote(agent_image)}
web_image={shell_single_quote(web_image)}
label={shell_single_quote(label)}
services={shell_single_quote(services)}
public_url={shell_single_quote(public_url)}
bak="docker-compose.ghcr-photo.yml.bak-${{label}}-$(date +%Y%m%d-%H%M%S)"

cp docker-compose.ghcr-photo.yml "$bak"
python3 - <<'PY'
from pathlib import Path

p = Path('docker-compose.ghcr-photo.yml')
agent_image = {agent_image!r}
web_image = {web_image!r}
targets = {{}}
if agent_image:
    targets['agent-service'] = agent_image
if web_image:
    targets['web'] = web_image

out = []
current = None
for line in p.read_text().splitlines():
    stripped = line.strip()
    if line.startswith('  ') and not line.startswith('    ') and stripped.endswith(':'):
        current = stripped[:-1]
    if current in targets and stripped.startswith('image:'):
        indent = line[:len(line) - len(line.lstrip())]
        out.append(f'{{indent}}image: {{targets[current]}}')
    else:
        out.append(line)
p.write_text('\\n'.join(out) + '\\n')
PY

echo "COMPOSE_BACKUP=$bak"
for image in "$agent_image" "$web_image"; do
    if [ -n "$image" ]; then
        grep -nF "$image" docker-compose.ghcr-photo.yml
    fi
done

docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull $services
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml up -d --no-build --force-recreate $services

echo '--- ps ---'
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml ps $services

if printf '%s\\n' "$services" | grep -qw 'agent-service'; then
    echo '--- agent-service health ---'
    for i in $(seq 1 40); do
        if docker exec agent-platform-agent-service-1 sh -c 'wget -qO- http://localhost:8082/actuator/health' 2>/dev/null; then
            echo
            break
        fi
        if [ "$i" = "40" ]; then
            echo 'agent-service health failed after retry' >&2
            docker logs --tail 120 agent-platform-agent-service-1 >&2
            exit 1
        fi
        sleep 3
    done
fi

echo '--- caddy local http ---'
for i in $(seq 1 20); do
    if curl -fsSI http://localhost:8480/chat | sed -n '1,12p'; then
        break
    fi
    if [ "$i" = "20" ]; then
        echo 'local caddy check failed' >&2
        exit 1
    fi
    sleep 2
done

echo '--- public https ---'
for i in $(seq 1 20); do
    if curl -k -fsSI "$public_url" | sed -n '1,12p'; then
        break
    fi
    if [ "$i" = "20" ]; then
        echo 'public https check failed' >&2
        exit 1
    fi
    sleep 2
done

echo '--- public assets ---'
curl -k -fsS "$public_url" | grep -o 'assets/[^" ]*' | head -n 10 || true
echo 'DEPLOY_OK=1'
"""
    return script.replace("\r\n", "\n")


if __name__ == "__main__":
    raise SystemExit(main())
