FROM gradle:8.5-jdk17 AS build
COPY . /home/app
WORKDIR /home/app
RUN gradle build

FROM openjdk:17
COPY --from=build /home/app/build/libs /app
WORKDIR /app
CMD ["java", "-cp", ".", "ProducerMain"]