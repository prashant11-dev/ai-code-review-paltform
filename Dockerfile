# =========================
# Stage 1: Build
# =========================
FROM maven:3.9-eclipse-temurin-23 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn dependency:go-offline

COPY src ./src

RUN mvn clean package -DskipTests


# =========================
# Stage 2: Runtime
# =========================
FROM eclipse-temurin:23-jre

WORKDIR /app

# -m creates the home dir: JGit resolves ~/.gitconfig via user.home when cloning,
# and useradd points appuser at a /home/appuser it would not otherwise create.
RUN useradd -r -u 1001 -m appuser

COPY --from=build /app/target/*.jar app.jar

# The compose named volumes mount onto these two paths. Docker only inherits
# ownership from the image when the directory already exists there - otherwise
# it creates them root-owned and appuser cannot write uploads or clone repos.
RUN mkdir -p /app/uploads /app/temp-repositories \
    && chown -R appuser:appuser /app/uploads /app/temp-repositories app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
