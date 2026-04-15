FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

WORKDIR /app/Backend

RUN chmod +x mvnw && ./mvnw clean package -DskipTests

EXPOSE 8080

CMD ["sh", "-c", "java -jar target/email-writer-sb-0.0.1-SNAPSHOT.jar --server.port=${PORT:-8080}"]
