#!/usr/bin/env bash

# Starts DynamoDB Local (Docker) + Datomic ddb-local transactor.
# Run ./datomic_fixtures_mbrainz_small_ddb.sh first to load sample data.
#
# Then start datomic-browser with:
#   AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy clj -X:dev:ddb dev/-main :datomic-uri '"'datomic:ddb-local://localhost:8000/datomic/*'"'
#
# Cleanup:
#   docker rm -f dynamodb-local
#   lsof -ti:4335 | xargs kill  # kill the transactor process on port 4335

set -euo pipefail

export AWS_ACCESS_KEY_ID=dummy
export AWS_SECRET_ACCESS_KEY=dummy
export AWS_DEFAULT_REGION=us-east-1

DDB_PORT=8000
TRANSACTOR_PORT=4335
DDB_TABLE=datomic

function fail { echo "ERROR: $*" >&2; exit 1; }

# --- Preflight checks ---

[ -d "state/datomic-pro" ] || fail "state/datomic-pro not found. Run ./datomic_fixtures_mbrainz_small.sh first."
docker info >/dev/null 2>&1 || fail "Docker is not running."
nc -z localhost $TRANSACTOR_PORT 2>/dev/null && fail "Port $TRANSACTOR_PORT already in use."

# --- DynamoDB Local ---

if docker ps --format '{{.Names}}' | grep -q '^dynamodb-local$'; then
    echo "DynamoDB Local already running"
else
    docker rm -f dynamodb-local 2>/dev/null || true
    echo "Starting DynamoDB Local on port $DDB_PORT"
    docker run -d --name dynamodb-local -p ${DDB_PORT}:8000 amazon/dynamodb-local >/dev/null
fi

while ! nc -z localhost $DDB_PORT 2>/dev/null; do sleep 0.2; done

# --- Create DDB table (idempotent) ---

if ! aws dynamodb describe-table --table-name $DDB_TABLE --endpoint-url http://localhost:${DDB_PORT} >/dev/null 2>&1; then
    aws dynamodb create-table \
        --table-name $DDB_TABLE \
        --attribute-definitions AttributeName=id,AttributeType=S \
        --key-schema AttributeName=id,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:${DDB_PORT} >/dev/null
fi

# --- Transactor config (written inline, gitignored via state/) ---

cat > state/datomic-pro/config/ddb-local-transactor.properties <<EOF
protocol=ddb-local
host=localhost
port=${TRANSACTOR_PORT}
alt-host=localhost

aws-dynamodb-table=${DDB_TABLE}
aws-dynamodb-override-endpoint=localhost:${DDB_PORT}

memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF

# --- Start transactor ---

./state/datomic-pro/bin/transactor config/ddb-local-transactor.properties >>state/ddb-transactor.log 2>&1 &

sleep 1
echo "Datomic (ddb-local) is starting in background. You can proceed."
