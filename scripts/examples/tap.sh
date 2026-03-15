#!/usr/bin/env bash
# Tap at coordinates.  Usage: ./tap.sh <x> <y>
set -euo pipefail
source "$(dirname "$0")/../env.sh"

X="${1:?Usage: $0 <x> <y>}"
Y="${2:?Usage: $0 <x> <y>}"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d "{
        \"jsonrpc\": \"2.0\",
        \"id\": 11,
        \"method\": \"tools/call\",
        \"params\": { \"name\": \"amctl_tap\", \"arguments\": { \"x\": ${X}, \"y\": ${Y} } }
    }" | python3 -m json.tool
