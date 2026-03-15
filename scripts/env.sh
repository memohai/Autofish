# Source this file to set MCP connection variables.
# Usage: source scripts/env.sh

export MCP_HOST="${MCP_HOST:-localhost}"
export MCP_PORT="${MCP_PORT:-8080}"
export MCP_TOKEN="${MCP_TOKEN:-}"
export MCP_SESSION="${MCP_SESSION:-}"
export MCP_BASE="http://${MCP_HOST}:${MCP_PORT}"
