FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Build scripts first so the dependency layer survives source changes.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Documentation only: the real port comes from SERVER_PORT, which compose passes in.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
