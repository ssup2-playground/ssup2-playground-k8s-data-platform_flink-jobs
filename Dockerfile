# Build stage
FROM gradle:8.14.3-jdk11 AS builder
WORKDIR /app

## Copy gradle wrapper
COPY gradlew .
RUN chmod +x gradlew

## Copy gradle configs
COPY gradle gradle
COPY build.gradle settings.gradle .
COPY mediawiki-pagecounter-job/build.gradle mediawiki-pagecounter-job/

## Copy source code
COPY mediawiki-pagecounter-job/src mediawiki-pagecounter-job/src

## Build
RUN ./gradlew --no-daemon --parallel --build-cache :wikimedia-page-create-counter:shadowJar

# Run stage
FROM flink:1.18-java11
WORKDIR /opt/flink

## Run
ENV FLINK_ENV_JAVA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"
COPY --from=builder /app/mediawiki-pagecounter-job/build/libs/mediawiki-pagecounter-job-*-all.jar \
     /opt/flink/usrlib/mediawiki-pagecounter-job.jar