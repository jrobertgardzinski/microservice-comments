# Runtime image for the comments service. The Spring Boot executable jar is built beforehand on
# the host (`mvn -pl comments-infrastructure -am package -DskipTests`). Build context is the repo
# root, which is what docker-compose.yml passes (`context: microservice-comments`).
#
# The path is comments-infrastructure/target/... because the service is four Maven modules (the
# estate's layers: domain, config, application, infrastructure) and infrastructure is the only one
# with a main() — and the only one the Spring Boot plugin repackages.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY comments-infrastructure/target/comments-infrastructure-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
