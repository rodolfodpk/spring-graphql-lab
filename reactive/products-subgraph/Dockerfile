ARG BUILD_IMAGE=eclipse-temurin:21.0.11_10-jdk-jammy
ARG RUNTIME_IMAGE=eclipse-temurin:21.0.11_10-jre-jammy

# Build context is the repository root, not this directory: the service needs lab-model built from
# source, so the reactor has to be visible. Compose selects this file via STACK.
FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
# Poms first, so a source-only change does not re-resolve dependencies. Every pom the aggregator
# lists must be present -- `-pl ... -am` parses all of them, including supergraph's.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY model/pom.xml model/pom.xml
COPY supergraph/pom.xml supergraph/pom.xml
COPY reactive/products-subgraph/pom.xml reactive/products-subgraph/pom.xml
COPY reactive/pricing-subgraph/pom.xml reactive/pricing-subgraph/pom.xml
COPY reactive/inventory-subgraph/pom.xml reactive/inventory-subgraph/pom.xml
COPY servlet/products-subgraph/pom.xml servlet/products-subgraph/pom.xml
COPY servlet/pricing-subgraph/pom.xml servlet/pricing-subgraph/pom.xml
COPY servlet/inventory-subgraph/pom.xml servlet/inventory-subgraph/pom.xml
RUN ./mvnw -q -DskipTests -pl reactive/products-subgraph -am dependency:go-offline
COPY model/src model/src
COPY reactive/products-subgraph/src reactive/products-subgraph/src
RUN ./mvnw -q -DskipTests -pl reactive/products-subgraph -am package

FROM ${RUNTIME_IMAGE}
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/reactive/products-subgraph/target/products-subgraph-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
