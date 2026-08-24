-- Shop schema generation 2 baseline.
-- Catalog, content, COS-only assets, and product engagement.

CREATE TABLE app_contact_setting (
  id tinyint NOT NULL,
  phone_number varchar(32) NOT NULL DEFAULT '',
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_app_contact_setting_singleton CHECK ((id = 1))
);

CREATE TABLE freight_template (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(64) NOT NULL,
  charge_mode varchar(20) NOT NULL,
  fixed_amount_cent bigint NOT NULL DEFAULT '0',
  status varchar(20) NOT NULL DEFAULT 'ENABLED',
  sort_order int NOT NULL DEFAULT '0',
  deleted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_freight_template_active_sort (deleted_at,status,sort_order,id)
);

CREATE TABLE home_banner (
  id bigint NOT NULL AUTO_INCREMENT,
  title varchar(128) NOT NULL,
  subtitle varchar(255) NOT NULL DEFAULT '',
  image_file_id bigint DEFAULT NULL,
  image_url varchar(500) NOT NULL DEFAULT '',
  jump_type varchar(20) NOT NULL DEFAULT 'NONE',
  jump_target_id bigint DEFAULT NULL,
  jump_path varchar(255) NOT NULL DEFAULT '',
  status varchar(20) NOT NULL DEFAULT 'DISABLED',
  sort_order int NOT NULL DEFAULT '0',
  start_at timestamp NULL DEFAULT NULL,
  end_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_home_banner_status_sort (status,sort_order)
);

CREATE TABLE home_category_item (
  id bigint NOT NULL AUTO_INCREMENT,
  category_id bigint NOT NULL,
  image_file_id bigint NOT NULL,
  image_url varchar(2048) NOT NULL DEFAULT '',
  sort_order int NOT NULL DEFAULT '0',
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_home_category_item_category UNIQUE (category_id),
  INDEX idx_home_category_item_status_sort (status,sort_order,id),
  CONSTRAINT chk_home_category_item_status CHECK ((status in ('ENABLED','DISABLED')))
);

CREATE TABLE home_product_fill_guard (
  section_type varchar(16) NOT NULL,
  PRIMARY KEY (section_type),
  CONSTRAINT chk_home_product_fill_guard_section CHECK ((section_type in ('HOT','RECOMMENDED')))
);

CREATE TABLE home_product_item (
  id bigint NOT NULL AUTO_INCREMENT,
  section_type varchar(16) NOT NULL,
  spu_id bigint NOT NULL,
  image_file_id bigint DEFAULT NULL,
  image_url varchar(2048) NOT NULL DEFAULT '',
  sort_order int NOT NULL DEFAULT '0',
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_home_product_item_section_spu UNIQUE (section_type,spu_id),
  INDEX idx_home_product_item_section_status_sort (section_type,status,sort_order,id),
  CONSTRAINT chk_home_product_item_section CHECK ((section_type in ('HOT','RECOMMENDED'))),
  CONSTRAINT chk_home_product_item_status CHECK ((status in ('ENABLED','DISABLED')))
);

CREATE TABLE product_category (
  id bigint NOT NULL AUTO_INCREMENT,
  parent_id bigint NOT NULL DEFAULT '0',
  name varchar(64) NOT NULL,
  icon varchar(255) NOT NULL DEFAULT '',
  sort_order int NOT NULL DEFAULT '0',
  status varchar(20) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  icon_file_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_category_parent_name UNIQUE (parent_id,name),
  INDEX idx_product_category_parent_sort (parent_id,sort_order)
);

CREATE TABLE product_category_guard (
  id tinyint NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT chk_product_category_guard_singleton CHECK ((id = 1))
);

CREATE TABLE product_category_parameter (
  category_id bigint NOT NULL,
  parameter_id bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (category_id,parameter_id),
  INDEX idx_product_category_parameter_parameter (parameter_id,category_id)
);

CREATE TABLE product_guarantee_service (
  id bigint NOT NULL AUTO_INCREMENT,
  terms_name varchar(64) NOT NULL,
  content_description varchar(500) NOT NULL DEFAULT '',
  icon varchar(500) NOT NULL DEFAULT '',
  icon_file_id bigint DEFAULT NULL,
  sort_order int NOT NULL DEFAULT '0',
  visible BOOLEAN NOT NULL DEFAULT TRUE,
  deleted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_product_guarantee_active_sort (deleted_at,visible,sort_order,id)
);

