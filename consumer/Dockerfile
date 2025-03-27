FROM eclipse-temurin:17-jdk AS build

WORKDIR /app
COPY . .

RUN ./gradlew bootJar

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]