FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S pulse && adduser -S pulse -G pulse

WORKDIR /app

COPY --from=build /build/target/ingest-service-*.jar app.jar

USER pulse

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
