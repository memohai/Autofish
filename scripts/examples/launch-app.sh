#!/usr/bin/env bash
# Launch app by package name.  Usage: ./launch-app.sh <package>
set -euo pipefail
source "$(dirname "$0")/../env.sh"

PKG="${1:?Usage: $0 <package_name>}"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d "{
        \"jsonrpc\": \"2.0\",
        \"id\": 12,
        \"method\": \"tools/call\",
        \"params\": { \"name\": \"amctl_launch_app\", \"arguments\": { \"package_name\": \"${PKG}\" } }
    }" | python3 -m json.tool
