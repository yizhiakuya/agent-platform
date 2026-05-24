#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WEB_DIR="${REPO_ROOT}/web"
PUBLISH_ROOT="${WEB_STATIC_ROOT:-/opt/agent-platform/web-dist}"
RELEASES_DIR="${PUBLISH_ROOT}/releases"
CURRENT_LINK="${PUBLISH_ROOT}/current"
KEEP_RELEASES="${WEB_STATIC_KEEP_RELEASES:-10}"
RUN_BUILD=1
PRUNE_OLD=1

usage() {
  cat <<'EOF'
Usage: scripts/deploy-web-static.sh [--skip-build] [--no-prune]

Builds web/dist and publishes it to:
  WEB_STATIC_ROOT/releases/<timestamp>-<git-sha>
Then atomically updates:
  WEB_STATIC_ROOT/current

Environment:
  WEB_STATIC_ROOT=/opt/agent-platform/web-dist
  WEB_STATIC_KEEP_RELEASES=10
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)
      RUN_BUILD=0
      ;;
    --no-prune)
      PRUNE_OLD=0
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "${RUN_BUILD}" == "1" ]]; then
  (cd "${WEB_DIR}" && pnpm build)
fi

if [[ ! -f "${WEB_DIR}/dist/index.html" ]]; then
  echo "Missing ${WEB_DIR}/dist/index.html; run without --skip-build first." >&2
  exit 1
fi

if [[ -e "${CURRENT_LINK}" && ! -L "${CURRENT_LINK}" ]]; then
  echo "${CURRENT_LINK} exists and is not a symlink; refusing to replace it." >&2
  exit 1
fi

SHORT_SHA="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo no-git)"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
RELEASE_DIR="${RELEASES_DIR}/${STAMP}-${SHORT_SHA}"

mkdir -p "${RELEASE_DIR}"
rsync -a --delete "${WEB_DIR}/dist/" "${RELEASE_DIR}/"
chmod a+rx "${PUBLISH_ROOT}" "${RELEASES_DIR}"
chmod -R a+rX "${RELEASE_DIR}"

ln -sfn "releases/$(basename "${RELEASE_DIR}")" "${CURRENT_LINK}.next"
mv -Tf "${CURRENT_LINK}.next" "${CURRENT_LINK}"

if [[ "${PRUNE_OLD}" == "1" && "${KEEP_RELEASES}" =~ ^[0-9]+$ && "${KEEP_RELEASES}" -gt 0 ]]; then
  find "${RELEASES_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' \
    | sort -nr \
    | tail -n +"$((KEEP_RELEASES + 1))" \
    | cut -d' ' -f2- \
    | xargs -r rm -rf
fi

echo "WEB_STATIC_RELEASE=${RELEASE_DIR}"
echo "WEB_STATIC_CURRENT=${CURRENT_LINK}"
find "${CURRENT_LINK}/assets" -maxdepth 1 -type f -printf '%f\n' | sort
