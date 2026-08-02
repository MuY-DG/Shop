package org.muybaby.shopserver.maintenance.cleanup;

public enum DataCleanupTaskCode {
    ANALYTICS_EVENT(
            "访问统计事件",
            "删除超过保留期的原始访问统计事件。",
            367,
            3_650,
            50_000,
            false,
            false
    ),
    ADMIN_SYSTEM_LOG(
            "后台系统日志",
            "删除超过保留期的后台访问和操作日志。",
            1,
            3_650,
            50_000,
            false,
            false
    ),
    CUSTOMER_SERVICE_MESSAGE(
            "客服历史消息",
            "删除超过保留期且已结束会话的客服消息。",
            1,
            3_650,
            10_000,
            false,
            false
    ),
    ORDER_AGGREGATE(
            "订单及关联数据",
            "归档后删除超过保留期的订单、商品快照、支付退款、售后物流、日志及关联附件。",
            1_095,
            3_650,
            100,
            false,
            true
    ),
    STORAGE_ASSET(
            "过期素材",
            "回收已过期、无引用或上传未完成的存储对象。",
            null,
            null,
            1_000,
            true,
            false
    ),
    DIRECT_UPLOAD_SESSION(
            "直传会话",
            "回收腾讯云 COS 直传临时对象和超期会话记录。",
            1,
            365,
            1_000,
            false,
            false
    );

    private final String title;
    private final String description;
    private final Integer minRetentionDays;
    private final Integer maxRetentionDays;
    private final int maxBatchSize;
    private final boolean uploadPendingGraceSupported;
    private final boolean retainReviewsSupported;

    DataCleanupTaskCode(
            String title,
            String description,
            Integer minRetentionDays,
            Integer maxRetentionDays,
            int maxBatchSize,
            boolean uploadPendingGraceSupported,
            boolean retainReviewsSupported
    ) {
        this.title = title;
        this.description = description;
        this.minRetentionDays = minRetentionDays;
        this.maxRetentionDays = maxRetentionDays;
        this.maxBatchSize = maxBatchSize;
        this.uploadPendingGraceSupported = uploadPendingGraceSupported;
        this.retainReviewsSupported = retainReviewsSupported;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Integer minRetentionDays() {
        return minRetentionDays;
    }

    public Integer maxRetentionDays() {
        return maxRetentionDays;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    public boolean retentionRequired() {
        return minRetentionDays != null;
    }

    public boolean uploadPendingGraceSupported() {
        return uploadPendingGraceSupported;
    }

    public boolean retainReviewsSupported() {
        return retainReviewsSupported;
    }
}
