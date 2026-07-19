package org.muybaby.shopserver.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StorageControllerTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanStorageTables() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from storage_asset").update();

        while (true) {
            var leafFolderIds = jdbcClient.sql("""
                            select folder.id
                            from storage_asset_folder folder
                            where not exists (
                                select 1
                                from storage_asset_folder child
                                where child.parent_id = folder.id
                            )
                            """)
                    .query(Long.class)
                    .list();
            if (leafFolderIds.isEmpty()) {
                break;
            }
            leafFolderIds.forEach(folderId -> jdbcClient.sql("delete from storage_asset_folder where id = :folderId")
                    .param("folderId", folderId)
                    .update());
        }
    }

    @Test
    void libraryUploadListDetailMovePublicReadAndDeleteFormOneLifecycle() throws Exception {
        String token = adminToken();
        long folderId = createFolder(token, 0, "商品素材", "ENABLED");

        UploadedAsset asset = uploadImage(token, "hotpot-cover.png", folderId);

        mockMvc.perform(get("/admin/assets")
                        .param("keyword", "hotpot")
                        .param("mediaKind", "IMAGE")
                        .param("folderId", String.valueOf(folderId))
                        .param("referenceStatus", "UNREFERENCED")
                        .param("createdFrom", "2026-01-01T00:00:00")
                        .param("createdTo", "2027-01-01T00:00:00")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(asset.id()))
                .andExpect(jsonPath("$.data.records[0].scope").value("LIBRARY"))
                .andExpect(jsonPath("$.data.records[0].mediaKind").value("IMAGE"))
                .andExpect(jsonPath("$.data.records[0].folderId").value(folderId))
                .andExpect(jsonPath("$.data.records[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.records[0].usageCount").value(0));

        mockMvc.perform(get("/admin/assets/{assetId}", asset.id())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(asset.id()))
                .andExpect(jsonPath("$.data.usages").isArray())
                .andExpect(jsonPath("$.data.usages.length()").value(0));

        mockMvc.perform(post("/admin/assets/{assetId}/move", asset.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":0}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/assets")
                        .param("folderId", "0")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].folderId").doesNotExist());

        mockMvc.perform(get(URI.create(asset.publicUrl()).getPath()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));

        mockMvc.perform(delete("/admin/assets/{assetId}", asset.id())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/assets/{assetId}", asset.id())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    @Test
    void renameChangesOnlyDisplayFilenameAndKeepsStoredObjectAndUrlStable() throws Exception {
        String token = adminToken();
        UploadedAsset asset = uploadImage(token, "original-cover.png", 0);
        String objectKeyBefore = jdbcClient.sql("select object_key from storage_asset where id = :assetId")
                .param("assetId", asset.id())
                .query(String.class)
                .single();

        mockMvc.perform(put("/admin/assets/{assetId}/display-name", asset.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"菌汤锅底主图\"}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(asset.id()))
                .andExpect(jsonPath("$.data.originalFilename").value("菌汤锅底主图.png"))
                .andExpect(jsonPath("$.data.publicUrl").value(asset.publicUrl()));

        assertThat(jdbcClient.sql("select original_filename from storage_asset where id = :assetId")
                .param("assetId", asset.id())
                .query(String.class)
                .single()).isEqualTo("菌汤锅底主图.png");
        assertThat(jdbcClient.sql("select object_key from storage_asset where id = :assetId")
                .param("assetId", asset.id())
                .query(String.class)
                .single()).isEqualTo(objectKeyBefore);

        mockMvc.perform(get(URI.create(asset.publicUrl()).getPath()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }

    @Test
    void batchMoveNormalizesDuplicateIdsAndMovesAllSelectedAssets() throws Exception {
        String token = adminToken();
        long sourceFolderId = createFolder(token, 0, "待整理", "ENABLED");
        long targetFolderId = createFolder(token, 0, "商品轮播图", "ENABLED");
        UploadedAsset first = uploadImage(token, "gallery-1.png", sourceFolderId);
        UploadedAsset second = uploadImage(token, "gallery-2.png", sourceFolderId);

        mockMvc.perform(post("/admin/assets/batch-move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetIds":[%d,%d,%d],"folderId":%d}
                                """.formatted(first.id(), first.id(), second.id(), targetFolderId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(folderId(first.id())).isEqualTo(targetFolderId);
        assertThat(folderId(second.id())).isEqualTo(targetFolderId);
    }

    @Test
    void batchMoveRollsBackWhenAnySelectedAssetIsUnavailable() throws Exception {
        String token = adminToken();
        long sourceFolderId = createFolder(token, 0, "待整理", "ENABLED");
        long targetFolderId = createFolder(token, 0, "商品素材", "ENABLED");
        UploadedAsset validAsset = uploadImage(token, "valid.png", sourceFolderId);
        long privateAssetId = insertPrivateSecret();

        mockMvc.perform(post("/admin/assets/batch-move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetIds":[%d,%d],"folderId":%d}
                                """.formatted(validAsset.id(), privateAssetId, targetFolderId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));

        assertThat(folderId(validAsset.id())).isEqualTo(sourceFolderId);
    }

    @Test
    void listSupportsDescendantReferenceKeywordAndDateFilters() throws Exception {
        String token = adminToken();
        long rootId = createFolder(token, 0, "根分组", "ENABLED");
        assertThat(jdbcClient.sql("select count(*) from storage_asset_folder where id = :id and parent_id is null")
                .param("id", rootId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        long childId = createFolder(token, rootId, "子分组", "ENABLED");
        UploadedAsset rootAsset = uploadImage(token, "root-image.png", rootId);
        UploadedAsset childAsset = uploadImage(token, "child-image.png", childId);
        uploadImage(token, "loose-image.png", 0);
        insertUsage(childAsset.id());

        mockMvc.perform(get("/admin/assets")
                        .param("folderId", String.valueOf(rootId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/admin/assets")
                        .param("referenceStatus", "REFERENCED")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(childAsset.id()))
                .andExpect(jsonPath("$.data.records[0].usageCount").value(1));

        mockMvc.perform(get("/admin/assets")
                        .param("keyword", "ROOT-IMAGE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(rootAsset.id()));

        mockMvc.perform(get("/admin/assets/{assetId}", childAsset.id())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageCount").value(1))
                .andExpect(jsonPath("$.data.usages[0].assetId").value(childAsset.id()))
                .andExpect(jsonPath("$.data.usages[0].usageType").value("PRODUCT_SPU_MAIN"));

        mockMvc.perform(get("/admin/assets")
                        .param("createdFrom", "2099-01-01T00:00:00")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void folderRulesRejectCyclesDisabledTargetsAndNonEmptyDeletes() throws Exception {
        String token = adminToken();
        long rootId = createFolder(token, 0, "根分组", "ENABLED");
        long childId = createFolder(token, rootId, "子分组", "ENABLED");
        UploadedAsset childAsset = uploadImage(token, "child.png", childId);
        long emptyId = createFolder(token, 0, "空分组", "ENABLED");

        mockMvc.perform(put("/admin/asset-folders/{folderId}", rootId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(folderBody(childId, "根分组", "ENABLED"))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_CYCLE.code()));

        mockMvc.perform(put("/admin/asset-folders/{folderId}", childId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(folderBody(rootId, "子分组", "DISABLED"))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(put("/admin/asset-folders/{folderId}/position", rootId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + childId + ",\"index\":0}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_CYCLE.code()));

        mockMvc.perform(put("/admin/asset-folders/{folderId}/position", emptyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + childId + ",\"index\":0}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE.code()));

        mockMvc.perform(multipart("/admin/assets/upload")
                        .file(new MockMultipartFile("file", "blocked.png", "image/png", TINY_PNG))
                        .param("folderId", String.valueOf(childId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE.code()));

        mockMvc.perform(delete("/admin/asset-folders/{folderId}", rootId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_IN_USE.code()));

        mockMvc.perform(delete("/admin/asset-folders/{folderId}", childId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_IN_USE.code()));

        mockMvc.perform(delete("/admin/asset-folders/{folderId}", emptyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/assets/{assetId}/move", childAsset.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":" + childId + "}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE.code()));

        mockMvc.perform(delete("/admin/assets/{assetId}", childAsset.id())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/asset-folders/{folderId}", childId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/asset-folders/{folderId}", rootId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void folderPositionMovesAcrossLevelsAndNormalizesBothSiblingOrders() throws Exception {
        String token = adminToken();
        long firstRootId = createFolder(token, 0, "根分组一", "ENABLED");
        long secondRootId = createFolder(token, 0, "根分组二", "ENABLED");
        long movingRootId = createFolder(token, 0, "待移动根分组", "ENABLED");
        long firstChildId = createFolder(token, firstRootId, "子分组一", "ENABLED");
        long secondChildId = createFolder(token, firstRootId, "子分组二", "ENABLED");

        mockMvc.perform(put("/admin/asset-folders/{folderId}/position", movingRootId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + firstRootId + ",\"index\":1}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(firstRootId))
                .andExpect(jsonPath("$.data.sortOrder").value(1));

        mockMvc.perform(get("/admin/asset-folders")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(firstRootId))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].children[0].id").value(firstChildId))
                .andExpect(jsonPath("$.data[0].children[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].children[1].id").value(movingRootId))
                .andExpect(jsonPath("$.data[0].children[1].sortOrder").value(1))
                .andExpect(jsonPath("$.data[0].children[2].id").value(secondChildId))
                .andExpect(jsonPath("$.data[0].children[2].sortOrder").value(2))
                .andExpect(jsonPath("$.data[1].id").value(secondRootId))
                .andExpect(jsonPath("$.data[1].sortOrder").value(1));

        mockMvc.perform(put("/admin/asset-folders/{folderId}/position", firstChildId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":0,\"index\":1}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(0))
                .andExpect(jsonPath("$.data.sortOrder").value(1));

        mockMvc.perform(get("/admin/asset-folders")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(firstRootId))
                .andExpect(jsonPath("$.data[0].children[0].id").value(movingRootId))
                .andExpect(jsonPath("$.data[0].children[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].children[1].id").value(secondChildId))
                .andExpect(jsonPath("$.data[0].children[1].sortOrder").value(1))
                .andExpect(jsonPath("$.data[1].id").value(firstChildId))
                .andExpect(jsonPath("$.data[1].sortOrder").value(1))
                .andExpect(jsonPath("$.data[2].id").value(secondRootId))
                .andExpect(jsonPath("$.data[2].sortOrder").value(2));
    }

    @Test
    void folderCreationAppendsToTheSelectedParent() throws Exception {
        String token = adminToken();
        long firstRootId = createFolder(token, 0, "首个根分组", "ENABLED");
        long secondRootId = createFolder(token, 0, "第二个根分组", "ENABLED");
        long firstChildId = createFolder(token, firstRootId, "首个子分组", "ENABLED");
        long secondChildId = createFolder(token, firstRootId, "第二个子分组", "ENABLED");

        mockMvc.perform(get("/admin/asset-folders")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(firstRootId))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].children[0].id").value(firstChildId))
                .andExpect(jsonPath("$.data[0].children[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].children[1].id").value(secondChildId))
                .andExpect(jsonPath("$.data[0].children[1].sortOrder").value(1))
                .andExpect(jsonPath("$.data[1].id").value(secondRootId))
                .andExpect(jsonPath("$.data[1].sortOrder").value(1));
    }

    @Test
    void genericAssetEndpointsRejectPrivateScopes() throws Exception {
        String token = adminToken();
        long secretId = insertPrivateSecret();

        mockMvc.perform(get("/admin/assets/{assetId}", secretId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
        mockMvc.perform(post("/admin/assets/{assetId}/move", secretId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":0}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
        mockMvc.perform(put("/admin/assets/{assetId}/display-name", secretId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"renamed-secret\"}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
        mockMvc.perform(delete("/admin/assets/{assetId}", secretId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    @Test
    void uploadRejectsUnsupportedAndCorruptFilesWithEnvelope() throws Exception {
        String token = adminToken();

        mockMvc.perform(multipart("/admin/assets/upload")
                        .file(new MockMultipartFile("file", "not-image.svg", "image/svg+xml", "<svg/>".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));

        mockMvc.perform(multipart("/admin/assets/upload")
                        .file(new MockMultipartFile("file", "corrupt.png", "image/png", "broken".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));
    }

    private long createFolder(String token, long parentId, String name, String status) throws Exception {
        String response = mockMvc.perform(post("/admin/asset-folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(folderBody(parentId, name, status))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.parentId").value(parentId))
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.children").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private UploadedAsset uploadImage(String token, String filename, long folderId) throws Exception {
        var request = multipart("/admin/assets/upload")
                .file(new MockMultipartFile("file", filename, "image/png", TINY_PNG))
                .header("Authorization", bearer(token));
        if (folderId >= 0) {
            request.param("folderId", String.valueOf(folderId));
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("LIBRARY"))
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new UploadedAsset(data.path("id").asLong(), data.path("publicUrl").asText());
    }

    private void insertUsage(long assetId) {
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (asset_id, usage_type, owner_type, owner_id, owner_label,
                             snapshot_url, sort_order, protected, status)
                        values
                            (:assetId, 'PRODUCT_SPU_MAIN', 'PRODUCT_SPU', 90001, '测试商品',
                             '', 1, false, 'ACTIVE')
                        """)
                .param("assetId", assetId)
                .update();
    }

    private Long folderId(long assetId) {
        return jdbcClient.sql("select folder_id from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private long insertPrivateSecret() {
        String objectKey = "private/secret/document/test-secret.pem";
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256,
                             alt_text, public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('SECRET', 'DOCUMENT', null, 'PRIVATE', 'LOCAL', '', :objectKey,
                             'secret.pem', 'text/plain', 'pem', 10, '', '', null, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .update();
        return jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private String adminToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"Super\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String folderBody(long parentId, String name, String status) {
        return """
                {"parentId":%d,"name":"%s","sortOrder":0,"status":"%s"}
                """.formatted(parentId, name, status);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record UploadedAsset(long id, String publicUrl) {
    }
}
