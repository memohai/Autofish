#!/usr/bin/env bash
# List installed packages.  Usage: ./list-packages.sh [filter]
set -euo pipefail
source "$(dirname "$0")/../env.sh"

FILTER="${1:-}"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1

if [[ -n "$FILTER" ]]; then
    ARGS="{ \"filter\": \"${FILTER}\" }"
else
    ARGS="{}"
fi

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d "{
        \"jsonrpc\": \"2.0\",
        \"id\": 13,
        \"method\": \"tools/call\",
        \"params\": { \"name\": \"amctl_list_packages\", \"arguments\": ${ARGS} }
    }" | python3 -m json.tool