CREATE TABLE product_parameter_definition (
  id bigint NOT NULL AUTO_INCREMENT,
  parameter_code varchar(64) NOT NULL,
  parameter_name varchar(64) NOT NULL,
  value_type varchar(24) NOT NULL,
  unit varchar(24) NOT NULL DEFAULT '',
  description varchar(255) NOT NULL DEFAULT '',
  required_value BOOLEAN NOT NULL DEFAULT FALSE,
  filterable BOOLEAN NOT NULL DEFAULT FALSE,
  card_visible BOOLEAN NOT NULL DEFAULT FALSE,
  detail_visible BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order int NOT NULL DEFAULT '0',
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  card_role varchar(16) NOT NULL DEFAULT 'META',
  card_renderer varchar(16) NOT NULL DEFAULT 'TEXT',
  card_priority int NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  CONSTRAINT uk_product_parameter_definition_code UNIQUE (parameter_code),
  INDEX idx_product_parameter_definition_status_sort (status,sort_order,id),
  CONSTRAINT chk_product_parameter_definition_card_priority CHECK ((card_priority >= 0)),
  CONSTRAINT chk_product_parameter_definition_card_renderer CHECK ((card_renderer in ('TEXT','PILL','LEVEL','SPICE'))),
  CONSTRAINT chk_product_parameter_definition_card_role CHECK ((card_role in ('HIGHLIGHT','META'))),
  CONSTRAINT chk_product_parameter_definition_status CHECK ((status in ('ENABLED','DISABLED'))),
  CONSTRAINT chk_product_parameter_definition_type CHECK ((value_type in ('TEXT','NUMBER','SINGLE_SELECT','MULTI_SELECT','BOOLEAN')))
);

CREATE TABLE product_parameter_option (
  id bigint NOT NULL AUTO_INCREMENT,
  parameter_id bigint NOT NULL,
  option_code varchar(64) NOT NULL,
  option_label varchar(64) NOT NULL,
  display_level int DEFAULT NULL,
  sort_order int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_parameter_option_code UNIQUE (parameter_id,option_code),
  INDEX idx_product_parameter_option_parameter_sort (parameter_id,sort_order,id)
);

CREATE TABLE product_review (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL,
  spu_id bigint NOT NULL,
  order_item_id bigint DEFAULT NULL,
  rating int NOT NULL,
  content varchar(1000) NOT NULL DEFAULT '',
  anonymous BOOLEAN NOT NULL DEFAULT FALSE,
  status varchar(20) NOT NULL DEFAULT 'PUBLISHED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  moderated_by_admin_user_id bigint DEFAULT NULL,
  moderated_at timestamp NULL DEFAULT NULL,
  source_order_item_id bigint NOT NULL,
  product_title_snapshot varchar(128) NOT NULL DEFAULT '',
  spec_text_snapshot varchar(255) NOT NULL DEFAULT '',
  verified_purchase BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_review_source_order_item UNIQUE (source_order_item_id),
  CONSTRAINT uk_product_review_order_item UNIQUE (order_item_id),
  INDEX idx_product_review_spu_status_created (spu_id,status,created_at,id),
  INDEX idx_product_review_user_created (user_id,created_at,id),
  INDEX idx_product_review_status_rating_created (status,rating,created_at,id)
);

