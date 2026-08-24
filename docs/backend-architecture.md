# 后端架构与演进约定

本文记录 `backend/shop-server` 的实际架构、命名约定和后续重构边界，避免用目录形式代替真正的解耦。

## 1. 当前结论

后端是按业务域分包的 Spring Boot 模块化单体。Controller 没有直接依赖 Entity、Mapper 或 `JdbcClient`，接口层边界总体清楚；主要技术债务位于体量过大的 Service、SQL 散落和跨业务域依赖。

当前的 `dto` 并不只表示入参：

- `*Request`：HTTP 入参 DTO。
- `*Response`：HTTP 出参 DTO，也就是传统分层中常说的 VO。
- `entity`：持久化模型，不得由 Controller 直接返回。
- `*Row`、`*Snapshot`：数据库投影或内部快照。
- `*Command`、`*Result`：复杂用例需要隔离 HTTP 契约时使用。

因此不再额外复制一套内容相同的 `vo` 包。是否叫 VO 不影响边界，Entity 不泄漏到 API 才是关键。

## 2. Service 是否必须有接口

不强制采用一对一的 `XxxService + XxxServiceImpl`。Spring 的构造器注入、事务和 AOP 都支持具体类；只有一个实现时，空接口只会增加跳转和样板代码。

满足以下任一条件时才增加接口：

1. 存在两个或更多实现，例如内存与 Redis、Mock 与真实渠道。
2. 对接支付、存储、微信等外部系统，需要稳定的替换端口。
3. 跨业务域调用需要切断包依赖，接口代表最小能力而不是完整 Service 的镜像。
4. 插件或策略确实需要运行时选择实现。

项目现有的 `TokenStore`、`WechatPayProvider`、`WechatShippingProvider`、`StorageProvider` 就属于正确的接口边界。后续应优先为订单、支付、售后之间的最小跨域能力建立 Port，而不是批量生成 `ServiceImpl`。

## 3. MyBatis-Plus 与 JdbcClient 的边界

两者共享同一个 `DataSource` 和 Spring 事务，技术上可以安全共存。选择标准如下：

| 场景 | 首选 | 原因 |
| --- | --- | --- |
| 简单单表 CRUD | MyBatis-Plus `BaseMapper` | 减少重复 SQL，实体映射明确 |
| 多表投影、报表聚合 | `JdbcClient` Query Repository | SQL 形状可见，直接映射 Response/Row |
| `FOR UPDATE`、条件状态迁移 | 显式 Mapper SQL 或 `JdbcClient` Repository | 锁顺序和更新条件必须可审查 |
| 批量写入、数据库专有语法 | `JdbcClient`/`NamedParameterJdbcTemplate` Repository | 便于控制批次与生成键 |

新增代码不得因为个人偏好在同一聚合内随机切换。更重要的规则是：复杂模块的 Service 只负责用例编排、业务不变量、事务边界和事件发布；SQL 与 `ResultSet` 映射应放到 `repository`、`query` 或 `store` 类。

当前 `JdbcClient` 是事实主栈，原有 12 个 `BaseMapper` 中只有少数被业务使用，其余属于未完成迁移的脚手架。不要一次性全量改写；每次重构一个业务聚合，并同时删除该聚合内不再使用的 Mapper/Entity。联系人设置已作为简单单表 CRUD 的 MyBatis-Plus 示例。

## 4. 目标模块结构

复杂业务域逐步收敛为以下结构，简单模块不必创建所有目录：

```text
order/
  controller/        HTTP 适配与鉴权
  dto/               Request / Response
  application/       用例编排、Command / Result
  domain/            状态规则、金额与库存不变量
  repository/        写模型和锁查询
  query/             列表、详情、报表投影
  integration/       支付、物流等外部端口适配
```

事务应包围本地状态变化，不应包围不受控的网络调用。外部调用流程采用“短事务准备/领取 → 事务外调用 → 短事务确认/重试”，并通过幂等键、租约或 outbox 恢复进程崩溃。

