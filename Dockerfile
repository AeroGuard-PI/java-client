FROM maven:3.10.0-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
ENV DB_HOST=localhost
ENV DB_PORT=3306
ENV DB_USER=""
ENV DB_PASSWORD=""
ENV EMPRESA_CNPJ=""
ENV MAC_ADDRESS=""
ENV HOST_ROOT=/host
COPY --from=build /app/target/aeroguard-java.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