CREATE TABLE product_sku (
  id bigint NOT NULL AUTO_INCREMENT,
  spu_id bigint NOT NULL,
  sku_code varchar(64) NOT NULL,
  spec_json text NOT NULL,
  spec_text varchar(255) NOT NULL,
  price_cent bigint NOT NULL,
  original_price_cent bigint NOT NULL DEFAULT '0',
  stock_available int NOT NULL DEFAULT '0',
  weight_gram int DEFAULT NULL,
  image varchar(500) NOT NULL DEFAULT '',
  status varchar(20) NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  image_file_id bigint DEFAULT NULL,
  cost_price_cent bigint DEFAULT NULL,
  volume_cubic_meter decimal(12,6) DEFAULT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  combination_key varchar(512) NOT NULL,
  deleted_at timestamp NULL DEFAULT NULL,
  low_stock_threshold int NOT NULL DEFAULT '10',
  net_content_text varchar(64) NOT NULL DEFAULT '',
  pack_unit_text varchar(24) DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_sku_code UNIQUE (sku_code),
  CONSTRAINT uk_product_sku_spu_combination UNIQUE (spu_id,combination_key),
  CONSTRAINT chk_product_sku_combination_key_nonempty CHECK (char_length(combination_key) > 0),
  INDEX idx_product_sku_spu_status_sort (spu_id,status,sort_order),
  INDEX idx_product_sku_spu_deleted_status_sort (spu_id,deleted_at,status,sort_order,id),
  INDEX idx_product_sku_statistics_stock (status,deleted_at,stock_available,spu_id),
  INDEX idx_product_sku_low_stock (deleted_at,status,stock_available,low_stock_threshold,id)
);

CREATE TABLE product_sku_spec_value (
  sku_id bigint NOT NULL,
  spec_value_id bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (sku_id,spec_value_id),
  INDEX idx_product_sku_spec_value_value (spec_value_id,sku_id)
);

CREATE TABLE product_spec_template (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(64) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_spec_template_name UNIQUE (name)
);

CREATE TABLE product_spec_template_group (
  id bigint NOT NULL AUTO_INCREMENT,
  template_id bigint NOT NULL,
  group_key varchar(64) NOT NULL,
  name varchar(30) NOT NULL,
  image_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order int NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  CONSTRAINT uk_product_spec_template_group_key UNIQUE (template_id,group_key),
  INDEX idx_product_spec_template_group_template_sort (template_id,sort_order,id)
);

CREATE TABLE product_spec_template_value (
  id bigint NOT NULL AUTO_INCREMENT,
  group_id bigint NOT NULL,
  value_key varchar(64) NOT NULL,
  value_name varchar(64) NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  CONSTRAINT uk_product_spec_template_value_key UNIQUE (group_id,value_key),
  INDEX idx_product_spec_template_value_group_sort (group_id,sort_order,id)
);

CREATE TABLE product_spu (
  id bigint NOT NULL AUTO_INCREMENT,
  category_id bigint NOT NULL,
  title varchar(128) NOT NULL,
  subtitle varchar(255) NOT NULL DEFAULT '',
  main_image varchar(500) NOT NULL DEFAULT '',
  selling_points text NOT NULL,
  detail_html text NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  status varchar(20) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  main_image_file_id bigint DEFAULT NULL,
  spec_type varchar(20) NOT NULL DEFAULT 'SINGLE',
  main_video varchar(500) NOT NULL DEFAULT '',
  main_video_file_id bigint DEFAULT NULL,
  freight_template_id bigint NOT NULL DEFAULT '1',
  virtual_sales bigint NOT NULL DEFAULT '0',
  deleted_at timestamp NULL DEFAULT NULL,
  purged_at timestamp NULL DEFAULT NULL,
  display_badge_text varchar(24) NOT NULL DEFAULT '',
  display_badge_tone varchar(16) NOT NULL DEFAULT 'NEUTRAL',
  compliance_type varchar(20) NOT NULL DEFAULT 'UNCLASSIFIED',
  PRIMARY KEY (id),
  INDEX idx_product_spu_category_status_sort (category_id,status,sort_order),
  INDEX idx_product_spu_status_sort (status,sort_order),
  INDEX idx_product_spu_active_status_sort (deleted_at,status,sort_order,id),
  INDEX idx_product_spu_freight_template (freight_template_id),
  INDEX idx_product_spu_recycle_bin (purged_at,deleted_at,id),
  CONSTRAINT chk_product_spu_compliance_type CHECK ((compliance_type in ('UNCLASSIFIED','FOOD','NON_FOOD'))),
  CONSTRAINT chk_product_spu_display_badge_tone CHECK ((display_badge_tone in ('RED','ORANGE','GREEN','NEUTRAL')))
);

CREATE TABLE product_spu_coupon (
  spu_id bigint NOT NULL,
  coupon_template_id bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (spu_id,coupon_template_id),
  INDEX idx_product_spu_coupon_template (coupon_template_id,spu_id)
);

