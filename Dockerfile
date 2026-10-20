FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
WORKDIR /app
RUN addgroup -S vaultcore && adduser -S vaultcore -G vaultcore
COPY --chown=vaultcore:vaultcore target/vaultcore-*.jar app.jar
USER vaultcore
EXPOSE 8080
ENTRYPOINT ["java", "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=48m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-jar", "/app/app.jar"]
