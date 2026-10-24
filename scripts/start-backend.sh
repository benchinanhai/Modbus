#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/env.sh"

cd "$ROOT/backend"
if [ ! -f target/modbus-gateway-0.1.0.jar ]; then
  echo "building backend..."
  mvn -DskipTests package
fi

echo "backend: http://localhost:8080"
exec java -jar target/modbus-gateway-0.1.0.jar
