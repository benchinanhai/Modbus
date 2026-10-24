# syntax=docker/dockerfile:1

# ---------- 前端构建 ----------
FROM node:22-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 后端构建（把前端产物打进 jar） ----------
FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /workspace
COPY backend/pom.xml ./backend/pom.xml
COPY backend/src ./backend/src
COPY --from=frontend /workspace/frontend/dist ./backend/src/main/resources/static
RUN mvn -q -f backend/pom.xml -DskipTests package

# ---------- 运行时 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/config
COPY --from=backend /workspace/backend/target/modbus-gateway-0.1.0.jar /app/app.jar
ENV GATEWAY_CONFIG=/app/config/real-devices.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
