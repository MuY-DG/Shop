package org.muybaby.shopserver.order;

public enum OrderStatus {
    CREATED,
    PAYING,
    PAID,
    PARTIALLY_SHIPPED,
    SHIPPED,
    COMPLETED,
    CLOSED,
    REFUNDING,
    REFUNDED
}
