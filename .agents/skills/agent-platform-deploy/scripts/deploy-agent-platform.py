#!/usr/bin/env python3
"""
Stable one-command deployment for Agent Platform.

The script keeps the fragile parts in one place:
- local build validation
- image packaging from already-built artifacts
- local Megumin compose deployment, or LF-only remote bash execution over SSH
- compose image replacement with backup and public asset verification
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import os
import shutil
import subprocess
import sys
import tempfile
import time
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
    local_megumin = is_local_megumin(args.remote_dir)

    log(f"REPO_ROOT={repo_root}")
    log(f"SERVICES={','.join(deploy_services)}")
    log(f"DEPLOY_MODE={'local-megumin' if local_megumin else 'remote-ghcr'}")

    if not args.skip_git_check:
        check_git_state(repo_root, allow_dirty=args.allow_dirty)
        if not args.skip_gitnexus:
            run(["npx", "gitnexus", "detect-changes", "--repo", str(repo_root)], cwd=repo_root)
        run(["git", "diff", "--check"], cwd=repo_root)

    if args.plan_only:
        log("PLAN_ONLY=1")

    if args.rollback_backup:
        if args.plan_only:
            if not local_megumin:
                log(f"HOST={args.host}")
            log(f"REMOTE_DIR={args.remote_dir}")
            log(f"PUBLIC_URL={args.public_url}")
            log(f"ROLLBACK_BACKUP={args.rollback_backup}")
            return 0
        if local_megumin:
            rollback_local(
                remote_dir=args.remote_dir,
                public_url=args.public_url,
                backup=args.rollback_backup,
                services=deploy_services,
            )
        else:
            rollback_remote(
                host=args.host,
                remote_dir=args.remote_dir,
                public_url=args.public_url,
                backup=args.rollback_backup,
                services=deploy_services,
            )
        return 0

    images: dict[str, str] = {}
    if args.agent_image:
        images["AGENT_IMAGE"] = args.agent_image
    if args.web_image:
        images["WEB_IMAGE"] = args.web_image

    if not args.deploy_only:
        if not args.skip_local_build and not args.plan_only:
            run_local_builds(repo_root, services, java_home=args.java_home)
        images.update(build_images(repo_root, services, args, local_megumin=local_megumin))

    for key, value in images.items():
        log(f"{key}={value}")

    if args.build_only:
        log("BUILD_ONLY=1")
        return 0

    if args.plan_only:
        if not local_megumin:
            log(f"HOST={args.host}")
        log(f"REMOTE_DIR={args.remote_dir}")
        log(f"PUBLIC_URL={args.public_url}")
        log(f"LABEL={args.label}")
        return 0

    if not images:
        raise SystemExit("No images were built or provided. Use --services or --agent-image/--web-image.")

    if local_megumin:
        deploy_local(
            remote_dir=args.remote_dir,
            public_url=args.public_url,
            label=args.label,
            agent_image=images.get("AGENT_IMAGE", ""),
            web_image=images.get("WEB_IMAGE", ""),
        )
    else:
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
    parser.add_argument("--tag-prefix", default="deploy", help="Tag prefix for published GHCR images.")
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
    parser.add_argument("--rollback-backup", default="", help="Restore a compose backup on Megumin, then recreate --services.")
    parser.add_argument("--docker-timeout-seconds", type=int, default=180, help="Docker server startup timeout.")
    args = parser.parse_args()
    if args.label is None:
        args.label = args.tag_prefix
    if args.deploy_only and not (args.agent_image or args.web_image):
        parser.error("--deploy-only requires --agent-image or --web-image")
    if args.rollback_backup and (args.agent_image or args.web_image or args.deploy_only or args.build_only):
        parser.error("--rollback-backup cannot be combined with image build/deploy flags")
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
    resolved = resolve_command(command)
    return subprocess.run(
        resolved,
        cwd=str(cwd),
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=True,
    )


def resolve_command(command: list[str]) -> list[str]:
    if not command:
        raise ValueError("command cannot be empty")
    executable = command[0]
    resolved = executable if any(sep in executable for sep in ("/", "\\")) else shutil.which(executable)
    if not resolved:
        resolved = executable
    if os.name == "nt" and Path(resolved).suffix.lower() in {".bat", ".cmd"}:
        return [os.environ.get("ComSpec", "cmd.exe"), "/d", "/c", resolved, *command[1:]]
    return [resolved, *command[1:]]


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
        if java_home and Path(java_home).exists():
            env["JAVA_HOME"] = java_home
            env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
        run(maven_command(repo_root) + ["-pl", "agent-service", "-am", "-DskipTests", "package"], cwd=repo_root, env=env)
    if "web" in services:
        run(web_build_command(repo_root / "web"), cwd=repo_root / "web")


def maven_command(repo_root: Path) -> list[str]:
    if os.name == "nt":
        return [str(repo_root / "mvnw.cmd")]
    return ["bash", str(repo_root / "mvnw")]


def web_build_command(web_root: Path) -> list[str]:
    if (web_root / "pnpm-lock.yaml").exists():
        return ["pnpm", "build"]
    return ["npm", "run", "build"]


def build_images(
    repo_root: Path,
    services: list[str],
    args: argparse.Namespace,
    *,
    local_megumin: bool,
) -> dict[str, str]:
    tag = image_tag(repo_root, args.tag_prefix)
    images: dict[str, str] = {}

    agent_jar = repo_root / "agent-service" / "target" / "agent-service-0.0.1-SNAPSHOT.jar"
    web_dist = repo_root / "web" / "dist"
    web_nginx = repo_root / "web" / "nginx.conf"

    if "agent-service" in services and not agent_jar.exists():
        raise SystemExit(f"Missing {agent_jar}. Run local Maven package before image build.")
    if "web" in services:
        if not web_dist.exists():
            raise SystemExit(f"Missing {web_dist}. Run npm run build in web before image build.")
        if not web_nginx.exists():
            raise SystemExit(f"Missing {web_nginx}.")

    if not args.plan_only:
        ensure_docker(args.docker_timeout_seconds)

    if "agent-service" in services:
        image = (
            f"agent-platform-agent-service:{tag}"
            if local_megumin
            else f"ghcr.io/{args.owner}/agent-platform-agent-service:{tag}"
        )
        images["AGENT_IMAGE"] = image
        if not args.plan_only:
            build_agent_image(agent_jar, image, push=not local_megumin)

    if "web" in services:
        image = (
            f"agent-platform-web:{tag}"
            if local_megumin
            else f"ghcr.io/{args.owner}/agent-platform-web:web-{tag}"
        )
        images["WEB_IMAGE"] = image
        if not args.plan_only:
            build_web_image(web_dist, web_nginx, image, push=not local_megumin)

    return images


def image_tag(repo_root: Path, tag_prefix: str) -> str:
    short_sha = run(["git", "rev-parse", "--short", "HEAD"], cwd=repo_root, capture=True).stdout.strip()
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    return f"{tag_prefix}-{stamp}-{short_sha}"


def ensure_docker(timeout_seconds: int) -> None:
    docker = shutil.which("docker")
    if not docker:
        raise SystemExit("Docker CLI is not available on PATH.")
    if docker_server_available(docker):
        return

    started = start_docker_desktop(docker)
    if not started:
        candidates = "; ".join(str(path) for path in docker_desktop_candidates(docker))
        log(f"DOCKER_DESKTOP_EXE_NOT_FOUND={candidates or 'none'}")

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if docker_server_available(docker):
            return
        time.sleep(3)
    raise SystemExit(f"Docker server is not available after {timeout_seconds} seconds.")


def start_docker_desktop(docker: str) -> Path | None:
    for docker_desktop in docker_desktop_candidates(docker):
        if not docker_desktop.exists():
            continue
        log(f"$ start {docker_desktop}")
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        subprocess.Popen(
            [str(docker_desktop)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creationflags,
        )
        return docker_desktop
    return None


def docker_desktop_candidates(docker: str | None = None) -> list[Path]:
    candidates: list[Path] = []
    seen: set[str] = set()

    def add(path: str | Path | None) -> None:
        if not path:
            return
        candidate = Path(os.path.expandvars(str(path))).expanduser()
        key = os.path.normcase(os.path.abspath(str(candidate)))
        if key in seen:
            return
        seen.add(key)
        candidates.append(candidate)

    add(os.environ.get("DOCKER_DESKTOP_EXE"))

    if docker:
        docker_path = Path(docker)
        if docker_path.parent.name.lower() == "bin" and docker_path.parent.parent.name.lower() == "resources":
            add(docker_path.parent.parent.parent / "Docker Desktop.exe")
        for parent in docker_path.parents:
            add(parent / "Docker Desktop.exe")
            add(parent / "Docker desktop.exe")

    for name in ("Docker Desktop.exe", "Docker desktop.exe"):
        add(shutil.which(name))

    for env_name in ("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)", "LOCALAPPDATA"):
        base = os.environ.get(env_name)
        if not base:
            continue
        add(Path(base) / "Docker" / "Docker" / "Docker Desktop.exe")
        add(Path(base) / "Docker" / "Docker Desktop.exe")

    return candidates


def docker_server_available(docker: str) -> bool:
    command = resolve_command([docker, "version", "--format", "{{.Server.Version}}"])
    try:
        subprocess.run(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
            text=True,
        )
        return True
    except subprocess.CalledProcessError:
        return False


def build_agent_image(jar: Path, image: str, *, push: bool) -> None:
    with tempfile.TemporaryDirectory(prefix="agent-platform-agent-service-") as tmp:
        ctx = Path(tmp)
        shutil.copy2(jar, ctx / "app.jar")
        write_text(
            ctx / "Dockerfile",
            """\
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY app.jar /app/app.jar
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
""",
        )
        run(["docker", "build", "-t", image, str(ctx)], cwd=ctx)
        if push:
            run(["docker", "push", image], cwd=ctx)


def build_web_image(dist: Path, nginx_conf: Path, image: str, *, push: bool) -> None:
    with tempfile.TemporaryDirectory(prefix="agent-platform-web-") as tmp:
        ctx = Path(tmp)
        shutil.copytree(dist, ctx / "dist")
        shutil.copy2(nginx_conf, ctx / "nginx.conf")
        write_text(
            ctx / "Dockerfile",
            """\
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN chmod -R a+rX /usr/share/nginx/html && chmod 0644 /etc/nginx/conf.d/default.conf
EXPOSE 80
""",
        )
        run(["docker", "build", "-t", image, str(ctx)], cwd=ctx)
        if push:
            run(["docker", "push", image], cwd=ctx)


def write_text(path: Path, content: str) -> None:
    path.write_text(content.replace("\r\n", "\n"), encoding="ascii")


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
        pull_images=True,
    )
    encoded = base64.b64encode(remote_script.encode("utf-8")).decode("ascii")
    log(f"$ ssh {host} 'base64 -d | bash'")
    proc = subprocess.run(
        resolve_command(["ssh", host, "base64 -d | bash"]),
        input=encoded,
        text=True,
        encoding="ascii",
        check=True,
    )
    if proc.returncode != 0:
        raise SystemExit(proc.returncode)


def deploy_local(
    *,
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
    pull_images = any(image.startswith("ghcr.io/") for image in (agent_image, web_image) if image)
    script = remote_deploy_script(
        remote_dir=remote_dir,
        public_url=public_url,
        label=label,
        services=" ".join(services),
        agent_image=agent_image,
        web_image=web_image,
        pull_images=pull_images,
    )
    log("$ bash local Megumin deploy script")
    subprocess.run(
        resolve_command(["bash"]),
        input=script,
        text=True,
        encoding="utf-8",
        check=True,
    )


def rollback_remote(
    *,
    host: str,
    remote_dir: str,
    public_url: str,
    backup: str,
    services: list[str],
) -> None:
    remote_script = remote_rollback_script(
        remote_dir=remote_dir,
        public_url=public_url,
        backup=backup,
        services=" ".join(services),
    )
    encoded = base64.b64encode(remote_script.encode("utf-8")).decode("ascii")
    log(f"$ ssh {host} 'base64 -d | bash'")
    proc = subprocess.run(
        resolve_command(["ssh", host, "base64 -d | bash"]),
        input=encoded,
        text=True,
        encoding="ascii",
        check=True,
    )
    if proc.returncode != 0:
        raise SystemExit(proc.returncode)


def rollback_local(
    *,
    remote_dir: str,
    public_url: str,
    backup: str,
    services: list[str],
) -> None:
    script = remote_rollback_script(
        remote_dir=remote_dir,
        public_url=public_url,
        backup=backup,
        services=" ".join(services),
        pull_images=False,
    )
    log("$ bash local Megumin rollback script")
    subprocess.run(
        resolve_command(["bash"]),
        input=script,
        text=True,
        encoding="utf-8",
        check=True,
    )


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
    pull_images: bool,
) -> str:
    pull_command = (
        "docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull $services"
        if pull_images
        else "echo 'SKIP_PULL=1'"
    )
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

{pull_command}
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
    if curl --noproxy '*' -k -fsSI --max-time 15 "$public_url" | sed -n '1,12p'; then
        break
    fi
    if [ "$i" = "20" ]; then
        echo 'public https check failed' >&2
        exit 1
    fi
    sleep 2
done

echo '--- public assets ---'
curl --noproxy '*' -k -fsS --max-time 15 "$public_url" | grep -o 'assets/[^" ]*' | head -n 10 || true
echo 'DEPLOY_OK=1'
"""
    return script.replace("\r\n", "\n")