## 5. 本轮已经落地

1. 支付预下单改为 `PREPARING + 租约`，采用“短事务准备 → 事务外调用 → 短事务确认”，失败后使用同一商户单号和请求参数恢复。
2. 取消和超时关闭在关单前先查渠道状态；已支付则确认支付，渠道已关闭则补齐本地关闭，解决回调延迟和渠道成功/本地提交失败的崩溃窗口。
3. 支付单持久化渠道配置 ID 和不可逆 SHA-256 指纹；查单、关单、退款、恢复及最终落账都再次校验该身份。每个新支付和退款都生成 192-bit 不可猜回调路由 token，微信收到的地址为固定 HTTPS 基址后追加 `/r/{token}`；最终 URL 在业务状态落库前完成协议、端口和 255 字符上限预检。退款编排显式挂起调用方事务，先用无密钥业务快照排除非法状态，再在进入售后锁事务前完成数据库配置解析及密钥读取；锁内用支付单 ID、配置 ID 和指纹拒绝过期预检结果。回调在验签解密前通过 token 精确定位唯一支付/退款及其数据库配置，查询命中后再做常量时间 token 比较和商户业务号校验；未知、非法、未验签或尚未绑定业务身份的请求都不写业务回调日志。创建支付单前在同一事务内锁行复核渠道关键字段，配置并发变更会让准备阶段重试。已被交易引用的数据库配置禁止原地修改；停用配置可软删除，但保留加密历史行供已绑定交易继续处理。
4. 退款请求不确定态保持 `PROCESSING`，由带租约的定时查单恢复；退款回调日志与业务状态事务分离，失败日志不会随业务回滚丢失。渠道终态为 `CLOSED` 时，管理员可在排除原因后调用 `POST /admin/after-sales/{id}/refund-retry`，系统保留旧记录并用新的商户退款单号发起可恢复的新退款。退款回调、人工查单和定时恢复由同一个 claim token 做 fencing：渠道写请求前必须再次确认 token、渠道状态及最新退款记录并续租，丢失租约时禁止提交或落账。活动租约会阻止新单重试，超过超时的孤儿租约可按原 token 条件清理；相关锁统一按售后、订单、支付单、退款单的顺序获取。`CLOSED` 不允许复用旧单号重提或被“转人工”覆盖，先前 `ABNORMAL` 后查到 `CLOSED` 也会刷新为真实终态。最新异常退款另提供渠道主动查询、安全查后重提以及“转人工处理”入口；所有操作（包括 `CLOSED` 新单重试）要求备注并写审计日志，操作备注不进入 App 退款响应。
5. 商品读取、售后分页、管理员分页和菜单权限组装消除了循环查询；相应测试固定了 SQL 次数上界。
6. 流量趋势、商品销量趋势和漏斗已改为数据库分桶/聚合；退款失败指标按独立的 `failed_at` 统计，避免后续人工查询更新 `updated_at` 后污染历史报表。长时间范围不再为这些图表把每条事实记录加载到 JVM。
7. Snowflake ID 统一按字符串输出到 JavaScript 客户端；管理员登录增加 Redis 原子限流、可信代理解析和失败关闭策略。
8. 联系人设置作为简单单表 CRUD 迁移到 MyBatis-Plus；支付、库存、退款和报表继续保留可审查的显式 SQL。
9. 对象存储上传改为持久化 `UPLOAD_PENDING` 中间态：短事务记录完整对象位置，事务外写入 Provider，再以条件更新激活为 `ACTIVE`；超过宽限期的中断上传由清理任务恢复。删除使用 `ACTIVE → DELETE_PENDING → DELETED` 和带租约的指数退避，Provider 删除失败不再丢失。售后凭证和客服图片上传也已移出业务长事务，未完成绑定的客服图片会按临时 TTL 清理。
10. Admin 在浏览器内读取支付 PEM 文本，后端校验 RSA 材料后以带上下文 AAD 的密文直接写入数据库；配置流程不创建文件或对象存储资产，也不保存文件 ID。API 只返回掩码或 configured 状态，不回传 PEM 正文。
11. 图片上传要求扩展名、声明 MIME 和解码器识别格式一致；显式安装 WebP reader，并以真实 WebP 样本做端到端测试。SVG 使用禁用外部实体的 XML 解析器，允许标准 W3C SVG DOCTYPE 以及设计工具用于命名空间别名的被动 HTTP(S) 实体，并拒绝脚本、事件处理器、动画及外部资源引用。宽、高、SVG viewBox、GIF logical canvas 和总像素超限时会在创建资产记录前拒绝；GIF 最多 16 帧、累计最多 1 亿源像素，所有帧均以每帧最多约 100 万输出像素的子采样完整解码，既能拒绝合法文件头后截断、伪装格式和恶意尾帧，也避免分配原始全尺寸像素缓冲区。
12. 应用密文只接受 `v2:<keyId>:<nonce>:<ciphertext+tag>` 协议，使用 key ring 中按规范小写 ID 精确命中的 AES-256 key，并以 AAD 绑定表域、行身份和字段，禁止跨行/跨字段搬运密文。支付、微信平台、COS 等数据库敏感配置保存密文版本、key ID 和 revision；小批量后台任务在验证明文语义后用 revision CAS 重加密，多节点并发或管理员更新不会被覆盖。轮换以数据库持久化 checkpoint 和短 `FOR UPDATE` 事务原子领取各域 keyset 批次，进程重启、多节点或损坏行都不会长期阻塞后续健康行。启动 key ring 只放在每个环境独立的 ignored runtime manifest 中，数据库和 tracked YAML 不保存主密钥。

