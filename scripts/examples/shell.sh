#!/usr/bin/env bash
# Run arbitrary shell command on device.  Usage: ./shell.sh <command>
set -euo pipefail
source "$(dirname "$0")/../env.sh"

CMD="${1:?Usage: $0 '<shell command>'}"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1

ESCAPED_CMD=$(python3 -c "import json,sys; print(json.dumps(sys.argv[1]))" "$CMD")

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d "{
        \"jsonrpc\": \"2.0\",
        \"id\": 14,
        \"method\": \"tools/call\",
        \"params\": { \"name\": \"amctl_shell\", \"arguments\": { \"command\": ${ESCAPED_CMD} } }
    }" | python3 -m json.tool
