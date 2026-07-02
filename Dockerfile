# Runtime image for the comments service. The Spring Boot executable jar is built beforehand on
# the host (`mvn package -DskipTests`).
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/microservice-comments-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
