# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests -Dspring-boot.repackage.skip=true package dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /opt/opensim-spawner

COPY --from=build /workspace/target/opensim-spawner-*.jar /opt/opensim-spawner/app.jar
COPY --from=build /workspace/target/dependency /opt/opensim-spawner/lib
COPY docker/entrypoint.sh /usr/local/bin/opensim-spawner-entrypoint.sh
RUN chmod +x /usr/local/bin/opensim-spawner-entrypoint.sh

ENV OPENSIM_SPAWNER_HTTP_HOST=0.0.0.0 \
    OPENSIM_SPAWNER_HTTP_PORT=8993 \
    OPENSIM_SPAWNER_FIRST_PORT=12345 \
    OPENSIM_METAVERSE2MCP_IMAGE=bithatch/opensim-metaverse2mcp:latest \
    OPENSIM_OPENCODE_IMAGE=bithatch/opensim-opencode:latest

VOLUME ["/config", "/data", "/workspace"]
ENTRYPOINT ["/usr/local/bin/opensim-spawner-entrypoint.sh"]
