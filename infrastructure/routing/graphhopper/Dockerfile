# The road routing engine: GraphHopper (open source, Java) on the OpenStreetMap road network of India.
#
# Only the official GraphHopper server jar from Maven Central, checked against its published SHA-256,
# plus our config.yml, our truck model for India and the start script. No map inside the image:
# the map is downloaded and imported on the first start into the /data volume (see start-graphhopper.sh).
#
# Build it:  docker build -t gps-graphhopper infrastructure/routing/graphhopper
# (docker compose builds it for you - see the "routing" profile in docker-compose.yml)

FROM eclipse-temurin:21-jre

ARG GRAPHHOPPER_VERSION=11.1
ARG GRAPHHOPPER_SHA256=8462f758d9ea49edaded557cec5c687a0a24f004ec371cd4daaeb0534824ea33

WORKDIR /app

ADD --checksum=sha256:${GRAPHHOPPER_SHA256} \
    https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/${GRAPHHOPPER_VERSION}/graphhopper-web-${GRAPHHOPPER_VERSION}.jar \
    /app/graphhopper-web.jar

COPY config.yml /app/config.yml
COPY custom_models /app/custom_models
COPY start-graphhopper.sh /app/start-graphhopper.sh

# Do not run as root inside the container. /data is created here so a new volume gets this owner.
RUN useradd --system --uid 1001 graphhopper \
    && chmod 0644 /app/graphhopper-web.jar \
    && chmod 0755 /app/start-graphhopper.sh \
    && mkdir -p /data \
    && chown graphhopper /data
USER graphhopper

VOLUME /data
EXPOSE 8989
ENTRYPOINT ["/app/start-graphhopper.sh"]
