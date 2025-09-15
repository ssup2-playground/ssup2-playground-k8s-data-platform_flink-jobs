## Build stage
FROM gradle:8.7-jdk17 AS builder
WORKDIR /workspace

# Copy Gradle files first for better layer caching
COPY gradle/ gradle/
COPY gradlew gradlew.bat ./
COPY settings.gradle build.gradle ./

# Copy subproject files
COPY wikimedia-page-create-counter/build.gradle wikimedia-page-create-counter/
COPY wikimedia-page-create-counter/src wikimedia-page-create-counter/src

# Build with optimized Gradle settings
RUN gradle --no-daemon --parallel --build-cache :wikimedia-page-create-counter:shadowJar

## Run stage
FROM flink:1.18-java17
WORKDIR /opt/flink

# Copy the built JAR
COPY --from=builder /workspace/wikimedia-page-create-counter/build/libs/wikimedia-page-create-counter-*-all.jar \
     /opt/flink/usrlib/mediawiki-page-create-counter.jar

# Set proper permissions
USER flink