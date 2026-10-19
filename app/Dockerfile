# The demo's one application image, built entirely inside Docker: no JDK or Gradle is needed on the host.
# Build context = the repository root (install.sh: docker build -f app/Dockerfile .), so the tests can read
# sample-data/. Stage 1 compiles and tests the modules with JDK 23; stage 2 is the runtime JRE only.
FROM eclipse-temurin:23-jdk AS build
WORKDIR /src
COPY app/ app/
COPY sample-data/ sample-data/
WORKDIR /src/app
ARG SKIP_TESTS=false
RUN if [ "$SKIP_TESTS" = "true" ]; then ./gradlew --no-daemon -q :payments-demo:bootJar; \
    else ./gradlew --no-daemon -q test :payments-demo:bootJar; fi

FROM eclipse-temurin:23-jre@sha256:4972459272d4050ab14700677ac7a7511194f1d9ed333b95e886c3c8c80dedae
WORKDIR /app
COPY --from=build /src/app/payments-demo/build/libs/payments-demo.jar /app/payments-demo.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/payments-demo.jar"]