CREATE TABLE product_spu_guarantee_service (
  spu_id bigint NOT NULL,
  service_id bigint NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (spu_id,service_id),
  INDEX idx_product_spu_guarantee_service_service (service_id,spu_id)
);

CREATE TABLE product_spu_image (
  id bigint NOT NULL AUTO_INCREMENT,
  spu_id bigint NOT NULL,
  url varchar(500) NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  file_id bigint DEFAULT NULL,
  PRIMARY KEY (id),
  INDEX idx_product_spu_image_spu_sort (spu_id,sort_order)
);

CREATE TABLE product_spu_parameter_value (
  spu_id bigint NOT NULL,
  parameter_id bigint NOT NULL,
  text_value varchar(500) DEFAULT NULL,
  number_value decimal(20,6) DEFAULT NULL,
  boolean_value BOOLEAN DEFAULT NULL,
  option_codes_json text NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (spu_id,parameter_id),
  INDEX idx_product_spu_parameter_value_parameter (parameter_id,spu_id)
);

CREATE TABLE product_spu_spec_group (
  id bigint NOT NULL AUTO_INCREMENT,
  spu_id bigint NOT NULL,
  group_key varchar(64) NOT NULL,
  name varchar(30) NOT NULL,
  image_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order int NOT NULL DEFAULT '0',
  deleted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_spu_spec_group_key UNIQUE (spu_id,group_key),
  INDEX idx_product_spu_spec_group_spu_deleted_sort (spu_id,deleted_at,sort_order,id)
);

CREATE TABLE product_spu_spec_value (
  id bigint NOT NULL AUTO_INCREMENT,
  group_id bigint NOT NULL,
  value_key varchar(64) NOT NULL,
  value_name varchar(64) NOT NULL,
  image varchar(500) NOT NULL DEFAULT '',
  image_file_id bigint DEFAULT NULL,
  sort_order int NOT NULL DEFAULT '0',
  deleted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_spu_spec_value_key UNIQUE (group_id,value_key),
  INDEX idx_product_spu_spec_value_group_deleted_sort (group_id,deleted_at,sort_order,id)
);

CREATE TABLE storage_asset_folder (
  id bigint NOT NULL AUTO_INCREMENT,
  parent_id bigint DEFAULT NULL,
  parent_key bigint GENERATED ALWAYS AS (coalesce(parent_id,0)),
  name varchar(64) NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  status varchar(20) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_storage_asset_folder_parent_name UNIQUE (parent_key,name),
  INDEX idx_storage_asset_folder_parent_status_sort (parent_id,status,sort_order,id),
  CONSTRAINT fk_storage_asset_folder_parent FOREIGN KEY (parent_id) REFERENCES storage_asset_folder (id) ON DELETE RESTRICT
);

CREATE TABLE storage_asset_folder_guard (
  id tinyint NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT chk_storage_asset_folder_guard_singleton CHECK ((id = 1))
);

CREATE TABLE storage_runtime_setting (
  id bigint NOT NULL,
  cos_region varchar(64) NOT NULL DEFAULT '',
  cos_bucket varchar(128) NOT NULL DEFAULT '',
  cos_secret_id_ciphertext varchar(1000) NOT NULL DEFAULT '',
  cos_secret_key_ciphertext varchar(1000) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  secret_cipher_version smallint NOT NULL DEFAULT 2,
  secret_key_id varchar(64) NOT NULL DEFAULT '',
  secret_revision bigint NOT NULL DEFAULT '0',
  secret_reencrypted_at timestamp NULL DEFAULT NULL,
  cos_public_base_url varchar(500) NOT NULL DEFAULT '',
  cos_custom_domain_verification_fingerprint varchar(64) DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT chk_storage_runtime_cipher_v2 CHECK (secret_cipher_version = 2 AND secret_key_id <> '')
);

CREATE TABLE storage_upload_principal_guard (
  principal_kind varchar(16) NOT NULL,
  principal_id bigint NOT NULL,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (principal_kind,principal_id),
  CONSTRAINT chk_storage_upload_guard_principal_kind CHECK ((principal_kind in ('ADMIN','APP')))
);

