#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export QUANT_MODE=debug-wan
exec "$SCRIPT_DIR/start.sh" "$@"
