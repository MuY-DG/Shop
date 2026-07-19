package org.muybaby.shopserver.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HomeBannerControllerTest {

    private static final AtomicLong LIMITED_ADMIN_IDS = new AtomicLong(9_930_000L);

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void clearTables() {
        clearLimitedAdmins();
        jdbcClient.sql("delete from home_banner").update();
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from storage_asset").update();
    }

    @AfterEach
    void clearLimitedAdminState() {
        clearLimitedAdmins();
    }

    @Test
    void adminCrudListEnableDisableAndUsageSnapshotsWork() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        UploadedFile uploadedFile = uploadBannerFile(adminToken, "banner-home-main.png");
        insertProductTarget(101L, "ON_SALE");

        String createResponse = mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"首页热卖","subtitle":"夏季新品","imageFileId":%d,
                                 "jumpType":"PRODUCT","jumpTargetId":101,"jumpPath":"   ",
                                 "status":"DISABLED","sortOrder":20,
                                 "startAt":"2026-07-01T00:00:00","endAt":"2026-08-01T00:00:00"}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long bannerId = objectMapper.readTree(createResponse).path("data").asLong();

        BannerRow created = findBannerRow(bannerId);
        assertThat(created.imageFileId()).isEqualTo(uploadedFile.id());
        assertThat(created.imageUrl()).isEqualTo(uploadedFile.url());
        assertThat(created.jumpType()).isEqualTo("PRODUCT");
        assertThat(created.jumpTargetId()).isEqualTo(101L);
        assertThat(created.jumpPath()).isEmpty();
        assertThat(created.status()).isEqualTo("DISABLED");

        UsageRow createdUsage = findActiveUsage(bannerId);
        assertThat(createdUsage.assetId()).isEqualTo(uploadedFile.id());
        assertThat(createdUsage.usageType()).isEqualTo("HOME_BANNER");
        assertThat(createdUsage.ownerType()).isEqualTo("HOME_BANNER");
        assertThat(createdUsage.ownerLabel()).isEqualTo("首页热卖");
        assertThat(createdUsage.snapshotUrl()).isEqualTo(uploadedFile.url());
        assertThat(createdUsage.sortOrder()).isEqualTo(20);

        jdbcClient.sql("""
                        update storage_asset
                        set public_url = 'http://localhost:8080/files/public/mutated/banner-home-main.png'
                        where id = :fileId
                        """)
                .param("fileId", uploadedFile.id())
                .update();

        mockMvc.perform(put("/admin/home/banners/{bannerId}", bannerId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"首页热卖更新","subtitle":"夏季新品更新","imageFileId":%d,
                                 "jumpType":"APP_PATH","jumpTargetId":999,"jumpPath":" /pages/product/list/list?categoryId=8 ",
                                 "status":"ENABLED","sortOrder":5,
                                 "startAt":"2026-07-02T00:00:00","endAt":"2026-08-02T00:00:00"}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        BannerRow updated = findBannerRow(bannerId);
        assertThat(updated.imageUrl()).isEqualTo(uploadedFile.url());
        assertThat(updated.jumpType()).isEqualTo("APP_PATH");
        assertThat(updated.jumpTargetId()).isNull();
        assertThat(updated.jumpPath()).isEqualTo("/pages/product/list/list?categoryId=8");
        assertThat(updated.status()).isEqualTo("ENABLED");
        assertThat(updated.sortOrder()).isEqualTo(5);

        UsageRow updatedUsage = findActiveUsage(bannerId);
        assertThat(updatedUsage.ownerLabel()).isEqualTo("首页热卖更新");
        assertThat(updatedUsage.snapshotUrl()).isEqualTo(uploadedFile.url());
        assertThat(updatedUsage.sortOrder()).isEqualTo(5);
        assertThat(totalUsageCount(bannerId)).isEqualTo(1);
        assertThat(removedUsageCount(bannerId)).isZero();

        mockMvc.perform(get("/admin/home/banners")
                        .param("current", "1")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].id").value(bannerId))
                .andExpect(jsonPath("$.data.records[0].title").value("首页热卖更新"))
                .andExpect(jsonPath("$.data.records[0].imageFileId").value(uploadedFile.id()))
                .andExpect(jsonPath("$.data.records[0].imageUrl").value(uploadedFile.url()))
                .andExpect(jsonPath("$.data.records[0].jumpType").value("APP_PATH"))
                .andExpect(jsonPath("$.data.records[0].jumpPath").value("/pages/product/list/list?categoryId=8"))
                .andExpect(jsonPath("$.data.records[0].status").value("ENABLED"));

        mockMvc.perform(post("/admin/home/banners/{bannerId}/disable", bannerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(findBannerRow(bannerId).status()).isEqualTo("DISABLED");
        assertThat(activeUsageCount(bannerId)).isEqualTo(1);

        mockMvc.perform(post("/admin/home/banners/{bannerId}/enable", bannerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(findBannerRow(bannerId).status()).isEqualTo("ENABLED");
        assertThat(activeUsageCount(bannerId)).isEqualTo(1);
    }

    @Test
    void adminBannerApisRequireSpecificAuthorities() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        UploadedFile uploadedFile = uploadBannerFile(adminToken, "banner-auth.png");
        String readToken = limitedAdminToken(List.of("content:banner:read"));
        String createToken = limitedAdminToken(List.of("content:banner:create"));
        String updateToken = limitedAdminToken(List.of("content:banner:update"));
        String publishToken = limitedAdminToken(List.of("content:banner:publish"));

        mockMvc.perform(get("/admin/home/banners")
                        .header("Authorization", "Bearer " + createToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(get("/admin/home/banners")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk());

        String requestBody = """
                {"title":"权限轮播","subtitle":"","imageFileId":%d,
                 "jumpType":"NONE","status":"DISABLED","sortOrder":1}
                """.formatted(uploadedFile.id());

        mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        String createResponse = mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long bannerId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(put("/admin/home/banners/{bannerId}", bannerId)
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody.replace("权限轮播", "权限轮播更新")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(put("/admin/home/banners/{bannerId}", bannerId)
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody.replace("权限轮播", "权限轮播更新")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/home/banners/{bannerId}/enable", bannerId)
                        .header("Authorization", "Bearer " + updateToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(post("/admin/home/banners/{bannerId}/enable", bannerId)
                        .header("Authorization", "Bearer " + publishToken))
                .andExpect(status().isOk());
    }

    @Test
    void jumpValidationNormalizesNoneAndSupportsCoupon() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        UploadedFile uploadedFile = uploadBannerFile(adminToken, "banner-jump-rules.png");

        mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"缺少商品目标","subtitle":"","imageFileId":%d,
                                 "jumpType":"PRODUCT","status":"DISABLED","sortOrder":1}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"空白 URL","subtitle":"","imageFileId":%d,
                                 "jumpType":"URL","jumpPath":"   ","status":"DISABLED","sortOrder":2}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        String noneResponse = mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"无跳转","subtitle":"","imageFileId":%d,
                                 "jumpType":"NONE","jumpTargetId":88,"jumpPath":"/pages/ignored",
                                 "status":"DISABLED","sortOrder":3}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long noneBannerId = objectMapper.readTree(noneResponse).path("data").asLong();
        BannerRow noneBanner = findBannerRow(noneBannerId);
        assertThat(noneBanner.jumpType()).isEqualTo("NONE");
        assertThat(noneBanner.jumpTargetId()).isNull();
        assertThat(noneBanner.jumpPath()).isEmpty();

        String couponResponse = mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"领券入口","subtitle":"新人专享","imageFileId":%d,
                                 "jumpType":"COUPON","jumpTargetId":66,"jumpPath":"   ",
                                 "status":"ENABLED","sortOrder":4}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long couponBannerId = objectMapper.readTree(couponResponse).path("data").asLong();
        BannerRow couponBanner = findBannerRow(couponBannerId);
        assertThat(couponBanner.jumpType()).isEqualTo("COUPON");
        assertThat(couponBanner.jumpTargetId()).isEqualTo(66L);
        assertThat(couponBanner.jumpPath()).isEmpty();
    }

    @Test
    void titleAndSubtitleAreOptional() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        UploadedFile uploadedFile = uploadBannerFile(adminToken, "banner-without-copy.png");

        mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageFileId":%d,"jumpType":"NONE",
                                 "status":"DISABLED","sortOrder":0}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber());

        mockMvc.perform(get("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value(""))
                .andExpect(jsonPath("$.data.records[0].subtitle").value(""));
    }

    @Test
    void appFeedIsPublicAndReturnsOnlyEnabledCurrentBannersInDeterministicOrder() throws Exception {
        insertProductTarget(201L, "ON_SALE");
        UploadedFile slowBanner = insertPublicStorageFile("banner-slow.png", "http://localhost:8080/files/public/banner-slow.png");
        UploadedFile oldTopBanner = insertPublicStorageFile("banner-old-top.png", "http://localhost:8080/files/public/banner-old-top.png");
        UploadedFile newTopBanner = insertPublicStorageFile("banner-new-top.png", "http://localhost:8080/files/public/banner-new-top.png");
        UploadedFile disabledBanner = insertPublicStorageFile("banner-disabled.png", "http://localhost:8080/files/public/banner-disabled.png");
        UploadedFile futureBanner = insertPublicStorageFile("banner-future.png", "http://localhost:8080/files/public/banner-future.png");
        UploadedFile expiredBanner = insertPublicStorageFile("banner-expired.png", "http://localhost:8080/files/public/banner-expired.png");

        LocalDateTime now = LocalDateTime.now();
        insertBanner("排序较后", slowBanner.id(), slowBanner.url(), "NONE", null, "", "ENABLED", 2, now.minusDays(1), now.plusDays(1));
        insertBanner("同序旧图", oldTopBanner.id(), oldTopBanner.url(), "PRODUCT", 201L, "", "ENABLED", 1, now.minusDays(1), now.plusDays(1));
        insertBanner("同序新图", newTopBanner.id(), newTopBanner.url(), "APP_PATH", null, "/pages/promo/index", "ENABLED", 1, now.minusDays(1), now.plusDays(1));
        insertBanner("已禁用", disabledBanner.id(), disabledBanner.url(), "NONE", null, "", "DISABLED", 0, now.minusDays(1), now.plusDays(1));
        insertBanner("未开始", futureBanner.id(), futureBanner.url(), "NONE", null, "", "ENABLED", 0, now.plusDays(1), now.plusDays(2));
        insertBanner("已结束", expiredBanner.id(), expiredBanner.url(), "NONE", null, "", "ENABLED", 0, now.minusDays(3), now.minusDays(1));

        mockMvc.perform(get("/app/home/banners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].title", contains("同序新图", "同序旧图", "排序较后")))
                .andExpect(jsonPath("$.data[*].title", not(hasItem("已禁用"))))
                .andExpect(jsonPath("$.data[*].title", not(hasItem("未开始"))))
                .andExpect(jsonPath("$.data[*].title", not(hasItem("已结束"))))
                .andExpect(jsonPath("$.data[0].imageUrl").value(newTopBanner.url()))
                .andExpect(jsonPath("$.data[0].jumpType").value("APP_PATH"))
                .andExpect(jsonPath("$.data[1].jumpType").value("PRODUCT"))
                .andExpect(jsonPath("$.data[2].jumpType").value("NONE"));
    }

    @Test
    void recycledProductTargetsCannotBeCreatedOrEnabledAndAreHiddenFromAppFeed() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        UploadedFile uploadedFile = uploadBannerFile(adminToken, "banner-recycled-product.png");
        insertProductTarget(301L, "ON_SALE");

        String createResponse = mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"回收商品入口","subtitle":"","imageFileId":%d,
                                 "jumpType":"PRODUCT","jumpTargetId":301,
                                 "status":"DISABLED","sortOrder":1}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long bannerId = objectMapper.readTree(createResponse).path("data").asLong();

        jdbcClient.sql("""
                        update product_spu
                        set status = 'OFF_SALE', deleted_at = current_timestamp
                        where id = 301
                        """)
                .update();
        jdbcClient.sql("update home_banner set status = 'ENABLED' where id = :bannerId")
                .param("bannerId", bannerId)
                .update();

        mockMvc.perform(get("/app/home/banners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].title", not(hasItem("回收商品入口"))));
        mockMvc.perform(post("/admin/home/banners/{bannerId}/enable", bannerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_UNAVAILABLE.code()));
        mockMvc.perform(post("/admin/home/banners")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"新的回收商品入口","subtitle":"","imageFileId":%d,
                                 "jumpType":"PRODUCT","jumpTargetId":301,
                                 "status":"DISABLED","sortOrder":2}
                                """.formatted(uploadedFile.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_UNAVAILABLE.code()));
    }

    private UploadedFile uploadBannerFile(String adminToken, String filename) throws Exception {
        String response = mockMvc.perform(multipart("/admin/assets/upload")
                        .file(new MockMultipartFile("file", filename, "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new UploadedFile(data.path("id").asLong(), data.path("url").asText());
    }

    private UploadedFile insertPublicStorageFile(String originalFilename, String publicUrl) {
        String objectKey = "public/test/banner/" + System.nanoTime() + ".png";
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('LIBRARY', 'IMAGE', null, 'PUBLIC', 'LOCAL', '', :objectKey, :originalFilename,
                             'image/png', 'png', 68, 'abc123', 1, 1, '', null,
                             :publicUrl, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .param("originalFilename", originalFilename)
                .param("publicUrl", publicUrl)
                .update();
        Long fileId = jdbcClient.sql("""
                        select id
                        from storage_asset
                        where object_key = :objectKey
                        """)
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new UploadedFile(fileId, publicUrl);
    }

    private void insertProductTarget(long spuId, String status) {
        Integer existingCount = jdbcClient.sql("select count(*) from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        if (existingCount != null && existingCount > 0) {
            jdbcClient.sql("""
                            update product_spu
                            set status = :status, deleted_at = null, purged_at = null
                            where id = :spuId
                            """)
                    .param("status", status)
                    .param("spuId", spuId)
                    .update();
            return;
        }
        String categoryName = "Banner target category " + spuId;
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 0, 'ENABLED')
                        """)
                .param("name", categoryName)
                .update();
        Long categoryId = jdbcClient.sql("select id from product_category where name = :name")
                .param("name", categoryName)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, subtitle, main_image, selling_points, detail_html,
                             sort_order, status, spec_type, freight_template_id, virtual_sales)
                        values
                            (:spuId, :categoryId, :title, '', '', '', '', 0, :status, 'SINGLE', 1, 0)
                        """)
                .param("spuId", spuId)
                .param("categoryId", categoryId)
                .param("title", "Banner target product " + spuId)
                .param("status", status)
                .update();
    }

    private void insertBanner(
            String title,
            Long imageFileId,
            String imageUrl,
            String jumpType,
            Long jumpTargetId,
            String jumpPath,
            String status,
            int sortOrder,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        jdbcClient.sql("""
                        insert into home_banner
                            (title, subtitle, image_file_id, image_url, jump_type, jump_target_id, jump_path,
                             status, sort_order, start_at, end_at)
                        values
                            (:title, '', :imageFileId, :imageUrl, :jumpType, :jumpTargetId, :jumpPath,
                             :status, :sortOrder, :startAt, :endAt)
                        """)
                .param("title", title)
                .param("imageFileId", imageFileId)
                .param("imageUrl", imageUrl)
                .param("jumpType", jumpType)
                .param("jumpTargetId", jumpTargetId)
                .param("jumpPath", jumpPath)
                .param("status", status)
                .param("sortOrder", sortOrder)
                .param("startAt", startAt)
                .param("endAt", endAt)
                .update();
    }

    private BannerRow findBannerRow(long bannerId) {
        return jdbcClient.sql("""
                        select id, image_file_id, image_url, jump_type, jump_target_id, jump_path, status, sort_order
                        from home_banner
                        where id = :bannerId
                        """)
                .param("bannerId", bannerId)
                .query((rs, rowNum) -> new BannerRow(
                        rs.getLong("id"),
                        rs.getObject("image_file_id", Long.class),
                        rs.getString("image_url"),
                        rs.getString("jump_type"),
                        rs.getObject("jump_target_id", Long.class),
                        rs.getString("jump_path"),
                        rs.getString("status"),
                        rs.getInt("sort_order")
                ))
                .single();
    }

    private UsageRow findActiveUsage(long bannerId) {
        return jdbcClient.sql("""
                        select asset_id, usage_type, owner_type, owner_label, snapshot_url, sort_order
                        from storage_asset_usage
                        where owner_type = 'HOME_BANNER'
                          and owner_id = :bannerId
                          and status = 'ACTIVE'
                        order by id desc
                        limit 1
                        """)
                .param("bannerId", bannerId)
                .query((rs, rowNum) -> new UsageRow(
                        rs.getLong("asset_id"),
                        rs.getString("usage_type"),
                        rs.getString("owner_type"),
                        rs.getString("owner_label"),
                        rs.getString("snapshot_url"),
                        rs.getInt("sort_order")
                ))
                .single();
    }

    private int activeUsageCount(long bannerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = 'HOME_BANNER'
                          and owner_id = :bannerId
                          and status = 'ACTIVE'
                        """)
                .param("bannerId", bannerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private int removedUsageCount(long bannerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = 'HOME_BANNER'
                          and owner_id = :bannerId
                          and status = 'REMOVED'
                        """)
                .param("bannerId", bannerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private int totalUsageCount(long bannerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = 'HOME_BANNER'
                          and owner_id = :bannerId
                        """)
                .param("bannerId", bannerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String limitedAdminToken(List<String> permissions) {
        long adminId = LIMITED_ADMIN_IDS.incrementAndGet();
        String username = "LimitedBannerAdmin" + adminId;
        String roleCode = "R_BANNER_LIMITED_" + adminId;
        insertLimitedAdmin(adminId, username, roleCode, permissions);
        TokenSession session = TokenSession.admin(adminId, username, List.of(roleCode), permissions, Instant.now());
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }

    private void insertLimitedAdmin(long adminId, String username, String roleCode, List<String> permissions) {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:adminId, :username, :passwordHash, :username, :email, 'ENABLED')
                        """)
                .param("adminId", adminId)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("email", "limited-banner-" + adminId + "@shop.test")
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:roleId, :roleCode, :roleCode, '', true)
                        """)
                .param("roleId", adminId)
                .param("roleCode", roleCode)
                .update();
        jdbcClient.sql("insert into admin_user_role (user_id, role_id) values (:adminId, :roleId)")
                .param("adminId", adminId)
                .param("roleId", adminId)
                .update();
        for (String permission : permissions) {
            Long permissionId = jdbcClient.sql("select id from admin_permission where auth_mark = :permission")
                    .param("permission", permission)
                    .query(Long.class)
                    .single();
            jdbcClient.sql("""
                            insert into admin_role_permission (role_id, permission_id)
                            values (:roleId, :permissionId)
                            """)
                    .param("roleId", adminId)
                    .param("permissionId", permissionId)
                    .update();
        }
    }

    private void clearLimitedAdmins() {
        jdbcClient.sql("delete from admin_role_permission where role_id between 9930001 and 9939999").update();
        jdbcClient.sql("delete from admin_user_role where role_id between 9930001 and 9939999").update();
        jdbcClient.sql("delete from admin_role where id between 9930001 and 9939999").update();
        jdbcClient.sql("delete from admin_user where id between 9930001 and 9939999").update();
    }

    private record UploadedFile(Long id, String url) {
    }

    private record BannerRow(
            Long id,
            Long imageFileId,
            String imageUrl,
            String jumpType,
            Long jumpTargetId,
            String jumpPath,
            String status,
            Integer sortOrder
    ) {
    }

    private record UsageRow(
            Long assetId,
            String usageType,
            String ownerType,
            String ownerLabel,
            String snapshotUrl,
            Integer sortOrder
    ) {
    }
}
