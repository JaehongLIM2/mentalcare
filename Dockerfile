# syntax=docker/dockerfile:1
# Multi-stage build: frontend (Node) → backend jar (Maven) → small JRE runtime.
# No data, .env, credentials or build secrets ever enter an image layer (see .dockerignore).

# ---------- 1. frontend ----------
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# vite.config.ts writes to ../backend/src/main/resources/static
RUN mkdir -p /src/backend/src/main/resources && npm run build

# ---------- 2. backend ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /src/backend/src/main/resources/static ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# ---------- 3. runtime ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1000 mentalcare && adduser -S -u 1000 -G mentalcare -h /app mentalcare \
    && mkdir -p /data && chown mentalcare:mentalcare /data
WORKDIR /app
COPY --from=backend --chown=mentalcare:mentalcare /src/backend/target/mentalcare.jar /app/mentalcare.jar
USER mentalcare
ENV MENTALCARE_DATA_DIR=/data \
    SERVER_ADDRESS=0.0.0.0 \
    MENTALCARE_PUBLIC_URL=http://127.0.0.1:8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp"
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD wget -qO- --header="Host: 127.0.0.1:8080" http://127.0.0.1:8080/api/home > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/mentalcare.jar"]
