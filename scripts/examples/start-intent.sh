#!/usr/bin/env bash
# Start an activity via intent.  Usage: ./start-intent.sh <action> <data_uri>
# Example: ./start-intent.sh android.intent.action.VIEW https://www.bing.com
set -euo pipefail
source "$(dirname "$0")/../env.sh"

ACTION="${1:?Usage: $0 <action> <data_uri>}"
DATA="${2:?Usage: $0 <action> <data_uri>}"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d "{
        \"jsonrpc\": \"2.0\",
        \"id\": 15,
        \"method\": \"tools/call\",
        \"params\": {
            \"name\": \"amctl_start_intent\",
            \"arguments\": { \"action\": \"${ACTION}\", \"data\": \"${DATA}\" }
        }
    }" | python3 -m json.tool
