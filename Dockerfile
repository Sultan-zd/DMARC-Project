# One image, one process, one port.
#
# Development runs two servers: Vite on 5173 for the interface and Spring Boot on
# 8000 for the API. That is convenient to work in and wrong to deploy — it needs two
# ports published, and /api becomes a cross-origin call that CORS then has to be
# relaxed for. Building the interface into the application's static resources
# collapses both into a single origin, which is what the three stages below do.
#
#   docker compose up --build          the whole thing, database included
#   docker build -t dmarc-dashboard .  just the image

# ─────────────────────────────────────────────────────────────────────────────
# 1. The interface
#
# Vite is configured to write into ../backend/src/main/resources/static, so the
# output lands beside the backend sources rather than in frontend/dist. The path is
# created here explicitly: it exists on a developer's machine, and would otherwise
# be the one thing about this build that only works there.
# ─────────────────────────────────────────────────────────────────────────────
FROM node:20-alpine AS frontend

WORKDIR /build/frontend
RUN mkdir -p /build/backend/src/main/resources

# Dependencies before sources: this layer is rebuilt only when the lockfile moves,
# so editing a component does not reinstall node_modules.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build


# ─────────────────────────────────────────────────────────────────────────────
# 2. The application
#
# Tests are skipped here on purpose. They are not skipped because they are slow but
# because they belong to a different question: `mvnw test` answers "is this code
# correct", and it has to be able to fail the build somewhere a person is watching.
# An image build that silently reran them would double every deploy and tell nobody
# anything new.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS backend

WORKDIR /build/backend

# Same reasoning as npm ci above: the dependency layer survives a source change.
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src

# After the sources, so the freshly built interface always wins over anything a
# stale directory in the build context might have carried in.
COPY --from=frontend /build/backend/src/main/resources/static ./src/main/resources/static

RUN mvn -B -q clean package -DskipTests


# ─────────────────────────────────────────────────────────────────────────────
# 3. What actually runs
#
# A JRE, not a JDK: the compiler, the debugger and the rest of the toolchain have
# no business in a running container, and every one of them is reachable by
# anything that gets in.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

# Timestamps are stored in UTC by the application. Running the container in UTC as
# well means the log agrees with the database instead of being an hour out twice a
# year.
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# mariadb-client, for one reason: the application takes its own backups.
#
# The alternative was a sidecar container running mariadb-dump on a cron. That is
# tidier in principle and worse in practice — the failure mode of backups is not
# "nobody set them up", it is "they stopped four months ago and nobody noticed".
# Taking them from inside the application means the Platform page can say when the
# last one ran and go red when it did not, which is the part that actually keeps
# them working.
RUN apk add --no-cache mariadb-client

# Nothing here needs to write outside /tmp and the backup directory, and a process
# that cannot write to its own installation cannot be made to overwrite it.
RUN addgroup -S dmarc && adduser -S -G dmarc -h /app dmarc \
    && mkdir -p /backups && chown dmarc:dmarc /backups

WORKDIR /app
COPY --from=backend --chown=dmarc:dmarc /build/backend/target/dmarc-*.jar /app/app.jar

USER dmarc
EXPOSE 8000

# The application's own endpoint rather than a TCP probe: a JVM that has bound the
# port but cannot reach the database is listening and not working, and only this
# distinguishes the two. wget is busybox's, already present on alpine.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1:8000/api/health || exit 1

# exec, so the JVM is PID 1 and receives SIGTERM directly. Without it the shell
# holds the signal, the container is killed after the grace period, and Spring
# never runs a shutdown hook.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
