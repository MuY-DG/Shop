ALTER TABLE order_shipment
    ADD COLUMN sender_address VARCHAR(768) NOT NULL DEFAULT '';

-- Only electronic waybills have a historical sender snapshot. Do not backfill
-- manual shipments from today's settings and misrepresent their original address.
UPDATE order_shipment
SET sender_address = COALESCE((
    SELECT TRIM(CONCAT_WS(' ', NULLIF(w.sender_province, ''),
        NULLIF(w.sender_city, ''), NULLIF(w.sender_district, ''),
        NULLIF(w.sender_detail_address, '')))
    FROM order_electronic_waybill w
    WHERE w.id = order_shipment.electronic_waybill_id
), '')
WHERE electronic_waybill_id IS NOT NULL;
