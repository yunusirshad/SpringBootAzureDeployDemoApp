# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/screener-app.jar app.jar
ENV SCREENER_STORE_DIR=/app/data
EXPOSE 8080
CMD ["sh", "-c", "java -XX:MaxRAMPercentage=75 -jar app.jar --server.port=${PORT:-8080}"]
