#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_COMPOSE_DIR="$(cd "${SCRIPT_DIR}/../opensim-ai-docker" 2>/dev/null && pwd || true)"

IMAGE="opensim-spawner:local"
SERVICE="spawner"
COMPOSE_DIR="${DEFAULT_COMPOSE_DIR}"
SKIP_BUILD=0
SKIP_HEALTH=0
DRY_RUN=0
HEALTH_TIMEOUT_SECS=90
HEALTH_POLL_SECS=2

usage() {
  cat <<'EOF'
Usage: redeploy-local-image.sh [options]

Build the local opensim-spawner Docker image and force-recreate the spawner
service in the opensim-ai-docker compose stack.

Options:
  --image <tag>            Image tag to build/redeploy (default: opensim-spawner:local)
  --service <name>         Compose service name (default: spawner)
  --compose-dir <path>     Path to compose project (default: ../opensim-ai-docker)
  --skip-build             Do not run docker build
  --skip-health            Skip post-deploy health wait/check
  --health-timeout <secs>  Max wait for container health (default: 90)
  --dry-run                Print actions without executing them
  -h, --help               Show this help

Examples:
  ./redeploy-local-image.sh
  ./redeploy-local-image.sh --skip-build
  ./redeploy-local-image.sh --image opensim-spawner:dev --health-timeout 180
EOF
}

log() {
  printf '[redeploy] %s\n' "$*"
}

run_cmd() {
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    printf '[dry-run]'
    printf ' %q' "$@"
    printf '\n'
    return 0
  fi
  "$@"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Error: required command not found: %s\n' "$1" >&2
    exit 1
  fi
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --image)
        IMAGE="${2:-}"
        shift 2
        ;;
      --service)
        SERVICE="${2:-}"
        shift 2
        ;;
      --compose-dir)
        COMPOSE_DIR="${2:-}"
        shift 2
        ;;
      --skip-build)
        SKIP_BUILD=1
        shift
        ;;
      --skip-health)
        SKIP_HEALTH=1
        shift
        ;;
      --health-timeout)
        HEALTH_TIMEOUT_SECS="${2:-}"
        shift 2
        ;;
      --dry-run)
        DRY_RUN=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        printf 'Error: unknown argument: %s\n\n' "$1" >&2
        usage >&2
        exit 1
        ;;
    esac
  done
}

validate_args() {
  if [[ -z "${IMAGE}" ]]; then
    printf 'Error: --image cannot be empty.\n' >&2
    exit 1
  fi
  if [[ -z "${SERVICE}" ]]; then
    printf 'Error: --service cannot be empty.\n' >&2
    exit 1
  fi
  if [[ -z "${COMPOSE_DIR}" || ! -d "${COMPOSE_DIR}" ]]; then
    printf 'Error: compose directory not found: %s\n' "${COMPOSE_DIR}" >&2
    exit 1
  fi
  if [[ ! "${HEALTH_TIMEOUT_SECS}" =~ ^[0-9]+$ || "${HEALTH_TIMEOUT_SECS}" -lt 1 ]]; then
    printf 'Error: --health-timeout must be a positive integer.\n' >&2
    exit 1
  fi
}

wait_for_health() {
  local cid="$1"
  local timeout_secs="$2"
  local deadline=$(( $(date +%s) + timeout_secs ))

  while true; do
    local now
    now="$(date +%s)"
    if [[ "${now}" -ge "${deadline}" ]]; then
      printf 'Error: timed out waiting for container health (%ss).\n' "${timeout_secs}" >&2
      return 1
    fi

    local state
    state="$(docker inspect "${cid}" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')"

    case "${state}" in
      healthy)
        log "Container is healthy."
        return 0
        ;;
      unhealthy)
        printf 'Error: container reported unhealthy.\n' >&2
        docker inspect "${cid}" --format 'Container={{.Name}} Started={{.State.StartedAt}} Health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' >&2 || true
        return 1
        ;;
      none)
        log "Container has no healthcheck; continuing."
        return 0
        ;;
      *)
        sleep "${HEALTH_POLL_SECS}"
        ;;
    esac
  done
}

main() {
  parse_args "$@"
  require_cmd docker
  validate_args

  log "Project: ${SCRIPT_DIR}"
  log "Workspace: ${WORKSPACE_DIR}"
  log "Compose: ${COMPOSE_DIR}"
  log "Image: ${IMAGE}"
  log "Service: ${SERVICE}"

  if [[ "${SKIP_BUILD}" -eq 0 ]]; then
    log "Building local image..."
    run_cmd docker build -f "${SCRIPT_DIR}/Dockerfile" -t "${IMAGE}" "${WORKSPACE_DIR}"
  else
    log "Skipping build (--skip-build)."
  fi

  log "Recreating compose service..."
  run_cmd env OPENSIM_SPAWNER_IMAGE="${IMAGE}" docker compose -f "${COMPOSE_DIR}/docker-compose.yml" -f "${COMPOSE_DIR}/docker-compose.local.yml" up -d --no-deps --force-recreate "${SERVICE}"

  local cid
  cid="$(docker compose -f "${COMPOSE_DIR}/docker-compose.yml" -f "${COMPOSE_DIR}/docker-compose.local.yml" ps -q "${SERVICE}" | head -n1)"
  if [[ -z "${cid}" ]]; then
    printf 'Error: could not resolve container id for service %s.\n' "${SERVICE}" >&2
    exit 1
  fi

  log "Container id: ${cid}"
  docker ps --filter "id=${cid}" --format 'CONTAINER={{.Names}} IMAGE={{.Image}} STATUS={{.Status}}'

  if [[ "${SKIP_HEALTH}" -eq 0 ]]; then
    log "Waiting for container health..."
    wait_for_health "${cid}" "${HEALTH_TIMEOUT_SECS}"
  else
    log "Skipping health wait/check (--skip-health)."
  fi

  log "Redeploy complete."
}

main "$@"
