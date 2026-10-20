# Dockerfile estándar DigitalFix - report (consumidor, sin servidor web: sin EXPOSE)
# Ajustes sobre el template: fuente DejaVu para PDFBox y UID fijo 10003,
# dueño de los datos del volumen /data existente.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache font-dejavu \
    && addgroup -S -g 10003 app && adduser -S -u 10003 -G app app \
    && mkdir /data && chown app:app /data && chmod 700 /data
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER app

ENV REPORT_FONT=/usr/share/fonts/dejavu/DejaVuSans.ttf
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
