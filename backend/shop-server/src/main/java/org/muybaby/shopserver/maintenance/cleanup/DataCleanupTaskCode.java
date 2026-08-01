package org.muybaby.shopserver.maintenance.cleanup;

public enum DataCleanupTaskCode {
    ANALYTICS_EVENT(
            "访问统计事件",
            "删除超过保留期的原始访问统计事件。",
            367,
            3_650,
            50_000,
            false
    ),
    ADMIN_SYSTEM_LOG(
            "后台系统日志",
            "删除超过保留期的后台访问和操作日志。",
            1,
            3_650,
            50_000,
            false
    ),
    CUSTOMER_SERVICE_MESSAGE(
            "客服历史消息",
            "删除超过保留期且已结束会话的客服消息。",
            1,
            3_650,
            10_000,
            false
    ),
    STORAGE_ASSET(
            "过期素材",
            "回收已过期、无引用或上传未完成的存储对象。",
            null,
            null,
            1_000,
            true
    ),
    DIRECT_UPLOAD_SESSION(
            "直传会话",
            "回收腾讯云 COS 直传临时对象和超期会话记录。",
            1,
            365,
            1_000,
            false
    );

    private final String title;
    private final String description;
    private final Integer minRetentionDays;
    private final Integer maxRetentionDays;
    private final int maxBatchSize;
    private final boolean uploadPendingGraceSupported;

    DataCleanupTaskCode(
            String title,
            String description,
            Integer minRetentionDays,
            Integer maxRetentionDays,
            int maxBatchSize,
            boolean uploadPendingGraceSupported
    ) {
        this.title = title;
        this.description = description;
        this.minRetentionDays = minRetentionDays;
        this.maxRetentionDays = maxRetentionDays;
        this.maxBatchSize = maxBatchSize;
        this.uploadPendingGraceSupported = uploadPendingGraceSupported;
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
}
