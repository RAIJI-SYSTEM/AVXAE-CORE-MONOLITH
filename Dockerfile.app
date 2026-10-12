FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY raiji-common/pom.xml ./raiji-common/
COPY raiji-security/pom.xml ./raiji-security/
COPY raiji-users/pom.xml ./raiji-users/
COPY raiji-app/pom.xml ./raiji-app/
RUN mvn -B dependency:go-offline || true

COPY raiji-common/src ./raiji-common/src
COPY raiji-security/src ./raiji-security/src
COPY raiji-users/src ./raiji-users/src
COPY raiji-app/src ./raiji-app/src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/raiji-app/target/raiji-app.jar ./app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
