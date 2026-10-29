#!/usr/bin/env bash
# Compile the atom and pack dist/http-request/ — the directory tools/publish-atom.sh expects.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVAC=javac
  JAR=jar
fi

classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
"$JAVAC" -encoding UTF-8 -d "$classes" src/HttpRequestAtom.java

rm -rf dist/http-request
mkdir -p dist/http-request
"$JAR" --create --file dist/http-request/http-request.jar -C "$classes" .
cp task.json dist/http-request/
[ -f meta.json ] && cp meta.json dist/http-request/

echo "built dist/http-request: $(ls dist/http-request | tr '\n' ' ')"
