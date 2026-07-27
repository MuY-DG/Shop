UPDATE admin_permission
SET title = CASE auth_mark
    WHEN 'system:user:read' THEN '查看管理员'
    WHEN 'system:user:create' THEN '创建管理员'
    WHEN 'system:user:update' THEN '编辑管理员'
    WHEN 'system:user:disable' THEN '停用管理员'
    WHEN 'system:role:read' THEN '查看角色'
    WHEN 'system:role:create' THEN '创建角色'
    WHEN 'system:role:update' THEN '编辑角色'
    WHEN 'system:role:assign' THEN '配置角色权限'
    WHEN 'system:role:delete' THEN '删除角色'
    WHEN 'system:menu:read' THEN '查看菜单与权限资源'
    WHEN 'system:log:read' THEN '查看系统日志'
    WHEN 'product:category:create' THEN '创建商品分类'
    WHEN 'product:category:update' THEN '编辑商品分类'
    WHEN 'product:spu:create' THEN '创建商品'
    WHEN 'product:spu:update' THEN '编辑商品'
    WHEN 'product:spu:publish' THEN '上架或下架商品'
    WHEN 'product:spu:delete' THEN '删除商品'
    WHEN 'product:spu:restore' THEN '恢复回收站商品'
    WHEN 'product:spu:purge' THEN '永久删除商品'
    WHEN 'product:sku:stock' THEN '调整商品库存'
    WHEN 'product:spec-template:create' THEN '创建规格模板'
    WHEN 'product:spec-template:update' THEN '编辑规格模板'
    WHEN 'product:guarantee:create' THEN '创建商品保障服务'
    WHEN 'product:guarantee:update' THEN '编辑商品保障服务'
    WHEN 'product:guarantee:delete' THEN '删除商品保障服务'
    WHEN 'product:guarantee:visibility' THEN '调整商品保障服务可见性'
    WHEN 'product:freight:create' THEN '创建运费模板'
    WHEN 'product:freight:update' THEN '编辑运费模板'
    WHEN 'product:coupon:bind' THEN '绑定商品优惠券'
    WHEN 'product:coupon:create' THEN '创建商品优惠券'
    WHEN 'product:parameter:read' THEN '查看商品参数'
    WHEN 'product:parameter:write' THEN '管理商品参数'
    WHEN 'product:review:read' THEN '查看商品评论'
    WHEN 'product:review:moderate' THEN '审核商品评论'
    WHEN 'coupon:template:create' THEN '创建优惠券模板'
    WHEN 'coupon:template:update' THEN '编辑优惠券模板'
    WHEN 'coupon:template:enable' THEN '启用优惠券模板'
    WHEN 'coupon:template:disable' THEN '停用优惠券模板'
    WHEN 'coupon:claim:read' THEN '查看优惠券领取记录'
    WHEN 'order:read' THEN '查看订单'
    WHEN 'order:close' THEN '关闭订单'
    WHEN 'order:ship' THEN '订单发货'
    WHEN 'order:shipping:retry' THEN '重试微信发货信息上传'
    WHEN 'aftersale:read' THEN '查看售后'
    WHEN 'aftersale:audit' THEN '审核售后'
    WHEN 'asset:upload' THEN '上传素材'
    WHEN 'asset:read' THEN '查看素材'
    WHEN 'asset:delete' THEN '删除素材'
    WHEN 'asset:folder' THEN '管理素材分组'
    WHEN 'content:banner:read' THEN '查看首页轮播图'
    WHEN 'content:banner:create' THEN '创建首页轮播图'
    WHEN 'content:banner:update' THEN '编辑首页轮播图'
    WHEN 'content:banner:publish' THEN '发布首页轮播图'
    WHEN 'content:home-category:read' THEN '查看首页分类'
    WHEN 'content:home-category:write' THEN '管理首页分类'
    WHEN 'content:home-hot:read' THEN '查看首页热销商品'
    WHEN 'content:home-hot:write' THEN '管理首页热销商品'
    WHEN 'content:home-recommended:read' THEN '查看首页推荐商品'
    WHEN 'content:home-recommended:write' THEN '管理首页推荐商品'
    WHEN 'content:contact:read' THEN '查看小程序联系设置'
    WHEN 'content:contact:write' THEN '管理小程序联系设置'
    WHEN 'storage:config:read' THEN '查看对象存储配置'
    WHEN 'storage:config:write' THEN '编辑对象存储配置'
    WHEN 'payment:config:read' THEN '查看支付配置'
    WHEN 'payment:config:write' THEN '编辑支付配置'
    WHEN 'payment:config:enable' THEN '启用支付配置'
    WHEN 'customer-service:conversation:read' THEN '查看客服会话'
    WHEN 'customer-service:conversation:claim' THEN '接入客服会话'
    WHEN 'customer-service:conversation:transfer' THEN '转接客服会话'
    WHEN 'customer-service:conversation:close' THEN '关闭客服会话'
    WHEN 'customer-service:message:send' THEN '发送客服消息'
    WHEN 'customer-service:order:link' THEN '关联客服会话订单'
    WHEN 'customer-service:product:send' THEN '发送客服商品卡片'
    WHEN 'customer-service:agent:manage' THEN '管理客服分配'
    WHEN 'customer:user:read' THEN '查看小程序客户'
    WHEN 'customer:coupon:issue' THEN '向客户发放优惠券'
    WHEN 'operation:overview:read' THEN '查看运营概览'
    WHEN 'operation:trade:read' THEN '查看交易统计'
    WHEN 'operation:product:read' THEN '查看商品统计'
    WHEN 'operation:user:read' THEN '查看用户统计'
    WHEN 'operation:traffic:read' THEN '查看流量统计'
    WHEN 'operation:marketing:read' THEN '查看营销统计'
    WHEN 'operation:service:read' THEN '查看服务统计'
    WHEN 'amap:config:read' THEN '查看高德地图配置'
    WHEN 'amap:config:write' THEN '管理高德地图配置'
    WHEN 'image-compression:config:read' THEN '查看图片压缩配置'
    WHEN 'image-compression:config:write' THEN '管理图片压缩配置'
    ELSE title
