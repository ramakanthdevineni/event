FROM eclipse-temurin:26-jdk AS builder
WORKDIR /app
COPY . /app
RUN javac -d out src/main/java/com/example/App.java

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=builder /app/out /app/out
COPY index.html /app/index.html
COPY registration.html /app/registration.html
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "out", "com.example.App"]
