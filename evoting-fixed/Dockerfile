# Stage 1 — Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src

# Set memory limit so Render's free/starter tier doesn't kill the build
ENV MAVEN_OPTS="-Xmx300m"

# Build the app, showing errors (-e) but hiding download spam (--no-transfer-progress)
RUN mvn package -DskipTests --no-transfer-progress -e

# Stage 2 — Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S evoting && adduser -S evoting -G evoting
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown evoting:evoting app.jar
USER evoting
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
