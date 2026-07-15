FROM node:22-alpine AS frontend-build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci
COPY frontend ./frontend
RUN mkdir -p src/main/resources/static/app \
    && cd frontend \
    && npm run build

FROM maven:3.9.11-eclipse-temurin-17 AS backend-build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
COPY --from=frontend-build /workspace/src/main/resources/static/app ./src/main/resources/static/app
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system lanchat \
    && useradd --system --gid lanchat --home-dir /app lanchat \
    && mkdir -p /app/uploads \
    && chown -R lanchat:lanchat /app
COPY --from=backend-build --chown=lanchat:lanchat /workspace/target/lan-chat-server-1.0.jar /app/lanchat.jar
USER lanchat
EXPOSE 8080
ENV FILE_STORAGE_PATH=/app/uploads/ \
    TUNNEL_ENABLED=false
VOLUME ["/app/uploads"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/lanchat.jar"]