END
WHERE auth_mark IN (
    'system:user:read',
    'system:user:create',
    'system:user:update',
    'system:user:disable',
    'system:role:read',
    'system:role:create',
    'system:role:update',
    'system:role:assign',
    'system:role:delete',
    'system:menu:read',
    'system:log:read',
    'product:category:create',
    'product:category:update',
    'product:spu:create',
    'product:spu:update',
    'product:spu:publish',
    'product:spu:delete',
    'product:spu:restore',
    'product:spu:purge',
    'product:sku:stock',
    'product:spec-template:create',
    'product:spec-template:update',
    'product:guarantee:create',
    'product:guarantee:update',
    'product:guarantee:delete',
    'product:guarantee:visibility',
    'product:freight:create',
    'product:freight:update',
    'product:coupon:bind',
    'product:coupon:create',
    'product:parameter:read',
    'product:parameter:write',
    'product:review:read',
    'product:review:moderate',
    'coupon:template:create',
    'coupon:template:update',
    'coupon:template:enable',
    'coupon:template:disable',
    'coupon:claim:read',
    'order:read',
    'order:close',
    'order:ship',
    'order:shipping:retry',
    'aftersale:read',
    'aftersale:audit',
    'asset:upload',
    'asset:read',
    'asset:delete',
    'asset:folder',
    'content:banner:read',
    'content:banner:create',
    'content:banner:update',
    'content:banner:publish',
    'content:home-category:read',
    'content:home-category:write',
    'content:home-hot:read',
    'content:home-hot:write',
    'content:home-recommended:read',
    'content:home-recommended:write',
    'content:contact:read',
    'content:contact:write',
    'storage:config:read',
    'storage:config:write',
    'payment:config:read',
    'payment:config:write',
    'payment:config:enable',
    'customer-service:conversation:read',
    'customer-service:conversation:claim',
    'customer-service:conversation:transfer',
    'customer-service:conversation:close',
    'customer-service:message:send',
    'customer-service:order:link',
    'customer-service:product:send',
    'customer-service:agent:manage',
    'customer:user:read',
    'customer:coupon:issue',
    'operation:overview:read',
    'operation:trade:read',
    'operation:product:read',
    'operation:user:read',
    'operation:traffic:read',
    'operation:marketing:read',
    'operation:service:read',
    'amap:config:read',
    'amap:config:write',
    'image-compression:config:read',
    'image-compression:config:write'
);

UPDATE admin_role
SET name = CASE
        WHEN code = 'R_SUPER' AND name = 'Super Admin' THEN '超级管理员'
        WHEN code = 'R_ADMIN' AND name = 'Admin' THEN '商城管理员'
        WHEN code = 'R_CUSTOMER_SERVICE' AND name = 'Customer Service' THEN '客服'
        ELSE name
    END,
    description = CASE
        WHEN code = 'R_SUPER' AND description = 'Full system access' THEN '拥有系统全部权限'
        WHEN code = 'R_ADMIN' AND description = 'Shop operator access' THEN '负责商城日常运营管理'
        WHEN code = 'R_CUSTOMER_SERVICE' AND description = 'Online customer service agent' THEN '负责在线客户服务'
        ELSE description
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE (code = 'R_SUPER' AND (name = 'Super Admin' OR description = 'Full system access'))
   OR (code = 'R_ADMIN' AND (name = 'Admin' OR description = 'Shop operator access'))
   OR (code = 'R_CUSTOMER_SERVICE'
       AND (name = 'Customer Service' OR description = 'Online customer service agent'));
