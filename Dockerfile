# Stage 1: Build the application
FROM maven:3.9.6-amazoncorretto-21 AS builder
WORKDIR /app

# Copy the pom.xml and source code
COPY pom.xml .
COPY src ./src

# Package the application (skip tests to speed up the build in CI)
RUN mvn clean package -DskipTests

# Stage 2: Create the runtime image (AL2023 for ONNX Runtime glibc 2.27+ compatibility)
FROM amazoncorretto:21-al2023-headless
WORKDIR /app

# Copy the generated fat (executable) JAR file
COPY --from=builder /app/target/*.jar app.jar

# Expose the application port
EXPOSE 10000

# Run the application with optimized JVM flags for Render Free Tier (512MB RAM)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:ActiveProcessorCount=1", "-jar", "app.jar"]
