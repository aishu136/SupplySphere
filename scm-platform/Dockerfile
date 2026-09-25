# One Dockerfile for every service in the platform:
#   docker build --build-arg SERVICE=order-service -t scm/order-service scm-platform
FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q :${SERVICE}:bootJar \
    && cp ${SERVICE}/build/libs/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
