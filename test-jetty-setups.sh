#!/usr/bin/env bash
set -e

# Smoke test: start the datomic browser, verify it responds.
#
# Usage:
#   ./test-jetty-setups.sh [OPTIONS] [-- EXTRA_DEPS_ALIASES...]
#
# Options:
#   --no-browser    Skip opening browser
#   --help          Show this help
#
# Examples:
#   ./test-jetty-setups.sh                      # Run with default aliases
#   ./test-jetty-setups.sh -- :private          # Add :private alias
#   ./test-jetty-setups.sh --no-browser         # Automated mode

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OPEN_BROWSER=true
EXTRA_ALIASES=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --no-browser)
            OPEN_BROWSER=false
            shift
            ;;
        --help)
            head -15 "$0" | tail -n +2 | sed 's/^# //' | sed 's/^#//'
            exit 0
            ;;
        --)
            shift
            EXTRA_ALIASES="$*"
            break
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage"
            exit 1
            ;;
    esac
done

PORT=8080
SERVER_PID=""

cleanup() {
    echo ""
    echo "Cleaning up..."
    if [[ -n "$SERVER_PID" ]]; then
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    echo "Done."
}
trap cleanup EXIT

# Build aliases string
BASE_ALIASES="dev"
if [[ -n "$EXTRA_ALIASES" ]]; then
    for alias in $EXTRA_ALIASES; do
        alias="${alias#:}"
        BASE_ALIASES="${BASE_ALIASES}:${alias}"
    done
fi

echo "========================================"
echo "Datomic Browser Smoke Test"
echo "========================================"
echo "Aliases: $BASE_ALIASES"
echo ""

echo "Starting server with: clj -A:$BASE_ALIASES -M -m datomic-browser.main http-port $PORT"
clj -A:$BASE_ALIASES -M -m datomic-browser.main http-port "$PORT" &
SERVER_PID=$!

echo "Waiting for server on port $PORT..."
for i in $(seq 1 60); do
    if curl -s -o /dev/null http://localhost:$PORT 2>/dev/null; then
        echo "Server is up!"
        break
    fi
    sleep 1
    if (( i % 10 == 0 )); then
        echo "  Still waiting... (${i}s)"
    fi
    if [[ $i -eq 60 ]]; then
        echo "FAIL: Server did not start within 60s"
        exit 1
    fi
done

# Check that agent status endpoint responds
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/api/agents)
if [[ "$HTTP_CODE" == "200" ]]; then
    echo "Agent status endpoint: PASS"
else
    echo "Agent status endpoint: FAIL (HTTP $HTTP_CODE)"
    exit 1
fi

if $OPEN_BROWSER; then
    echo ""
    echo "Opening browser: http://datomic.localhost:$PORT"
    open "http://datomic.localhost:$PORT" 2>/dev/null || xdg-open "http://datomic.localhost:$PORT" 2>/dev/null || true
    echo ""
    echo "Verify the Datomic browser loads, then press Enter to finish..."
    read -r
fi

echo ""
echo "TEST PASSED"
