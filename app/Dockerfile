# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Dependency layer first: it changes far less often than the sources.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- runtime ---
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# logback writes ./logs/application.log; the runtime user has to own that path.
RUN mkdir -p /app/logs && chown -R appuser:appuser /app
USER appuser
# The container clock is pinned to UTC: news timestamps are published in UTC.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
