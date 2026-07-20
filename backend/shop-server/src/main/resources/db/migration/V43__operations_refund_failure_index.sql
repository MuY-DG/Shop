CREATE INDEX idx_refund_order_status_failed
    ON refund_order(status, failed_at, id);

CREATE INDEX idx_shop_order_statistics_created
    ON shop_order(created_at, id);

CREATE INDEX idx_user_coupon_status_valid_end
    ON user_coupon(status, valid_end_at, id);

CREATE INDEX idx_after_sale_evidence_after_sale_sort
    ON after_sale_evidence(after_sale_id, sort_order, id);
