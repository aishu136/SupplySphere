# One Dockerfile for every service in the platform:
#   docker build --build-arg SERVICE=order-service -t scm/order-service scm-platform
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl ${SERVICE} -am package -DskipTests \
    && cp ${SERVICE}/target/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
