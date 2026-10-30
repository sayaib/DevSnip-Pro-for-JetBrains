#!/usr/bin/env bash
# What a plugin ZIP adds to opening a project, from the IDE's own startup report: runs the IDE
# several times on a new configuration for each ZIP, and once more with no plugin at all.
#   scripts/benchmark-startup.sh <IDE folder> <project folder> <runs> [zip ...]
# Prints each run's times; the first run of a configuration includes the IDE's first-start work,
# so it is left out of the medians.
set -euo pipefail
ide=${1:?IDE folder}; project=${2:?project folder}; runs=${3:-4}; shift 3 || true
here=$(cd "$(dirname "$0")" && pwd)

run_variant() {
  local label=$1 zip=$2 work
  work=$(mktemp -d -t devsnip-bench)
  mkdir -p "$work/config" "$work/system" "$work/plugins" "$work/log"
  [ -n "$zip" ] && unzip -q "$zip" -d "$work/plugins"
  cat > "$work/idea.properties" <<EOF
idea.config.path=$work/config
idea.system.path=$work/system
idea.plugins.path=$work/plugins
idea.log.path=$work/log
EOF
  local info root java args
  info=$( (ls "$ide"/Resources/product-info.json "$ide"/product-info.json 2>/dev/null || true) | head -1)
  root=$(cd "$(dirname "$info")/.." && pwd); [ -d "$root/lib" ] || root=$(dirname "$info")
  java="$root/jbr/Contents/Home/bin/java"; [ -x "$java" ] || java="$root/jbr/bin/java"
  for n in $(seq 1 "$runs"); do
    local report="$work/perf-$n.json"
    args=$(python3 "$here/ide-launch-args.py" "$info" "$root" "-Didea.properties.file=$work/idea.properties" "-Didea.log.perf.stats.file=$report" -Didea.trust.all.projects=true \
      -Djb.consents.confirmation.enabled=false "-Djb.privacy.policy.text=<!--999.999-->" -Didea.initially.ask.config=never -Dide.experimental.ui.onboarding=false)
    eval "exec \"$java\" $args \"$project\"" >"$work/stdout-$n.log" 2>&1 &
    local pid=$! waited=0
    while [ ! -s "$report" ] && [ $waited -lt 180 ] && kill -0 $pid 2>/dev/null; do sleep 1; waited=$((waited + 1)); done
    sleep 2
    kill $pid 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 $pid 2>/dev/null || break; sleep 1; done
    kill -9 $pid 2>/dev/null || true
    python3 "$here/ide-startup-report.py" "$label" "$n" "$report"
  done
  rm -rf "$work"
}

run_variant "no plugin" ""
for zip in "$@"; do run_variant "$(basename "$zip" .zip)" "$zip"; done
