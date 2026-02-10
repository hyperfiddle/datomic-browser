#!/usr/bin/env bash

# Downloads Datomic Pro + mbrainz-small, starts DynamoDB Local, and loads mbrainz into DDB storage.
# After this, use ./run_datomic_ddb.sh to start the transactor.

set -uo pipefail

export AWS_ACCESS_KEY_ID=dummy
export AWS_SECRET_ACCESS_KEY=dummy
export AWS_DEFAULT_REGION=us-east-1

DDB_PORT=8000
DDB_TABLE=datomic

function fail { echo "$@"; exit 1; }
function info { echo "[INFO] $(date +"%T.%3N") $*"; }
function downloadAsNeeded { curl "$1" -O -C - || fail "Failed to download $1"; }

# --- Prerequisites ---

docker info >/dev/null 2>&1 || fail "Docker is not running."
command -v aws >/dev/null 2>&1 || fail "AWS CLI not found. Install it with: brew install awscli"

# --- Download Datomic Pro + mbrainz (same as datomic_fixtures_mbrainz_small.sh) ---

mkdir -p state
pushd state

info "Downloading datomic-pro"
downloadAsNeeded https://datomic-pro-downloads.s3.amazonaws.com/1.0.7469/datomic-pro-1.0.7469.zip
info "Extracting datomic-pro"
if [ ! -d "datomic-pro" ]; then
    unzip -q datomic-pro-1.0.7469.zip || fail "Failed to unzip datomic-pro"
    mv datomic-pro-1.0.7469 datomic-pro
fi

info "Downloading mbrainz dataset"
mbrainz=https://s3.amazonaws.com/mbrainz/datomic-mbrainz-1968-1973-backup-2017-07-20.tar
mbrainz_backup_dir=datomic-mbrainz-1968-1973-backup-2017-07-20
downloadAsNeeded "$mbrainz"
info "Extracting mbrainz dataset"
if [ ! -d "$mbrainz_backup_dir" ]; then
    tar -xf "${mbrainz##*/}" || fail "failed to untar mbrainz dataset"
fi

popd

# --- DynamoDB Local ---

if docker ps --format '{{.Names}}' | grep -q '^dynamodb-local$'; then
    info "DynamoDB Local already running"
else
    docker rm -f dynamodb-local 2>/dev/null || true
    info "Starting DynamoDB Local on port $DDB_PORT"
    docker run -d --name dynamodb-local -p ${DDB_PORT}:8000 amazon/dynamodb-local >/dev/null
fi

info "Waiting for DynamoDB Local..."
while ! nc -z localhost $DDB_PORT 2>/dev/null; do sleep 0.2; done

# --- Create DDB table (idempotent) ---

if aws dynamodb describe-table --table-name $DDB_TABLE --endpoint-url http://localhost:${DDB_PORT} >/dev/null 2>&1; then
    info "DynamoDB table '$DDB_TABLE' already exists"
else
    info "Creating DynamoDB table '$DDB_TABLE'"
    aws dynamodb create-table \
        --table-name $DDB_TABLE \
        --attribute-definitions AttributeName=id,AttributeType=S \
        --key-schema AttributeName=id,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:${DDB_PORT} >/dev/null
fi

# --- Import mbrainz into DDB storage via a short-lived transactor ---

info "Importing the mbrainz dataset into DDB storage"
while PORT=$((RANDOM % 60000 + 5000)); lsof -i:$PORT >/dev/null 2>&1; do :; done

cat > state/datomic-pro/config/ddb-local-transactor.properties <<EOF
protocol=ddb-local
host=localhost
port=$PORT
alt-host=localhost

aws-dynamodb-table=${DDB_TABLE}
aws-dynamodb-override-endpoint=localhost:${DDB_PORT}

memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF

./state/datomic-pro/bin/transactor config/ddb-local-transactor.properties >>state/ddb-transactor.log 2>&1 &
datomic_transactor_pid=$!

info "Waiting for Datomic to start on port $PORT..."
while ! nc -z localhost $PORT 2>/dev/null; do sleep 0.5; done

state/datomic-pro/bin/datomic restore-db \
    "file:$(pwd)/state/mbrainz-1968-1973 datomic:ddb-local://localhost:${DDB_PORT}/${DDB_TABLE}/mbrainz-1968-1973"
kill $datomic_transactor_pid

info "Cleaning up mbrainz backup dir"
rm -rf "state/${mbrainz_backup_dir}"

info "Sample dataset ready (DDB). Run ./run_datomic_ddb.sh to start the transactor."
