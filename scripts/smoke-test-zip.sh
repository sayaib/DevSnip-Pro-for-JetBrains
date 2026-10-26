#!/usr/bin/env bash
# Installs the release ZIP into a fresh, empty IDE configuration (as "Install Plugin from Disk"
# does) and runs the plugin's self-check (DevSnipSelfTest) in that IDE, then prints its results.
#   scripts/smoke-test-zip.sh <IDE folder (the .app's Contents, or the unpacked IDE)> <project folder> [zip]
# The project should have a README.md (README Manager otherwise asks in a modal dialog, which stops the
# self-check), a data.csv and a src/app.js with a `total` variable (for the CSV viewer and Insert Debug Log checks).
set -euo pipefail

ide=${1:?IDE folder}
project=${2:?project folder}
zip=${3:-$(ls -t "$(dirname "$0")/../build/distributions/"devsnip-pro-jetbrains-*.zip | grep -v signed | head -1)}
work=$(mktemp -d -t devsnip-smoke)
mkdir -p "$work/config" "$work/system" "$work/plugins" "$work/log"
unzip -q "$zip" -d "$work/plugins"

cat > "$work/idea.properties" <<EOF
idea.config.path=$work/config
idea.system.path=$work/system
idea.plugins.path=$work/plugins
idea.log.path=$work/log
EOF

# The IDE's own Java runtime with its launch settings (product-info.json); macOS refuses the
# launcher binary outside its signed app bundle.
info=$( (ls "$ide"/Resources/product-info.json "$ide"/product-info.json 2>/dev/null || true) | head -1)
root=$(cd "$(dirname "$info")/.." && pwd)
[ -d "$root/lib" ] || root=$(dirname "$info")
java="$root/jbr/Contents/Home/bin/java"
[ -x "$java" ] || java="$root/jbr/bin/java"
mapfile_args=$(python3 - "$info" "$root" "-Didea.properties.file=$work/idea.properties" -Ddevsnip.selfTest=exit -Didea.trust.all.projects=true \
  -Djb.consents.confirmation.enabled=false "-Djb.privacy.policy.text=<!--999.999-->" -Didea.initially.ask.config=never \
  -Dide.experimental.ui.onboarding=false <<'PY'
import json, sys, shlex
info, root, extra = sys.argv[1], sys.argv[2], sys.argv[3:]
d = json.load(open(info))
launch = next(l for l in d["launch"] if l["os"].lower() in ("macos", "linux", "windows"))
cp = ":".join(f"{root}/lib/{j}" for j in launch["bootClassPathJarNames"])
args = [a.replace("$APP_PACKAGE/Contents", root).replace("$APP_PACKAGE", root).replace("$IDE_HOME", root) for a in launch.get("additionalJvmArguments", [])]
vm = [l.strip() for l in open(f"{root}/bin/{launch['vmOptionsFilePath'].split('/')[-1]}") if l.strip() and not l.startswith("#")]
print(" ".join(shlex.quote(x) for x in vm + args + extra + ["-cp", cp, launch.get("mainClass") or d.get("mainClass")]))
PY
)
echo "IDE: $ide"
echo "ZIP: $zip"
eval "exec \"$java\" $mapfile_args \"$project\"" >"$work/stdout.log" 2>&1 &
pid=$!
for _ in $(seq 1 600); do
  grep -aq "DEVSNIP-SELFTEST: done\|DEVSNIP-SELFTEST: FAILED\|engine ready=false" "$work/log/idea.log" 2>/dev/null && break
  kill -0 $pid 2>/dev/null || break
  sleep 1
done
sleep 5
# Wait for the IDE to quit, so the next run does not find it still holding the configuration.
kill $pid 2>/dev/null || true
for _ in $(seq 1 30); do kill -0 $pid 2>/dev/null || break; sleep 1; done
kill -9 $pid 2>/dev/null || true
grep -a "DEVSNIP-SELFTEST" "$work/log/idea.log" | sed 's/^.*DEVSNIP-SELFTEST: //' | sed -E 's/(page=[^{]*).*/\1/' | cut -c1-140
echo "Plugin loaded: $(grep -ac 'pro.devsnip.api' "$work/log/idea.log") log lines mention it; errors blamed on it: $(grep -a -c 'Plugin to blame: DevSnip' "$work/log/idea.log" || true)"
cp "$work/log/idea.log" /tmp/devsnip-smoke-last.log; rm -rf "$work"
