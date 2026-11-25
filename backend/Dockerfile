FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
ARG V2V_MAVEN_CENTRAL_MIRROR=https://maven-central-asia.storage-download.googleapis.com/maven2/
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system v2v && useradd --system --gid v2v v2v
COPY --from=build --chown=v2v:v2v /workspace/build/libs/*.jar /app/v2v-backend.jar
ENV SPRING_PROFILES_ACTIVE=cloud
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0"
EXPOSE 8080
USER v2v
ENTRYPOINT ["java", "-jar", "/app/v2v-backend.jar"]
