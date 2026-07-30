ARG BUILD_IMAGE=eclipse-temurin:21.0.11_10-jdk-jammy
ARG RUNTIME_IMAGE=eclipse-temurin:21.0.11_10-jre-jammy

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM ${RUNTIME_IMAGE}
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/pricing-subgraph-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
