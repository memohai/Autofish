#!/usr/bin/env bash
# Health check — no auth required
set -euo pipefail
source "$(dirname "$0")/env.sh"

curl -s "${MCP_BASE}/health" | python3 -m json.tool
