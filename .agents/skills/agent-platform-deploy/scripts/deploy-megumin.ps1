[CmdletBinding(PositionalBinding=$false)]
param(
    [string]$AgentImage,
    [string]$WebImage,
    [string]$HostName = 'root@192.168.0.109',
    [string]$RemoteDir = '/opt/agent-platform',
    [string]$PublicUrl = 'https://agent.rainaki.top:8443/chat',
    [string]$Label = 'deploy',
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'

$services = @()
if ($AgentImage) { $services += 'agent-service' }
if ($WebImage) { $services += 'web' }
if ($services.Count -eq 0) {
    throw 'Provide at least one of -AgentImage or -WebImage.'
}

$serviceList = $services -join ' '
if ($PlanOnly) {
    "PLAN_ONLY=1"
    "HOST=$HostName"
    "REMOTE_DIR=$RemoteDir"
    "SERVICES=$serviceList"
    if ($AgentImage) { "AGENT_IMAGE=$AgentImage" }
    if ($WebImage) { "WEB_IMAGE=$WebImage" }
    "PUBLIC_URL=$PublicUrl"
    return
}

$remote = @'
set -euo pipefail
cd __REMOTE_DIR__
agent_image='__AGENT_IMAGE__'
web_image='__WEB_IMAGE__'
label='__LABEL__'
services='__SERVICES__'
public_url='__PUBLIC_URL__'
bak="docker-compose.ghcr-photo.yml.bak-${label}-$(date +%Y%m%d-%H%M%S)"

cp docker-compose.ghcr-photo.yml "$bak"
python3 - <<'PY'
from pathlib import Path

p = Path('docker-compose.ghcr-photo.yml')
agent_image = '__AGENT_IMAGE__'
web_image = '__WEB_IMAGE__'
targets = {}
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
        out.append(f'{indent}image: {targets[current]}')
    else:
        out.append(line)
p.write_text('\n'.join(out) + '\n')
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

if printf '%s\n' "$services" | grep -qw 'agent-service'; then
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
curl -fsSI http://localhost:8480/chat | sed -n '1,12p'

echo '--- public https ---'
curl -k -fsSI "$public_url" | sed -n '1,12p'

echo '--- public assets ---'
curl -k -fsS "$public_url" | grep -o 'assets/[^" ]*' | head -n 10 || true
echo 'DEPLOY_OK=1'
'@

$remote = $remote.Replace('__REMOTE_DIR__', $RemoteDir)
$remote = $remote.Replace('__AGENT_IMAGE__', $AgentImage)
$remote = $remote.Replace('__WEB_IMAGE__', $WebImage)
$remote = $remote.Replace('__LABEL__', $Label)
$remote = $remote.Replace('__SERVICES__', $serviceList)
$remote = $remote.Replace('__PUBLIC_URL__', $PublicUrl)

$remote = $remote -replace "`r`n", "`n"
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
ssh $HostName "echo $b64 | base64 -d | bash"
