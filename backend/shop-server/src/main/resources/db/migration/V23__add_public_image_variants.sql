ALTER TABLE storage_asset
    ADD COLUMN public_image_variants_ready BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE storage_asset
    ADD COLUMN public_image_variants_retry_at DATETIME NULL;

CREATE INDEX idx_storage_public_image_variants
    ON storage_asset (public_image_variants_ready, status, public_image_variants_retry_at, id);

CREATE INDEX idx_storage_asset_public_url ON storage_asset (public_url);
