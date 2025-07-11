#!/usr/bin/env bash
set -uo pipefail

function fail {
    echo "$@"
    exit 1
}

function info {
    echo "[INFO] $(date +"%T.%3N") $*"
}

function downloadAsNeeded {
    curl "$1" -O -C - || fail "Failed to download $1"
}

function timeout() { perl -e 'alarm shift; exec @ARGV' "$@"; }

mkdir -p state
pushd state

info "Downloading datomic-pro"
downloadAsNeeded https://datomic-pro-downloads.s3.amazonaws.com/1.0.6735/datomic-pro-1.0.6735.zip
info "Extracting datomic-pro"
if [ ! -d "datomic-pro" ]; then
    unzip -q datomic-pro-1.0.6735.zip || fail "Failed to unzip datomic-pro"
    mv datomic-pro-1.0.6735 datomic-pro
fi

info "Downloading mbrainz dataset"
mbrainz=https://s3.amazonaws.com/mbrainz/datomic-mbrainz-backup-20130611.tar
mbrainz_backup_dir=datomic-mbrainz-backup-20130611
downloadAsNeeded "$mbrainz"
info "Extracting mbrainz dataset"
if [ ! -d "$mbrainz_backup_dir" ]; then
    tar -xf "${mbrainz##*/}" || fail "failed to untar mbrainz dataset"
fi

info "Importing the mbrainz dataset"
# Pick a random port for a short-lived datomic instance
while PORT=$((RANDOM % 60000 + 5000)); lsof -i:$PORT >/dev/null 2>&1; do :; done
sed "s/^port=.*/port=$PORT/" datomic-pro/config/samples/dev-transactor-template.properties > datomic-pro/config/fixtures-transactor.properties

datomic-pro/bin/transactor config/fixtures-transactor.properties &
datomic_transactor_pid=$!

info "Waiting for Datomic to start on port $PORT..."
while ! timeout bash -c "echo > /dev/tcp/localhost/$PORT 2> /dev/null" 2> /dev/null; do :; done

# https://datomic.narkive.com/OUskfRdr/backup-error
datomic-pro/bin/datomic restore-db "file:$(pwd)/${mbrainz_backup_dir} datomic:dev://localhost:$PORT/mbrainz-full"
kill $datomic_transactor_pid

info "Sample dataset ready."
