# 빌드 단계: JDK로 실행 가능한 jar를 만든다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 다운로드를 소스 복사보다 먼저 해서, 소스만 바뀌면 이 레이어를 캐시로 재사용한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null

COPY src src
# 테스트는 CI의 build 단계에서 돌리므로 이미지 빌드에서는 건너뛴다
RUN ./gradlew bootJar --no-daemon -x test \
    && cp build/libs/*-SNAPSHOT.jar app.jar

# 실행 단계: JRE만 담아 이미지 크기를 줄인다
FROM eclipse-temurin:21-jre
WORKDIR /app

# root가 아닌 사용자로 실행한다
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=build /workspace/app.jar app.jar

# 컨테이너 메모리 한도의 75%까지 힙으로 쓴다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENV TZ=Asia/Seoul

ENTRYPOINT ["java", "-jar", "app.jar"]
