#!/usr/bin/env bash
set -euo pipefail

# 生成“前端打进 jar”的单端口包并启动。
# 等价于 Dockerfile 的构建结果，便于没有 Docker 的机器直接部署。

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/env.sh"

cd "$ROOT/frontend"
if [ ! -d node_modules ]; then
  npm install
fi
npm run build

rm -rf "$ROOT/backend/src/main/resources/static"
mkdir -p "$ROOT/backend/src/main/resources/static"
cp -r "$ROOT/frontend/dist/"* "$ROOT/backend/src/main/resources/static/"

cd "$ROOT/backend"
mvn -DskipTests package

cd "$ROOT"
echo "single-jar app: http://localhost:8080"
exec java -jar backend/target/modbus-gateway-0.1.0.jar
