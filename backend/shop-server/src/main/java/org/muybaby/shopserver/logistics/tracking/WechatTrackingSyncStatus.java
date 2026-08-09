package org.muybaby.shopserver.logistics.tracking;

public enum WechatTrackingSyncStatus {
    NOT_REQUESTED,
    SYNCING,
    SYNCED,
    UNSUPPORTED,
    FAILED,
    UNKNOWN,
    UNAVAILABLE
}
