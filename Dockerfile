# Build stage
FROM gradle:8.14.3-jdk11 AS builder
WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY mediawiki-pagecounter-job/build.gradle mediawiki-pagecounter-job/build.gradle

COPY mediawiki-pagecounter-job/src mediawiki-pagecounter-job/src

RUN ./gradlew --no-daemon --parallel --build-cache :wikimedia-page-create-counter:shadowJar

# Run stage
FROM flink:1.18-java11
WORKDIR /opt/flink

ENV FLINK_ENV_JAVA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"
COPY --from=builder /workspace/mediawiki-pagecounter-job/build/libs/mediawiki-pagecounter-job-*-all.jar \
     /opt/flink/usrlib/mediawiki-pagecounter-job.jar