def remote_rollback_script(
    *,
    remote_dir: str,
    public_url: str,
    backup: str,
    services: str,
    pull_images: bool = True,
) -> str:
    pull_command = (
        "docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull $services"
        if pull_images
        else "echo 'SKIP_PULL=1'"
    )
    script = f"""\
set -euo pipefail
cd {shell_single_quote(remote_dir)}
backup={shell_single_quote(backup)}
services={shell_single_quote(services)}
public_url={shell_single_quote(public_url)}

test -f "$backup"
cp "$backup" docker-compose.ghcr-photo.yml
echo "COMPOSE_RESTORED=$backup"

{pull_command}
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
    if curl --noproxy '*' -k -fsSI --max-time 15 "$public_url" | sed -n '1,12p'; then
        break
    fi
    if [ "$i" = "20" ]; then
        echo 'public https check failed' >&2
        exit 1
    fi
    sleep 2
done

echo '--- public assets ---'
curl --noproxy '*' -k -fsS --max-time 15 "$public_url" | grep -o 'assets/[^" ]*' | head -n 10 || true
echo 'ROLLBACK_OK=1'
"""
    return script.replace("\r\n", "\n")


def is_local_megumin(remote_dir: str) -> bool:
    hostname = run(["hostname"], cwd=Path.cwd(), capture=True).stdout.strip().lower()
    if hostname == "megumin":
        return True
    remote_path = Path(remote_dir)
    return (
        remote_path.exists()
        and (remote_path / "docker-compose.yml").exists()
        and any("agent-platform-" in line for line in docker_container_names())
    )


def docker_container_names() -> list[str]:
    docker = shutil.which("docker")
    if not docker:
        return []
    try:
        result = subprocess.run(
            resolve_command([docker, "ps", "--format", "{{.Names}}"]),
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        return result.stdout.splitlines()
    except subprocess.CalledProcessError:
        return []


if __name__ == "__main__":
    raise SystemExit(main())
