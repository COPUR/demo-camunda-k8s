# The demo's one application image, built entirely inside Docker: no JDK or Gradle is needed on the host. It holds
# two jars: payments-demo.jar (simulators and workers, BPO_DEMO_ROLE picks one) and payment-exception-api.jar.
# Build context = the repository root (install.sh: docker build -f app/Dockerfile .), so the tests can read
# sample-data/ and models/. Stage 1 compiles and tests the modules with JDK 23; stage 2 is the runtime JRE only.
FROM eclipse-temurin:23-jdk@sha256:83177670e43c17769a5402a60601545318e45e63adf634fb5022340e516cded4 AS build
WORKDIR /src
# Behind a TLS-inspecting proxy, install.sh copies DEMO_BUILD_CA to .build-ca.crt (empty otherwise); every
# certificate in it is trusted by this build stage only, for downloading Gradle and dependencies.
COPY .build-ca.crt /tmp/build-ca.crt
# Only the BEGIN..END blocks are kept (a bundle may carry comments or subject lines), any keytool failure stops
# the build, and a non-empty file with no certificate in it is an error rather than a silent no-op.
RUN set -e; if [ -s /tmp/build-ca.crt ]; then \
      awk '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > ("/tmp/build-ca-" n ".crt")} /-----END CERTIFICATE-----/{p=0}' /tmp/build-ca.crt; \
      ls /tmp/build-ca-*.crt >/dev/null 2>&1 || { echo "DEMO_BUILD_CA holds no PEM certificate" >&2; exit 1; }; \
      for f in /tmp/build-ca-*.crt; do \
        keytool -importcert -noprompt -cacerts -storepass changeit -alias "$(basename "$f" .crt)" -file "$f" >/dev/null; \
      done; \
    fi
COPY app/ app/
COPY sample-data/ sample-data/
# payment-exception-api's tests hold its exception catalog against these BPMN models.
COPY models/ models/
WORKDIR /src/app
ARG SKIP_TESTS=false
# With tests, payment-exception-api must also keep line and branch coverage at 87% or more (JaCoCo rule).
RUN if [ "$SKIP_TESTS" = "true" ]; then ./gradlew --no-daemon -q :payments-demo:bootJar :payment-exception-api:bootJar; \
    else ./gradlew --no-daemon -q test :payment-exception-api:jacocoTestCoverageVerification :payments-demo:bootJar \
      :payment-exception-api:bootJar; fi

FROM eclipse-temurin:23-jre@sha256:4972459272d4050ab14700677ac7a7511194f1d9ed333b95e886c3c8c80dedae
WORKDIR /app
COPY --from=build /src/app/payments-demo/build/libs/payments-demo.jar /app/payments-demo.jar
# The exception API is its own Spring Boot application; its Deployment (k8s/45-payment-exception-api.yaml) runs this
# jar instead of the entrypoint below. One image, so both are built, tested and released together.
COPY --from=build /src/app/payment-exception-api/build/libs/payment-exception-api.jar /app/payment-exception-api.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/payments-demo.jar"]
