FROM node:22-alpine AS frontend-build
WORKDIR /workspace
COPY apps/web/package.json apps/web/package-lock.json ./apps/web/
RUN cd apps/web && npm ci
COPY apps/web ./apps/web
COPY packages ./packages
RUN npm run build --prefix apps/web

FROM maven:3.9.11-eclipse-temurin-17 AS backend-build
WORKDIR /workspace
COPY pom.xml ./
COPY services/server/pom.xml ./services/server/
RUN mvn -B -q -pl services/server -DskipTests dependency:go-offline
COPY services/server/src ./services/server/src
COPY --from=frontend-build /workspace/services/server/src/main/resources/static/app ./services/server/src/main/resources/static/app
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system lanchat \
    && useradd --system --gid lanchat --home-dir /app lanchat \
    && mkdir -p /app/uploads /app/logs \
    && chown -R lanchat:lanchat /app
COPY --from=backend-build --chown=lanchat:lanchat /workspace/services/server/target/lan-chat-server-*.jar /app/lanchat.jar
USER lanchat
EXPOSE 8080
ENV FILE_STORAGE_PATH=/app/uploads/ \
    LANCHAT_LOG_FILE=/app/logs/lan-chat.log \
    TUNNEL_ENABLED=false
VOLUME ["/app/uploads", "/app/logs"]
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/api/v1/node/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/lanchat.jar"]