CREATE TABLE user_product_browse_history (
  user_id bigint NOT NULL,
  spu_id bigint NOT NULL,
  first_viewed_at timestamp NOT NULL,
  last_viewed_at timestamp NOT NULL,
  view_count bigint NOT NULL DEFAULT '1',
  PRIMARY KEY (user_id,spu_id),
  INDEX idx_user_product_history_user_last_viewed (user_id,last_viewed_at,spu_id)
);

CREATE TABLE user_product_favorite (
  user_id bigint NOT NULL,
  spu_id bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id,spu_id),
  INDEX idx_user_product_favorite_user_created (user_id,created_at,spu_id)
);

CREATE TABLE product_sku_wholesale_tier (
  id bigint NOT NULL AUTO_INCREMENT,
  sku_id bigint NOT NULL,
  min_quantity int NOT NULL,
  unit_price_cent bigint NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_sku_wholesale_tier_quantity UNIQUE (sku_id,min_quantity),
  INDEX idx_sku_wholesale_tier_lookup (sku_id,min_quantity),
  CONSTRAINT fk_sku_wholesale_tier_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id) ON DELETE CASCADE
);

CREATE TABLE product_food_disclosure (
  spu_id bigint NOT NULL,
  food_name varchar(160) NOT NULL DEFAULT '',
  ingredients text NOT NULL,
  allergen_information varchar(1000) NOT NULL DEFAULT '',
  storage_conditions varchar(500) NOT NULL DEFAULT '',
  shelf_life_description varchar(255) NOT NULL DEFAULT '',
  manufacturer_name varchar(160) NOT NULL DEFAULT '',
  manufacturer_address varchar(512) NOT NULL DEFAULT '',
  production_license_number varchar(96) NOT NULL DEFAULT '',
  origin varchar(160) NOT NULL DEFAULT '',
  consumer_notice varchar(1000) NOT NULL DEFAULT '',
  variable_production_notice varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (spu_id),
  CONSTRAINT fk_product_food_disclosure_spu FOREIGN KEY (spu_id) REFERENCES product_spu (id)
);

