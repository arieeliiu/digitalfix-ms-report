FROM eclipse-temurin:21-jdk AS compilacion
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM compilacion AS pruebas
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["./mvnw", "-B", "test"]

FROM eclipse-temurin:21-jre AS produccion
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10003 digitalfix \
    && useradd --uid 10003 --gid digitalfix --no-create-home digitalfix \
    && mkdir /data && chown digitalfix:digitalfix /data && chmod 700 /data
WORKDIR /app
COPY --from=compilacion /app/target/*.jar app.jar
USER digitalfix
ENTRYPOINT ["java", "-jar", "app.jar"]
