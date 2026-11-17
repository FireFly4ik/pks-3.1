FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /project
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /project/target/hospital-web-backend-1.0.0.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","app.jar"]
