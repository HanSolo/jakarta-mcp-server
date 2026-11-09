#!/usr/bin/env bash
# Walks through the Tasks extension against a running mcp-example-tasks (default http://localhost:8080/mcp):
#   ./demo.sh          start the task, poll it, answer its question, print the result
#   ./demo.sh listen   the same, but follow the task with subscriptions/listen instead of polling
# The server must run with MCP_TASKS_ENABLED=true. Needs curl only.
set -euo pipefail

URL="${MCP_URL:-http://localhost:8080/mcp}"
VERSION="2026-07-28"
EXT='"extensions":{"io.modelcontextprotocol/tasks":{}}'
META="\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$VERSION\",\"io.modelcontextprotocol/clientCapabilities\":{$EXT}}"

# rpc METHOD NAME PARAMS-WITHOUT-BRACES  ->  response body
rpc() {
    local method="$1" name="$2" params="$3"
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H "MCP-Protocol-Version: $VERSION" -H "Mcp-Method: $method")
    if [ -n "$name" ]; then headers+=(-H "Mcp-Name: $name"); fi
    curl -s "$URL" "${headers[@]}" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":{$params,$META}}"
}

field() { grep -oE "\"$1\" *: *\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

echo "== tools/call build_report (2 sections)"
created=$(rpc tools/call build_report '"name":"build_report","arguments":{"topic":"quarterly sales","sections":2}')
echo "$created"
id=$(printf '%s' "$created" | field taskId)
if [ -z "$id" ]; then
    echo "No task came back: is the server running with MCP_TASKS_ENABLED=true?" >&2
    exit 1
fi

answer() {
    echo "== tasks/update: publish = true"
    rpc tasks/update "$id" "\"taskId\":\"$id\",\"inputResponses\":{\"publish\":{\"action\":\"accept\",\"content\":{\"publish\":true}}}"
    echo
}

if [ "${1:-}" = "listen" ]; then
    echo "== subscriptions/listen for $id (ends when the task is finished)"
    fifo="$(mktemp -u)"
    mkfifo "$fifo"
    curl -sN --max-time 300 "$URL" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H "MCP-Protocol-Version: $VERSION" -H 'Mcp-Method: subscriptions/listen' \
         -d "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"subscriptions/listen\",\"params\":{\"notifications\":{\"taskIds\":[\"$id\"]},$META}}" > "$fifo" &
    curl_pid=$!
    trap 'kill "$curl_pid" 2>/dev/null || true; rm -f "$fifo"' EXIT
    answered=0
    while IFS= read -r line; do
        case "$line" in
            data:*)
                echo "$line"
                case "$line" in
                    *'"status"'*'"input_required"'*) if [ "$answered" = 0 ]; then answered=1; answer; fi ;;
                    *'"status"'*'"completed"'*|*'"status"'*'"failed"'*|*'"status"'*'"cancelled"'*) exit 0 ;;
                esac ;;
        esac
    done < "$fifo"
    exit 0
fi

echo "== polling tasks/get"
answered=0
while true; do
    state=$(rpc tasks/get "$id" "\"taskId\":\"$id\"")
    status=$(printf '%s' "$state" | field status)
    echo "status=$status $(printf '%s' "$state" | field statusMessage)"
    case "$status" in
        input_required) if [ "$answered" = 0 ]; then answered=1; answer; fi ;;
        completed|failed|cancelled) echo "$state"; exit 0 ;;
    esac
    sleep 1
done
