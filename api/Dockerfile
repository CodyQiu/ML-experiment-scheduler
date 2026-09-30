# syntax=docker/dockerfile:1.26.0

# Build stage: package the jar with the Maven Wrapper. Tests are not run here because they
# need Docker for Testcontainers; run them with ./mvnw verify or scripts/mvnw-docker.sh verify.
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

# Runtime stage: JRE only, running as an unprivileged user.
FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN useradd --system --no-create-home --user-group scheduler
WORKDIR /app
COPY --from=build /workspace/target/scheduler-api-*.jar app.jar
USER scheduler
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
