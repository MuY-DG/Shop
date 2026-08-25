# 后端架构约定

后端是按业务域分包的 Spring Boot 模块化单体。目录形式不是目标，稳定的接口边界、
事务边界和可验证的业务不变量才是目标。

## API 与模型

- Controller 只接收 `*Request`，返回 `*Response` 或明确的空响应。
- Entity 是持久化模型，不直接进入 HTTP 响应。
- `*Row`、`*Snapshot` 用于数据库投影和内部快照。
- `*Command`、`*Result` 只在复杂用例需要隔离 HTTP 契约时使用。
- Snowflake ID 向 JavaScript 客户端输出时必须使用字符串。

项目不额外复制一套与 Response 内容相同的 VO。命名可以不同，但 Entity 不能泄漏到
接口层。

## Service 与端口

只有一个实现时，不强制创建 `XxxService + XxxServiceImpl`。以下情况才增加接口：

1. 确实存在多个实现。
2. 支付、微信、存储等外部系统需要可替换端口。
3. 跨业务域只应暴露一组最小能力。
4. 策略或插件需要在运行时选择实现。

接口应表达稳定能力，不能只是把具体 Service 的全部方法复制一遍。

## 数据访问

| 场景 | 首选 |
| --- | --- |
| 简单单表 CRUD | MyBatis-Plus `BaseMapper` |
| 多表投影、报表聚合 | `JdbcClient` Query Repository |
| `FOR UPDATE`、条件状态迁移 | 显式 Mapper SQL 或 `JdbcClient` Repository |
| 批量写入、数据库专有语法 | `JdbcClient` / `NamedParameterJdbcTemplate` |

复杂 Service 负责用例编排、业务不变量和事务；SQL 与 `ResultSet` 映射放在
`repository`、`query` 或 `store`。同一聚合内不要无理由混用访问方式。

## 事务与外部调用

数据库事务不能包围 HTTP、COS、微信等不受控网络调用。标准流程是：

```text
短事务准备/领取 -> 事务外调用 -> 短事务确认或安排重试
```

所有外部提交、回调和定时补偿都需要幂等键，以及进程在任意一步退出后的恢复路径。
状态更新应把期望旧状态、版本号或业务不变量放进原子 `WHERE` 条件。

## 模块结构

复杂业务域可逐步使用以下结构，简单模块不必创建空目录：

```text
order/
  controller/   HTTP 适配与鉴权
  dto/          Request / Response
  application/  用例编排
  domain/       状态、金额和库存规则
  repository/   写模型与锁查询
  query/        列表、详情和报表
  integration/  外部端口适配
```

## 评审检查

- Controller 是否依赖 Entity、Mapper、`JdbcClient` 或弱类型 Map。
- 新接口是否真的存在替换点或跨域边界。
- 行锁或事务期间是否发生外部 I/O。
- 请求值是否全部使用绑定参数，动态标识符是否来自枚举白名单。
- 列表组装是否在循环或 RowMapper 中再次访问数据库。
- 变更是否补充了对应契约、并发或 MySQL Testcontainers 测试。
