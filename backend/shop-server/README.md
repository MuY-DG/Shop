# shop-server

后端目录只保留应用源码、数据库基线、运行清单模板和三类脚本：

```text
shop-server/
  config/runtime/              唯一 runtime manifest 模板；真实文件不提交
  scripts/ci/                  CI 与本地工程门禁
  scripts/config/              生成/校验 manifest、一次性引导 Super
  scripts/deploy/              后端部署、远程切换和 MySQL 备份
  src/main/java/               Spring Boot 业务代码
  src/main/resources/          application 配置与 Flyway V1-V7
  src/test/                    H2、单元和 Testcontainers 测试
  compose.prod.yaml            txcloud/shop 共用的服务器拓扑
  Dockerfile                   后端镜像构建
```

`target/`、`.idea/`、真实 `config/runtime/*.env`、一次性管理员凭据和服务器生成的
`backups/` 都是本机或运行期产物，不属于源码。它们被 Git/Docker 构建上下文排除。

## 脚本职责

| 目录 | 脚本 | 用途 |
| --- | --- | --- |
| `scripts/ci` | `verify-flyway-migrations.sh` | 校验迁移命名、版本连续性 |
| `scripts/ci` | `verify-test-layers.sh` | 校验 Testcontainers 标签和固定镜像 |
| `scripts/ci` | `assert-integration-test-results.sh` | 确认全部集成套件实际执行且零跳过 |
| `scripts/config` | `init-runtime-env.sh` | 为指定目标生成唯一的 5 项秘密清单 |
| `scripts/config` | `validate-runtime-env.sh` | 校验清单白名单、长度和 AES key ring |
| `scripts/config` | `bootstrap-admin.sh` | 对全新空库执行一次性 Super CAS 引导 |
| `scripts/deploy` | `deploy-backend.sh` | 本机发布入口；双重 dirty gate、构建、唯一候选上传并触发切换 |
| `scripts/deploy` | `remote-deploy.sh` | 服务器内部的发布锁、备份、切换、健康检查与应用回滚 |
| `scripts/deploy` | `backup-mysql.sh` | 在服务器生成压缩 MySQL 备份及 SHA-256 sidecar |

日常手动使用的是 `config` 中的清单/引导脚本和 `deploy-backend.sh`；
`remote-deploy.sh` 由发布入口调用。仓库不再包含 Docker/1Panel 安装或密码轮换脚本。

每次发布都生成唯一 `deploy_id`，远端候选清单和调用脚本不会与其他发布共用文件名；
服务器在修改 canonical 文件、镜像标签或数据服务前先解析候选 Compose，并在同目录用原子
`mv` 切换 runtime/Compose；canonical 运维脚本只在应用健康且版本核对成功后提升。HUP、INT、
TERM 会转入清理/回滚。服务器通过同一目标级非阻塞 `flock` 拒绝并发切换或人工备份；发布内部备份复用继承的锁文件描述符。常规发布要求 runtime manifest 的 5 项秘密
与服务器当前值完全一致，数据库/Redis 密码或加密 key ring 的轮换必须走独立维护流程。
应用回滚只恢复 Compose、runtime 和旧镜像并重新做健康检查，不会自动撤销 Flyway 或回灌数据库。

## 环境

| 目标 | Spring Profile | manifest |
| --- | --- | --- |
| 本机 | `local` | `config/runtime/local.env` |
| txcloud | `server` | `config/runtime/txcloud.env` |
| shop | `server` | `config/runtime/shop.env` |

三个 manifest 使用同一模板，只保存数据库、Redis 和数据库敏感字段主密钥。
微信、支付、COS、服务动态及业务运行开关通过 Admin 保存到数据库。

```bash
./scripts/config/init-runtime-env.sh local
./scripts/config/validate-runtime-env.sh local
./mvnw -Dspring-boot.run.profiles=local spring-boot:run
./scripts/config/bootstrap-admin.sh local
```

服务器配置、首次引导、部署和回滚见
[`docs/docker-deployment.md`](../../docs/docker-deployment.md)。
