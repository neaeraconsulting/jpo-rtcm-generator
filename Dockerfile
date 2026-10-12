FROM maven:4.0.0-rc-7-eclipse-temurin-25-alpine AS builder

WORKDIR /home

ARG MAVEN_GITHUB_TOKEN
ARG MAVEN_GITHUB_ORG

ENV MAVEN_GITHUB_TOKEN=$MAVEN_GITHUB_TOKEN
ENV MAVEN_GITHUB_ORG=$MAVEN_GITHUB_ORG

COPY ./jpo-rtcm-generator/pom.xml ./jpo-rtcm-generator/
COPY ./jpo-rtcm-generator/settings.xml ./jpo-rtcm-generator/

# Download dependencies alone to cache them first
WORKDIR /home/jpo-rtcm-generator
RUN mvn -s settings.xml dependency:resolve

# Copy the source code and build the rtcm converter
COPY ./jpo-rtcm-generator/src ./src
RUN mvn -s settings.xml package -DskipTests

FROM eclipse-temurin:25-jre-alpine

WORKDIR /home

COPY ./jpo-rtcm-generator/src/test/resources/j2735ffm/libasnapplication.so          /home
COPY --from=builder /home/jpo-rtcm-generator/src/main/resources/application.yaml /home
COPY --from=builder /home/jpo-rtcm-generator/target/jpo-rtcm-generator-*.jar /home/jpo-rtcm-generator.jar

ENTRYPOINT ["java", \
	"-Djava.rmi.server.hostname=$DOCKER_HOST_IP", \
	"-Dcom.sun.management.jmxremote.port=9090", \
	"-Dcom.sun.management.jmxremote.rmi.port=9090", \
	"-Dcom.sun.management.jmxremote", \
	"-Dcom.sun.management.jmxremote.local.only=true", \
	"-Dcom.sun.management.jmxremote.authenticate=false", \
	"-Dcom.sun.management.jmxremote.ssl=false", \
	"-jar", \
	"/home/jpo-rtcm-generator.jar"]