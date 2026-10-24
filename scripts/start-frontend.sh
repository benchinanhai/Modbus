#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/env.sh"

cd "$ROOT/frontend"
if [ ! -d node_modules ]; then
  echo "installing frontend dependencies..."
  npm install
fi

echo "frontend: http://localhost:5173"
exec npm run dev
