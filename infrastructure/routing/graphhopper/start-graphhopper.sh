#!/bin/sh
# Starts GraphHopper on the road map of GH_MAP_REGION (a Geofabrik region, default asia/india).
#
#   1. First start only: downloads <region>-latest.osm.pbf from Geofabrik into /data/maps,
#      checked against Geofabrik's MD5 (India: 1.7 GB).
#   2. First start only: imports it into /data/graph-cache/<region> - roads, the car and truck profiles,
#      Contraction Hierarchies and Landmarks (India: see docs/ROUTING_ENGINE_SETUP.md for timings).
#      The import writes to <region>.importing and is renamed only when it finished, so a stopped or failed
#      import is started again from scratch instead of serving half a graph.
#   3. Every start: loads the imported graph and serves http://localhost:8989 (API, /maps/ UI, /health).
#
# GH_IMPORT=never (the home server, docs/HOME_SERVER_SETUP.md): skip 1 and 2 - a machine with too little memory to
# import India only serves a graph imported elsewhere; until one is installed, it waits and says how to get one.
#
# New map data: delete /data/maps/<region>.osm.pbf and /data/graph-cache/<region>, then restart
# (docs/ROUTING_ENGINE_SETUP.md, "Refresh the map").
set -eu

REGION="${GH_MAP_REGION:-asia/india}"
NAME=$(echo "$REGION" | tr '/' '-')
MAP_URL="${GH_MAP_BASE_URL:-https://download.geofabrik.de}/$REGION-latest.osm.pbf"
MAP_FILE="/data/maps/$NAME.osm.pbf"
GRAPH_DIR="/data/graph-cache/$NAME"

mkdir -p /data/maps /data/graph-cache

if [ "${GH_IMPORT:-auto}" = "never" ]; then
    while [ ! -d "$GRAPH_DIR" ]; do
        echo "[routing] No graph for $REGION yet and GH_IMPORT=never: export it on the build laptop" \
             "(infrastructure/home-server/export-routing-graph.ps1) and install it here (install-routing-graph.sh)." \
             "Checking again in 5 minutes."
        sleep 300
    done
fi

if [ ! -d "$GRAPH_DIR" ] && [ ! -s "$MAP_FILE" ]; then
    echo "[routing] Downloading $MAP_URL"
    curl -fL --retry 5 --retry-delay 10 -o "$MAP_FILE.part" "$MAP_URL"
    EXPECTED=$(curl -fsL --retry 5 "$MAP_URL.md5" | cut -d ' ' -f 1)
    ACTUAL=$(md5sum "$MAP_FILE.part" | cut -d ' ' -f 1)
    if [ "$EXPECTED" != "$ACTUAL" ]; then
        rm -f "$MAP_FILE.part"
        echo "[routing] MD5 mismatch (expected $EXPECTED, got $ACTUAL) - Geofabrik may have published a new file meanwhile; the next start downloads again." >&2
        exit 1
    fi
    mv "$MAP_FILE.part" "$MAP_FILE"
    echo "[routing] Map saved as $MAP_FILE ($(du -h "$MAP_FILE" | cut -f 1))"
fi

if [ ! -d "$GRAPH_DIR" ]; then
    echo "[routing] Importing $MAP_FILE - the API stays down until this finishes"
    rm -rf "$GRAPH_DIR.importing"
    started=$(date +%s)
    # Landmark and CH tables in the heap while they are prepared, written to disk once (see config.yml).
    sed 's/^\(  "graph\.dataaccess\.type\..*"\): MMAP$/\1: RAM_STORE/' /app/config.yml > /tmp/import-config.yml
    grep -q 'landmarks_.*": RAM_STORE' /tmp/import-config.yml
    # shellcheck disable=SC2086 - JAVA_OPTS holds several options on purpose
    java ${GH_IMPORT_JAVA_OPTS:--Xmx7g} \
        -Ddw.graphhopper.datareader.file="$MAP_FILE" \
        -Ddw.graphhopper.graph.location="$GRAPH_DIR.importing" \
        -jar /app/graphhopper-web.jar import /tmp/import-config.yml
    mv "$GRAPH_DIR.importing" "$GRAPH_DIR"
    echo "[routing] Import finished in $(( ($(date +%s) - started) / 60 )) min"
fi

echo "[routing] Serving $GRAPH_DIR on :8989"
# shellcheck disable=SC2086
exec java ${GH_SERVER_JAVA_OPTS:--Xmx3g} \
    -Ddw.graphhopper.datareader.file="$MAP_FILE" \
    -Ddw.graphhopper.graph.location="$GRAPH_DIR" \
    -jar /app/graphhopper-web.jar server /app/config.yml
