#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRIVATE_DIR="${ROOT_DIR}/private"
FORCE="${FORCE_PRIVATE_LINKS:-false}"

if [[ ! -d "${PRIVATE_DIR}" ]]; then
  cat >&2 <<EOF
Missing private submodule directory: ${PRIVATE_DIR}

Create or initialize it first, for example:
  git submodule add <private-repo-url> private
  git submodule update --init --recursive
EOF
  exit 1
fi

link_path() {
  local source="$1"
  local target="$2"

  if [[ ! -e "${source}" ]]; then
    echo "skip: missing ${source#${ROOT_DIR}/}"
    return 0
  fi

  mkdir -p "$(dirname "${target}")"

  if [[ -L "${target}" ]]; then
    rm "${target}"
  elif [[ -e "${target}" ]]; then
    if [[ "${FORCE}" == "true" ]]; then
      rm -rf "${target}"
    else
      echo "keep: ${target#${ROOT_DIR}/} already exists (set FORCE_PRIVATE_LINKS=true to replace)"
      return 0
    fi
  fi

  ln -s "${source}" "${target}"
  echo "link: ${target#${ROOT_DIR}/} -> ${source#${ROOT_DIR}/}"
}

link_path "${PRIVATE_DIR}/config.yaml" "${ROOT_DIR}/config.yaml"
link_path "${PRIVATE_DIR}/config.yaml1" "${ROOT_DIR}/config.yaml1"
link_path "${PRIVATE_DIR}/.env.model" "${ROOT_DIR}/.env.model"
link_path "${PRIVATE_DIR}/claude/settings.local.json" "${ROOT_DIR}/.claude/settings.local.json"
link_path "${PRIVATE_DIR}/claude-skills" "${ROOT_DIR}/.claude/skills"
link_path "${PRIVATE_DIR}/agent-analysis-skills" "${ROOT_DIR}/agent/analysis-skills"
link_path "${PRIVATE_DIR}/android/release.keystore" "${ROOT_DIR}/compose-app/release.keystore"
link_path "${PRIVATE_DIR}/android/keystore.properties" "${ROOT_DIR}/compose-app/keystore.properties"
link_path "${PRIVATE_DIR}/plans" "${ROOT_DIR}/plans"
link_path "${PRIVATE_DIR}/temp" "${ROOT_DIR}/temp"
