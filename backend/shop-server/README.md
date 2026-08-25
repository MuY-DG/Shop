# shop-server

Java 21 + Spring Boot 3.5 模块化单体后端，使用 MySQL、Redis、Flyway 和
Docker Compose。

## 目录

```text
config/runtime/    运行密钥模板；真实环境文件不提交
scripts/ci/        CI 与测试分层校验
scripts/config/    生成/校验运行密钥、首次引导 Super
src/main/          应用代码、配置和 Flyway
src/test/          单元、H2 与 Testcontainers 测试
compose.prod.yaml  txcloud/shop 共用的服务器拓扑
Dockerfile         生产镜像
```

`target/`、真实 `config/runtime/*.env` 和一次性管理员凭据都是本地产物，已被
Git 与 Docker 构建上下文排除。

## 本机开发

```bash
./scripts/config/init-runtime-env.sh local
./scripts/config/validate-runtime-env.sh local
./mvnw -Dspring-boot.run.profiles=local spring-boot:run
```

`init-runtime-env.sh` 只在文件不存在时运行，不会覆盖已有密钥。本机 MySQL 和 Redis
密码必须与 `config/runtime/local.env` 一致。

## 测试

```bash
./mvnw test
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports
./scripts/ci/verify-test-layers.sh
./scripts/ci/verify-flyway-migrations.sh
```

默认测试层不包含 Testcontainers；`-Pintegration verify` 才会验证真实
MySQL 8.4 和 Redis 7.4 兼容性。

## 服务器部署

仓库根目录只有一个部署入口：

```bash
./deploy.sh txcloud
./deploy.sh shop
```

首次和日常部署使用相同命令，后端与 Admin 一起发布。Compose 数据卷会在日常部署中
继续复用。服务器和 1Panel 前置条件见
[部署文档](../../docs/deployment-guide.md)。
