# === Stage 1: Build ===
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Gradle wrapper & 설정 복사 (캐시 활용)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 & 빌드
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# === Stage 2: Run ===
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 18080

ENTRYPOINT ["java", "-jar", "app.jar"]
