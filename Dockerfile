FROM eclipse-temurin:23-jre
WORKDIR /app
RUN useradd -r -u 1001 appuser \
 && mkdir -p /app/uploads /app/temp-repositories \
 && chown -R appuser /app
COPY --chown=appuser target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
