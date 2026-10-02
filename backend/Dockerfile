# syntax=docker/dockerfile:1
# Phase J (docs/architecture.md §10.1) — contexte de build = RACINE du dépôt :
#   docker compose -f docker/docker-compose.yml build   (build.context: ..)
# Aucun secret dans ce fichier (AGENTS.md §13).

FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY backend/pom.xml backend/pom.xml
COPY prompts/extraction prompts/extraction

# Couche de cache des dépendances. Best effort : toute dépendance non
# résolue ici le sera par l'étape package suivante (erreurs donc visibles).
RUN mvn -B -f backend/pom.xml -DskipTests dependency:go-offline || true

COPY backend/src backend/src

# -DskipTests : ADR-09, la suite de tests doit passer sans Docker (Phase K).
RUN mvn -B -f backend/pom.xml -DskipTests package \
 && find /build/backend/target -maxdepth 1 -name '*.jar' ! -name 'original-*' \
    -exec cp {} /build/app.jar \; \
 && test -f /build/app.jar

FROM eclipse-temurin:17-jre

# curl = healthcheck compose (ADR-13 /actuator/health).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 adgendoc \
 && useradd --system --uid 10001 --gid adgendoc --home-dir /app \
      --no-create-home --shell /usr/sbin/nologin adgendoc \
 && mkdir -p /app/migrations /data/storage \
 && chown -R adgendoc:adgendoc /app /data

# ADR-04 : schéma appliqué uniquement par Flyway, hors classpath.
COPY --chown=adgendoc:adgendoc database/migrations /app/migrations
COPY --from=builder --chown=adgendoc:adgendoc /build/app.jar /app/app.jar

USER adgendoc
WORKDIR /app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
