FROM gradle:8.7-jdk17 AS builder
WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY mediawiki-pagecounter-job/build.gradle mediawiki-pagecounter-job/build.gradle

#RUN gradle --no-daemon build -x test || true

# 실제 소스 복사 후 빌드
COPY mediawiki-pagecounter-job/src mediawiki-pagecounter-job/src

RUN gradle --no-daemon :mediawiki-pagecounter-job:shadowJar

FROM flink:1.18-java17
WORKDIR /opt/flink
COPY --from=builder /workspace/mediawiki-pagecounter-job/build/libs/mediawiki-pagecounter-job-*-all.jar \
     /opt/flink/usrlib/mediawiki-pagecounter-job.jar