#!/usr/bin/env bash
# Run the atom the way the runner does — input.json in, output.json out — against a real HTTP server started here.
# No Jenkins and no external network: CI can run it on every push.
set -euo pipefail
cd "$(dirname "$0")/.."

./build.sh >/dev/null
jar="$(cd dist/http-request && pwd)/http-request.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
port=$(( 20000 + RANDOM % 20000 ))

work="$(mktemp -d)"
served="$(mktemp -d)"
printf 'endpoint-body\n' >"$served/ok.txt"
( cd "$served" && python3 -m http.server "$port" --bind 127.0.0.1 >/dev/null 2>&1 ) &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT

for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$port/ok.txt" >/dev/null 2>&1; then break; fi
  sleep 0.25
done

run() { # run <dir> --  reads input.json from <dir>
  local dir="$1"
  ( cd "$dir" && atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" BUILD_NUMBER=7 \
      "$java_bin" -cp "$jar" HttpRequestAtom )
}

fail() { echo "smoke failed: $*" >&2; exit 1; }

# 1. 200, body and status in the outputs, body saved into the workspace
dir="$work/ok"; mkdir -p "$dir"
cat >"$dir/input.json" <<JSON
{"url": "http://127.0.0.1:$port/ok.txt?n=\${BUILD_NUMBER}", "method": "GET",
 "headers": "X-Build: \${BUILD_NUMBER}", "body": "", "expectStatus": "200",
 "saveTo": "out/body.txt", "printBody": "true", "timeoutSeconds": "20"}
JSON
run "$dir" >/dev/null
grep -q '"status":"success"' "$dir/output.json" || fail "expected success: $(cat "$dir/output.json")"
grep -q '"body":{"type":"string","value":"endpoint-body' "$dir/output.json" || fail "body missing"
grep -q '"status":{"type":"string","value":"200"}' "$dir/output.json" || fail "status missing"
grep -q '"savedFile"' "$dir/output.json" || fail "savedFile missing"
[ "$(cat "$dir/out/body.txt")" = "endpoint-body" ] || fail "saved body is not the response"

# 2. an unexpected status is a failure with a readable message
dir="$work/missing"; mkdir -p "$dir"
cat >"$dir/input.json" <<JSON
{"url": "http://127.0.0.1:$port/missing.txt", "method": "GET", "headers": "", "body": "",
 "expectStatus": "2xx", "saveTo": "", "printBody": "false", "timeoutSeconds": "20"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "404 must fail the atom"; fi
grep -q '"status":"failure"' "$dir/output.json" || fail "404 not reported"
grep -q 'expected 2xx, got 404' "$dir/output.json" || fail "404 message is not readable: $(cat "$dir/output.json")"

# 3. an unreachable host is an error, not a crash
dir="$work/unreachable"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"url": "http://127.0.0.1:1/none", "method": "GET", "headers": "", "body": "",
 "expectStatus": "2xx", "saveTo": "", "printBody": "false", "timeoutSeconds": "3"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "connection refused must fail the atom"; fi
grep -q '"status":"error"' "$dir/output.json" || fail "connection error not reported"

echo "smoke ok: 200 + saved body, 404 failure, unreachable host"
