#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/env.sh"

echo "== backend =="
cd "$ROOT/backend"
mvn -DskipTests package

echo "== frontend =="
cd "$ROOT/frontend"
npm install
npm run build

echo "done: backend/target/modbus-gateway-0.1.0.jar, frontend/dist/"
