# Builds the GPS streaming service (and the route service) into small images, in two stages:
#   1. "build"   : a full JDK + Maven compile the code and produce the jars of every module
#   2. runtime   : only a Java runtime and one jar - no source code, no Maven, no JDK
#        "route-service" : gps-route-service.jar   (docker compose: profile "routing")
#        "gps-service"   : gps-streaming-service.jar (the last stage, so a plain "docker build ." builds this one)
#
# Build it:  docker build -t gps-streaming-service .
#            docker build --target route-service -t gps-route-service .
# (docker compose builds them for you - see docker-compose.yml)

# ---------- stage 1: build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY gps-configuration gps-configuration
COPY gps-connectivity gps-connectivity
COPY gps-processing gps-processing
COPY gps-module-api gps-module-api
COPY gps-streams gps-streams
COPY gps-application gps-application
COPY gps-route-service gps-route-service

# The cache mount keeps downloaded Maven libraries between builds, so a rebuild after a code change is fast.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -q -DskipTests package

# ---------- stage 2a: route service runtime ----------
FROM eclipse-temurin:21-jre AS route-service
WORKDIR /app

RUN useradd --system --uid 1001 gps
USER gps

COPY --from=build /workspace/gps-route-service/target/gps-route-service.jar gps-route-service.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/gps-route-service.jar"]

# ---------- stage 2b: GPS streaming service runtime (default) ----------
FROM eclipse-temurin:21-jre AS gps-service
WORKDIR /app

# Do not run as root inside the container.
# /var/lib/gps-streams: the Kafka Streams state stores (RocksDB), mounted as a volume by docker compose so a new
# container reuses them instead of restoring every store from its changelog topic.
# /var/lib/gps-journal: the ingest journal - frames and events not yet confirmed by Kafka; a volume, so a new
# container sends them instead of losing them.
RUN useradd --system --uid 1001 gps \
    && mkdir -p /var/lib/gps-streams /var/lib/gps-journal \
    && chown gps /var/lib/gps-streams /var/lib/gps-journal
USER gps

COPY --from=build /workspace/gps-application/target/gps-streaming-service.jar gps-streaming-service.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gps-streaming-service.jar"]
