#!/usr/bin/env bash
# Call any MCP tool by name.
# Usage: ./04-call-tool.sh <tool_name> [json_args]
# Example: ./04-call-tool.sh amctl_tap '{"x":540,"y":1200}'
# Requires: MCP_TOKEN, MCP_SESSION
set -euo pipefail
source "$(dirname "$0")/env.sh"

TOOL="${1:-}"
ARGS="${2:-"{}"}"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1
[[ -z "$TOOL" ]]        && echo "Usage: $0 <tool_name> [json_args]" >&2 && exit 1

PAYLOAD=$(python3 -c '
import json, sys
payload = {
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
        "name": sys.argv[1],
        "arguments": json.loads(sys.argv[2])
    }
}
print(json.dumps(payload))
' "$TOOL" "$ARGS")

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d "$PAYLOAD" | python3 -m json.tool
