# 빌드 단계: JDK로 실행 가능한 jar를 만든다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 빌드 설정만 먼저 복사해 의존성을 받는다. 빌드 설정이 그대로면 이 단계는 레이어 캐시로 건너뛴다
# 받은 의존성은 이미지 레이어가 아니라 캐시 마운트(/root/.gradle)에 저장되어 아래 bootJar 단계와 다음 빌드에서 재사용된다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew dependencies --no-daemon > /dev/null

COPY src src
# 테스트는 CI의 build 단계에서 돌리므로 이미지 빌드에서는 건너뛴다
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon -x test --build-cache \
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
