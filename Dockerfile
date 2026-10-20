FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw --batch-mode -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
WORKDIR /app
RUN addgroup -S vaultcore && adduser -S vaultcore -G vaultcore
COPY --from=build --chown=vaultcore:vaultcore /workspace/target/vaultcore-*.jar app.jar
USER vaultcore
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -q -T 2 -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=48m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-jar", "/app/app.jar"]