## 6. 下一阶段优先级

1. 把总览、交易、用户、营销、客服等剩余报表的原始事实列表逐步下沉为 SQL 聚合，并以生产数据执行 `EXPLAIN ANALYZE` 校准组合索引和深分页策略。
2. 统一商品参数选项与 SPU 参数快照的并发锁顺序，评估从 JSON 快照迁移到规范化关联表，避免删除选项与商品保存并发时写入陈旧值。
3. 将文件引用维护为统一的资产使用账本，替代删除时锁住资产后跨多个业务表扫描（尤其富文本 `locate`）；同时继续拆分 `AdminProductService`、`CustomerServiceService` 等超大类。
4. 为图片上传增加按主体/IP 的速率与流量额度，并用进程级小型信号量限制同步 ImageIO 解码并发；当前字节、尺寸、帧数与累计像素限制约束单请求，但不能替代服务级容量保护。
5. 在 ingress 对支付/退款路由回调设置合理的突发限流和容量告警；监控未知 token、验签失败、商户身份不匹配和渠道重复重试，避免恶意请求或异常重试放大外部调用与日志写入。

每次重构必须保留或增加契约测试、并发测试和 MySQL Testcontainers 测试。目录调整本身不算完成，必须证明行为与性能边界得到改善。

## 7. 代码评审检查项

- Response 中的 app user/address Snowflake ID 是否使用 `@JsonStringId`。
- Controller 是否只消费 Request、返回 Response/`ApiResponse<Void>`，且没有 Entity/Map 弱类型泄漏。
- 新 Service 接口是否真的存在替换点或跨域端口。
- Service 是否新增了 SQL、`ResultSet` 映射或不受控网络调用。
- 数据库事务或行锁期间是否发生 HTTP、对象存储、微信等不受控外部 I/O。
- 所有请求值是否使用绑定参数；动态表名/列名是否来自受控枚举白名单。
- 状态更新是否把期望旧状态、版本或业务不变量放进原子 `WHERE` 条件。
- 外部回调、提交和定时补偿是否具备幂等与崩溃恢复路径。
- 列表组装是否在循环或 RowMapper 内再次访问数据库。