CREATE TABLE storage_asset (
  id bigint NOT NULL AUTO_INCREMENT,
  scope varchar(20) NOT NULL,
  media_kind varchar(20) NOT NULL,
  folder_id bigint DEFAULT NULL,
  visibility varchar(20) NOT NULL,
  provider varchar(20) NOT NULL,
  storage_container varchar(500) NOT NULL DEFAULT '',
  storage_region varchar(64) NOT NULL DEFAULT '',
  object_key varchar(255) NOT NULL,
  original_filename varchar(255) NOT NULL,
  content_type varchar(128) NOT NULL,
  extension varchar(20) NOT NULL,
  size_bytes bigint NOT NULL,
  sha256 varchar(64) NOT NULL DEFAULT '',
  width int DEFAULT NULL,
  height int DEFAULT NULL,
  duration_seconds int DEFAULT NULL,
  alt_text varchar(255) NOT NULL DEFAULT '',
  tags_json text,
  public_url varchar(500) DEFAULT NULL,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  uploaded_by_type varchar(20) NOT NULL,
  uploaded_by_id bigint NOT NULL,
  upload_context_type varchar(40) DEFAULT NULL,
  upload_context_id bigint DEFAULT NULL,
  expires_at timestamp NULL DEFAULT NULL,
  cleanup_attempts int NOT NULL DEFAULT '0',
  cleanup_next_retry_at timestamp NULL DEFAULT NULL,
  cleanup_lease_token varchar(36) DEFAULT NULL,
  deleted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  thumbnail_status varchar(20) NOT NULL DEFAULT 'NONE',
  thumbnail_object_key varchar(255) DEFAULT NULL,
  thumbnail_content_type varchar(128) DEFAULT NULL,
  thumbnail_size_bytes bigint DEFAULT NULL,
  thumbnail_sha256 varchar(64) DEFAULT NULL,
  thumbnail_width int DEFAULT NULL,
  thumbnail_height int DEFAULT NULL,
  thumbnail_attempts int NOT NULL DEFAULT '0',
  thumbnail_started_at timestamp NULL DEFAULT NULL,
  thumbnail_next_retry_at timestamp NULL DEFAULT NULL,
  object_etag varchar(128) DEFAULT NULL,
  thumbnail_object_etag varchar(128) DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_storage_asset_object_key UNIQUE (object_key),
  INDEX idx_storage_asset_scope_status_created (scope,status,created_at,id),
  INDEX idx_storage_asset_scope_kind_status_created (scope,media_kind,status,created_at,id),
  INDEX idx_storage_asset_folder_status_created (folder_id,status,created_at,id),
  INDEX idx_storage_asset_upload_context (scope,upload_context_type,upload_context_id,status),
  INDEX idx_storage_asset_expiry (scope,status,expires_at,id),
  INDEX idx_storage_asset_cleanup_retry (scope,status,cleanup_next_retry_at,id),
  INDEX idx_storage_asset_thumbnail_work (upload_context_type,thumbnail_status,thumbnail_next_retry_at,id),
  CONSTRAINT fk_storage_asset_folder FOREIGN KEY (folder_id) REFERENCES storage_asset_folder (id) ON DELETE RESTRICT,
  CONSTRAINT chk_storage_asset_cos_only CHECK ((provider = 'TENCENT_COS')),
  CONSTRAINT chk_storage_asset_folder_scope CHECK (((folder_id is null) or (scope = 'LIBRARY'))),
  CONSTRAINT chk_storage_asset_media_kind CHECK ((media_kind in ('IMAGE','VIDEO','DOCUMENT'))),
  CONSTRAINT chk_storage_asset_provider CHECK ((provider in ('LOCAL','TENCENT_COS'))),
  CONSTRAINT chk_storage_asset_scope CHECK ((scope in ('LIBRARY','ATTACHMENT','SECRET'))),
  CONSTRAINT chk_storage_asset_scope_visibility CHECK ((((scope = 'LIBRARY') and (visibility = 'PUBLIC')) or ((scope in ('ATTACHMENT','SECRET')) and (visibility = 'PRIVATE')))),
  CONSTRAINT chk_storage_asset_thumbnail_status CHECK ((thumbnail_status in ('NONE','PENDING','PROCESSING','READY','FAILED','UNAVAILABLE'))),
  CONSTRAINT chk_storage_asset_upload_context CHECK ((((upload_context_type is null) and (upload_context_id is null)) or ((upload_context_type is not null) and (upload_context_id is not null)))),
  CONSTRAINT chk_storage_asset_visibility CHECK ((visibility in ('PUBLIC','PRIVATE')))
);

CREATE TABLE product_food_disclosure_label (
  id bigint NOT NULL AUTO_INCREMENT,
  spu_id bigint NOT NULL,
  file_id bigint NOT NULL,
  url varchar(500) NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_food_label_file UNIQUE (spu_id,file_id),
  INDEX fk_product_food_label_asset (file_id),
  INDEX idx_product_food_label_spu_sort (spu_id,sort_order,id),
  CONSTRAINT fk_product_food_label_asset FOREIGN KEY (file_id) REFERENCES storage_asset (id),
  CONSTRAINT fk_product_food_label_disclosure FOREIGN KEY (spu_id) REFERENCES product_food_disclosure (spu_id),
  CONSTRAINT chk_product_food_label_sort CHECK ((sort_order >= 0))
);

CREATE TABLE product_review_image (
  id bigint NOT NULL AUTO_INCREMENT,
  review_id bigint NOT NULL,
  asset_id bigint NOT NULL,
  image_url varchar(500) NOT NULL,
  sort_order int NOT NULL DEFAULT '0',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_product_review_image_asset UNIQUE (asset_id),
  CONSTRAINT uk_product_review_image_sort UNIQUE (review_id,sort_order),
  INDEX idx_product_review_image_review (review_id,sort_order,id),
  CONSTRAINT fk_product_review_image_asset FOREIGN KEY (asset_id) REFERENCES storage_asset (id) ON DELETE CASCADE,
  CONSTRAINT fk_product_review_image_review FOREIGN KEY (review_id) REFERENCES product_review (id) ON DELETE CASCADE
);

