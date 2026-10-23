# ---- build ----
FROM eclipse-temurin:21-jdk AS build
ENV TANDEM_SERVER_ONLY=1
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core core
COPY server server
RUN ./gradlew :server:installDist --no-daemon -q

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 tandem
USER tandem
WORKDIR /app
COPY --from=build /app/server/build/install/server .
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8090
ENTRYPOINT ["bin/server"]
