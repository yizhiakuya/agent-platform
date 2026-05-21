    [CmdletBinding(PositionalBinding=$false)]
param(
    [string[]]$Services = @('agent-service', 'web'),
    [string]$TagPrefix = 'deploy',
    [string]$RepoRoot,
    [string]$Owner = 'yizhiakuya',
    [int]$DockerTimeoutSeconds = 120,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) {
    $scriptPath = $PSCommandPath
    if (-not $scriptPath) {
        $scriptPath = $MyInvocation.MyCommand.Path
    }
    if (-not $scriptPath) {
        throw 'Cannot resolve script path; pass -RepoRoot explicitly.'
    }
    $RepoRoot = (Resolve-Path (Join-Path (Split-Path -Parent $scriptPath) '..\..\..\..')).Path
}

function Resolve-Services {
    param([string[]]$InputServices)

    $allowed = @('agent-service', 'web')
    $resolved = @()
    foreach ($entry in $InputServices) {
        $entry -split ',' | ForEach-Object {
            $service = $_.Trim()
            if ($service) {
                if ($allowed -notcontains $service) {
                    throw "Unsupported service '$service'. Allowed services: $($allowed -join ', ')."
                }
                if ($resolved -notcontains $service) {
                    $resolved += $service
                }
            }
        }
    }
    if ($resolved.Count -eq 0) {
        throw 'At least one service is required.'
    }
    return $resolved
}

function Wait-Docker {
    param([int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            docker version --format '{{.Server.Version}}' *> $null
            return
        } catch {
            Start-Sleep -Seconds 3
        }
    }
    throw "Docker server is not available after $TimeoutSeconds seconds."
}

function Ensure-Docker {
    try {
        docker version --format '{{.Server.Version}}' *> $null
        return
    } catch {
        $dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
        if (Test-Path -LiteralPath $dockerDesktop) {
            Start-Process -FilePath $dockerDesktop -WindowStyle Hidden | Out-Null
        }
        Wait-Docker -TimeoutSeconds $DockerTimeoutSeconds
    }
}

function New-CleanDirectory {
    param([string]$Path)

    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path | Out-Null
}

Push-Location $RepoRoot
try {
    $Services = Resolve-Services -InputServices $Services
    if (-not $PlanOnly) {
        Ensure-Docker
    }

    $short = (git rev-parse --short HEAD).Trim()
    $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
    $tag = "$TagPrefix-$stamp-$short"
    $outputs = [ordered]@{}

    if ($Services -contains 'agent-service') {
        $jar = Join-Path $RepoRoot 'agent-service\target\agent-service-0.0.1-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) {
            throw "Missing $jar. Run local Maven package before image build."
        }
        if (-not $PlanOnly) {
            $ctx = Join-Path $env:TEMP "agent-platform-agent-service-$tag"
            New-CleanDirectory -Path $ctx
            Copy-Item -LiteralPath $jar -Destination (Join-Path $ctx 'app.jar')
            @'
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY app.jar /app/app.jar
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
'@ | Set-Content -LiteralPath (Join-Path $ctx 'Dockerfile') -Encoding ASCII
        }

        $image = "ghcr.io/$Owner/agent-platform-agent-service:$tag"
        $outputs.AGENT_IMAGE = $image
        if (-not $PlanOnly) {
            docker build -t $image $ctx
            docker push $image
        }
    }

    if ($Services -contains 'web') {
        $dist = Join-Path $RepoRoot 'web\dist'
        $nginx = Join-Path $RepoRoot 'web\nginx.conf'
        if (-not (Test-Path -LiteralPath $dist)) {
            throw "Missing $dist. Run npm run build in web before image build."
        }
        if (-not (Test-Path -LiteralPath $nginx)) {
            throw "Missing $nginx."
        }
        if (-not $PlanOnly) {
            $ctx = Join-Path $env:TEMP "agent-platform-web-$tag"
            New-CleanDirectory -Path $ctx
            Copy-Item -LiteralPath $dist -Destination (Join-Path $ctx 'dist') -Recurse
            Copy-Item -LiteralPath $nginx -Destination (Join-Path $ctx 'nginx.conf')
            @'
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
'@ | Set-Content -LiteralPath (Join-Path $ctx 'Dockerfile') -Encoding ASCII
        }

        $image = "ghcr.io/$Owner/agent-platform-web:web-$tag"
        $outputs.WEB_IMAGE = $image
        if (-not $PlanOnly) {
            docker build -t $image $ctx
            docker push $image
        }
    }

    foreach ($key in $outputs.Keys) {
        "$key=$($outputs[$key])"
    }
    if ($PlanOnly) {
        'PLAN_ONLY=1'
    }
} finally {
    Pop-Location
}
