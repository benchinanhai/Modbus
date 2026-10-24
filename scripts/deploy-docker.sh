#!/usr/bin/env bash
set -euo pipefail

# 构建镜像并启动容器，然后做健康检查。
# 需要 docker 权限（用户在 docker 组，或用 sudo 运行本脚本）。

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! docker info >/dev/null 2>&1; then
  echo "无法访问 Docker 守护进程（当前用户不在 docker 组或组未生效）。"
  echo "任选其一修复："
  echo "  1) sudo ./scripts/deploy-docker.sh                    # 用 root 权限立即执行"
  echo "  2) sudo usermod -aG docker \$USER 并重新登录后，重跑本脚本"
  exit 1
fi

echo "== 释放本机占用的 8080/1502 端口（本地直跑的后端） =="
fuser -k 8080/tcp 2>/dev/null || true
fuser -k 1502/tcp 2>/dev/null || true
sleep 1

echo "== 构建并启动容器 =="
docker compose up --build -d

echo "== 等待应用就绪 =="
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/api/health >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "== 状态 =="
docker compose ps
echo
echo "health:  $(curl -s http://localhost:8080/api/health || echo '未就绪')"
echo "slave:   $(curl -s http://localhost:8080/api/slave || echo '未就绪')"
echo
echo "打开控制台:  http://localhost:8080"
echo "Modbus 从站: 127.0.0.1:1502（Unit 见 设备接入 页）"
echo "导出镜像:    docker save modbus-gateway:0.2.0 | gzip > modbus-gateway-0.2.0.tar.gz"
