package org.muybaby.shopserver.order.dto;

public record AdminOrderStatusCountsResponse(
        Long all,
        Long unpaid,
        Long toShip,
        Long toReceive,
        Long completed,
        Long closed,
        Long refunding,
        Long refunded
) {
}
