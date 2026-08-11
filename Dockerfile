FROM eclipse-temurin:21-jdk AS builder
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/event-homepage-1.0.0.jar /app/app.jar
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