CREATE TABLE storage_asset_usage (
  id bigint NOT NULL AUTO_INCREMENT,
  asset_id bigint NOT NULL,
  usage_type varchar(40) NOT NULL,
  owner_type varchar(40) NOT NULL,
  owner_id bigint NOT NULL,
  owner_label varchar(255) NOT NULL DEFAULT '',
  snapshot_url varchar(500) NOT NULL DEFAULT '',
  sort_order int NOT NULL DEFAULT '0',
  protected BOOLEAN NOT NULL DEFAULT FALSE,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_storage_asset_usage_asset_status (asset_id,status,id),
  INDEX idx_storage_asset_usage_owner_status (owner_type,owner_id,status,id),
  CONSTRAINT fk_storage_asset_usage_asset FOREIGN KEY (asset_id) REFERENCES storage_asset (id) ON DELETE RESTRICT
);

CREATE TABLE storage_upload_session (
  id varchar(36) NOT NULL,
  profile varchar(40) NOT NULL,
  principal_kind varchar(16) NOT NULL,
  principal_id bigint NOT NULL,
  folder_id bigint DEFAULT NULL,
  upload_context_type varchar(40) DEFAULT NULL,
  upload_context_id bigint DEFAULT NULL,
  original_filename varchar(255) NOT NULL,
  source_content_type varchar(128) NOT NULL,
  expected_size_bytes bigint NOT NULL,
  provider varchar(20) NOT NULL,
  storage_container varchar(500) NOT NULL,
  storage_region varchar(64) NOT NULL,
  public_base_url varchar(500) NOT NULL DEFAULT '',
  staging_object_key varchar(255) NOT NULL,
  final_object_key varchar(255) NOT NULL,
  thumbnail_object_key varchar(255) DEFAULT NULL,
  status varchar(20) NOT NULL DEFAULT 'INITIATED',
  asset_id bigint DEFAULT NULL,
  business_status varchar(20) NOT NULL DEFAULT 'NONE',
  business_result_id bigint DEFAULT NULL,
  expires_at timestamp NOT NULL,
  processing_started_at timestamp NULL DEFAULT NULL,
  processing_token varchar(36) DEFAULT NULL,
  processing_attempts int NOT NULL DEFAULT '0',
  next_processing_attempt_at timestamp NULL DEFAULT NULL,
  completed_at timestamp NULL DEFAULT NULL,
  failure_code varchar(64) DEFAULT NULL,
  staging_deleted_at timestamp NULL DEFAULT NULL,
  outputs_deleted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_storage_upload_session_staging_key UNIQUE (staging_object_key),
  CONSTRAINT uk_storage_upload_session_final_key UNIQUE (final_object_key),
  INDEX fk_storage_upload_session_asset (asset_id),
  INDEX fk_storage_upload_session_folder (folder_id),
  INDEX idx_storage_upload_session_owner (principal_kind,principal_id,status,created_at),
  INDEX idx_storage_upload_session_expiry (status,expires_at),
  INDEX idx_storage_upload_session_retention (status,updated_at),
  CONSTRAINT fk_storage_upload_session_asset FOREIGN KEY (asset_id) REFERENCES storage_asset (id) ON DELETE SET NULL,
  CONSTRAINT fk_storage_upload_session_folder FOREIGN KEY (folder_id) REFERENCES storage_asset_folder (id) ON DELETE SET NULL,
  CONSTRAINT chk_storage_upload_session_business_status CHECK ((business_status in ('NONE','COMPLETED'))),
  CONSTRAINT chk_storage_upload_session_context CHECK ((((upload_context_type is null) and (upload_context_id is null)) or ((upload_context_type is not null) and (upload_context_id is not null)))),
  CONSTRAINT chk_storage_upload_session_principal_kind CHECK ((principal_kind in ('ADMIN','APP'))),
  CONSTRAINT chk_storage_upload_session_processing_attempts CHECK (((processing_attempts >= 0) and (processing_attempts <= 3))),
  CONSTRAINT chk_storage_upload_session_provider CHECK ((provider = 'TENCENT_COS')),
  CONSTRAINT chk_storage_upload_session_size CHECK ((expected_size_bytes > 0)),
  CONSTRAINT chk_storage_upload_session_status CHECK ((status in ('INITIATED','PROCESSING','COMPLETED','FAILED','EXPIRED')))
);
