# V19–V21 数据库与履约修复

此次升级通过新迁移推进，不改 V1–V18。应同时发布后端与管理端、小程序契约变更。

## 数量语义

- `order_item.quantity`：购买数量；`refunded_quantity`：成功退款对应的累计商品数量。
- `order_shipment_item.quantity`：实际出库数量。商品后来被退回时，该历史事实不减少。
- `after_sale_fulfillment_allocation`：售后批准数量分配到未发商品（`UNSHIPPED`）或已发包裹商品（`SHIPPED`）。仅退款先取消未发商品，超出的部分归已发商品；退货退款只处理已发商品。同一订单项多个包裹按出库明细 ID 顺序分配，这是同款商品的记账约定，不代表用户确认了原包裹来源。
- 待发数量 = 购买数量 − 实际出库数量 − 成功退款中取消的未发数量。退回已发商品不会减少另外的待发商品。
- `received_quantity`：验货实际收到数量；`restock_quantity`：可重新销售并回库的数量。校验 0 ≤ 回库 ≤ 实收 ≤ 批准数量，历史实收保留 NULL。取消未发商品不经过退货验货，实收仍为 NULL。

批准分配、退款入账、库存恢复都沿用订单事务锁。成功回调可重复接收但只能入账一次。退款取消最后的待发商品后，订单进入已发货，发货时间采用真实包裹时间；微信最终包裹标记通过现有投递机制刷新。在途旧请求不能确认新载荷成功。

## 历史数据

V19 仅在退款申请记录为付款待发状态、类型为仅退款、且申请时不存在已发或发货时间未知包裹时，将批准数量标为取消未发商品。其余记录为 `LEGACY_UNKNOWN`，不猜测对应包裹。未知来源的历史部分发货订单会拒绝继续发货，涉及该商品的新售后也会提前拒绝，需先核对原出库、退款记录并通过单独审核的数据修复补齐来源；不要直接将未知改成未发。

`shipment_item_id=0` 仅用于无包裹来源的未发取消和历史未知记录；已发来源必须关联真实出库明细。分配记录随订单聚合一起归档、锁定和清理。

V21 分类快照仅由新订单写入。历史订单不使用商品当前分类回填，报表显示“历史未记录”。分类 ID/名称的快照在商品分类改名或移动后保持原值。

## 发布前检查

先执行 [V20 只读预检](database-v20-preflight.sql)，按[约束升级说明](database-v20-constraints.md)处理真实异常。不要用归零库存、伪造分类或任意去重来通过迁移。

新增约束和索引会检查现有数据，发布时需要协调旧实例写入并保留备份。MySQL DDL 失败后不能假定整份迁移已回滚，应核对实际结构和 Flyway 历史后恢复。

升级后可用以下只读查询定位仍有待履约但历史退款来源不明的订单，结果须结合原始记录核对：

```sql
SELECT DISTINCT o.id, o.order_no, r.id AS after_sale_id
FROM shop_order o
JOIN after_sale_request r ON r.order_id=o.id AND r.status='REFUNDED'
JOIN after_sale_item i ON i.after_sale_id=r.id
JOIN after_sale_fulfillment_allocation a ON a.after_sale_item_id=i.id
WHERE o.status IN ('PAID','PARTIALLY_SHIPPED')
  AND a.source_type='LEGACY_UNKNOWN';
```

本次代码验证使用隔离测试数据库；上线目标库的数据质量、迁移耗时及微信线上结果需在发布流程中另行验证。
