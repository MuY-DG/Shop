# syntax=docker/dockerfile:1.7

# 使用 Maven 与 JDK 21 在容器内构建，避免依赖开发电脑上的 Maven/JDK 状态。
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

# Maven 仓库使用 BuildKit 缓存，后续构建可以复用已经下载的依赖。
COPY pom.xml ./

# 镜像构建只负责打包；完整测试应在构建镜像前单独执行。
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode -Dmaven.test.skip=true package \
    && cp target/shop-server-*.jar /workspace/application.jar \
    && java -Djarmode=tools -jar /workspace/application.jar \
        extract --layers --destination /workspace/extracted

# 生产镜像只保留 JRE、健康检查工具和 Spring Boot 分层文件。
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 shop \
    && useradd --system --uid 10001 --gid shop \
        --home-dir /nonexistent --shell /usr/sbin/nologin shop

WORKDIR /app

# 按稳定性从高到低复制 Spring Boot 层；业务代码变化时可复用依赖层。
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./

# 容器不包含任何生产环境变量、证书或上传文件。
USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Duser.timezone=UTC", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/application.jar"]
