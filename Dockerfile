FROM eclipse-temurin:26-jdk AS builder
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q package

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=builder /app/target/event-homepage-1.0.0.jar /app/app.jar
COPY index.html /app/index.html
COPY registration.html /app/registration.html
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
