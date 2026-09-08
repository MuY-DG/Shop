package org.muybaby.shopserver.aftersale;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.service.UploadPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AfterSaleEvidenceControllerTest extends PaymentTestSupport {

    private static final byte[] MP4_HEADER = {
            0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0,
            'i', 's', 'o', 'm', 'm', 'p', '4', '2'
    };

    @Autowired
    private StorageProvider storageProvider;

    @Test
    void multipartRejectsOversizedAndDisguisedEvidenceBeforePersistingIt() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession user = appLogin("evidence-file-limits");
        SeedPaidOrder order = seedPaidOrder(user, 6980L, "PAID", "wx-evidence-file-limits");
        MockMultipartFile imageTooLarge = oversizedFile("proof.png", "image/png",
                UploadPolicy.AFTER_SALE_IMAGE_MAX_SIZE_BYTES + 1);
        MockMultipartFile videoTooLarge = oversizedFile("proof.mp4", "video/mp4",
                UploadPolicy.AFTER_SALE_VIDEO_MAX_SIZE_BYTES + 1);
        MockMultipartFile disguisedVideo = new MockMultipartFile("file", "proof.mp4", "video/mp4",
                "this is not a video".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (MockMultipartFile file : new MockMultipartFile[]{imageTooLarge, videoTooLarge, disguisedVideo}) {
            mockMvc.perform(multipart("/app/orders/{id}/after-sale-evidence", order.orderId())
                            .file(file).header("Authorization", "Bearer " + user.token()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));
        }
        assertThat(jdbcClient.sql("select count(*) from storage_asset where upload_context_id = :id")
                .param("id", order.orderId()).query(Integer.class).single()).isZero();
    }

    @Test
    void acceptsFourMixedFilesAndStreamsOnlyEvidenceOwnedByTheCurrentUser() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession owner = appLogin("evidence-four-owner");
        AppLoginSession other = appLogin("evidence-four-other");
        SeedPaidOrder order = seedPaidOrder(owner, 6980L, "PAID", "wx-evidence-four");
        String uploaded = mockMvc.perform(multipart("/app/orders/{id}/after-sale-evidence", order.orderId())
                        .file(new MockMultipartFile("file", "proof.mp4", "video/mp4", MP4_HEADER))
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaKind").value("VIDEO"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andReturn().getResponse().getContentAsString();
        long videoId = objectMapper.readTree(uploaded).path("data").path("id").asLong();
        long first = insertAppEvidenceFile(owner.userId(), order.orderId());
        long second = insertAppEvidenceFile(owner.userId(), order.orderId());
        long third = insertAppEvidenceFile(owner.userId(), order.orderId());
        long fifth = insertAppEvidenceFile(owner.userId(), order.orderId());
        for (String field : new String[]{"size_bytes = " + (UploadPolicy.AFTER_SALE_VIDEO_MAX_SIZE_BYTES + 1),
                "content_type = 'application/octet-stream'"}) {
            jdbcClient.sql("update storage_asset set " + field + " where id = :id").param("id", videoId).update();
            rejectEvidence(owner, order.orderId(), videoId);
            jdbcClient.sql("update storage_asset set size_bytes = :size, content_type = 'video/mp4' where id = :id")
                    .param("size", MP4_HEADER.length).param("id", videoId).update();
        }
        jdbcClient.sql("update storage_asset set size_bytes = :size where id = :id")
                .param("size", UploadPolicy.AFTER_SALE_IMAGE_MAX_SIZE_BYTES + 1).param("id", first).update();
        rejectEvidence(owner, order.orderId(), first);
        jdbcClient.sql("update storage_asset set size_bytes = 68 where id = :id").param("id", first).update();
        rejectEvidence(owner, order.orderId(), videoId, first, second, third, fifth);

        String applied = mockMvc.perform(post("/app/orders/{id}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(videoId, first, second, third)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.evidenceFileIds.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        long afterSaleId = objectMapper.readTree(applied).path("data").path("id").asLong();
        mockMvc.perform(get("/app/after-sales/{id}/evidence/{fileId}", afterSaleId, videoId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andExpect(content().contentType("video/mp4"))
                .andExpect(content().bytes(MP4_HEADER));
        for (Object[] denied : new Object[][]{{other.token(), videoId}, {owner.token(), fifth}}) {
            mockMvc.perform(get("/app/after-sales/{id}/evidence/{fileId}", afterSaleId, denied[1])
                            .header("Authorization", "Bearer " + denied[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
        }
        String admin = adminLogin();
        mockMvc.perform(get("/admin/after-sales/{id}/evidence/{fileId}", afterSaleId, videoId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(content().contentType("video/mp4"))
                .andExpect(content().bytes(MP4_HEADER));
        jdbcClient.sql("update storage_asset set status = 'DELETED' where id = :id").param("id", videoId).update();
        mockMvc.perform(get("/app/after-sales/{id}/evidence/{fileId}", afterSaleId, videoId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    @Test
    void directVideoSessionsEnforceLimitsContentOrderBindingAndPrivateFinalization() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession user = appLogin("evidence-direct-video");
        SeedPaidOrder order = seedPaidOrder(user, 6980L, "PAID", "wx-evidence-direct-video");
        SeedPaidOrder otherOrder = seedPaidOrder(user, 6980L, "PAID", "wx-evidence-direct-other");
        for (String request : new String[]{sessionBody("proof.png", "image/png", UploadPolicy.AFTER_SALE_IMAGE_MAX_SIZE_BYTES + 1),
                sessionBody("proof.mp4", "video/mp4", UploadPolicy.AFTER_SALE_VIDEO_MAX_SIZE_BYTES + 1)}) {
            mockMvc.perform(post(sessionRoute(order.orderId())).header("Authorization", "Bearer " + user.token())
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));
        }
        String wrongSize = createSession(user, order.orderId());
        stage(wrongSize, new byte[MP4_HEADER.length + 1]);
        rejectCompletion(user, order.orderId(), wrongSize);
        String wrongHeader = createSession(user, order.orderId());
        stage(wrongHeader, new byte[MP4_HEADER.length]);
        rejectCompletion(user, order.orderId(), wrongHeader);

        String uploadId = createSession(user, order.orderId());
        stage(uploadId, MP4_HEADER);
        mockMvc.perform(post(sessionRoute(otherOrder.orderId()) + "/" + uploadId + "/complete")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
        String result = mockMvc.perform(post(sessionRoute(order.orderId()) + "/" + uploadId + "/complete")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mediaKind").value("VIDEO"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.sizeBytes").value(MP4_HEADER.length))
                .andReturn().getResponse().getContentAsString();
        long assetId = objectMapper.readTree(result).path("data").path("id").asLong();
        assertThat(jdbcClient.sql("select count(*) from storage_asset where id = :id and expires_at > current_timestamp")
                .param("id", assetId).query(Integer.class).single()).isEqualTo(1);
        String cancelledId = createSession(user, order.orderId());
        mockMvc.perform(delete(sessionRoute(order.orderId()) + "/" + cancelledId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk());
    }

    private MockMultipartFile oversizedFile(String name, String type, long reportedSize) {
        return new MockMultipartFile("file", name, type, new byte[]{1}) {
            @Override public long getSize() { return reportedSize; }
        };
    }

    private void rejectEvidence(AppLoginSession user, long orderId, long... fileIds) throws Exception {
        mockMvc.perform(post("/app/orders/{id}/after-sales", orderId)
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(fileIds)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    private String applyBody(long... fileIds) {
        String ids = Arrays.stream(fileIds).mapToObj(Long::toString).collect(Collectors.joining(","));
        return "{\"afterSaleType\":\"REFUND_ONLY\",\"reason\":\"凭证测试\",\"evidenceFileIds\":[" + ids + "]}";
    }

    private String sessionRoute(long orderId) {
        return "/app/orders/" + orderId + "/after-sale-evidence/upload-sessions";
    }

    private String sessionBody(String filename, String type, long size) {
        return "{\"originalFilename\":\"" + filename + "\",\"contentType\":\"" + type + "\",\"sizeBytes\":" + size + "}";
    }

    private String createSession(AppLoginSession user, long orderId) throws Exception {
        String result = mockMvc.perform(post(sessionRoute(orderId)).header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON).content(sessionBody("proof.mp4", "video/mp4", MP4_HEADER.length)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(result).path("data");
        return data.path("uploadId").asText();
    }

    private void stage(String uploadId, byte[] bytes) {
        StorageObjectLocation location = jdbcClient.sql("""
                        select storage_container, storage_region, staging_object_key
                        from storage_upload_session where id = :id
                        """).param("id", uploadId).query((rs, rowNum) -> new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS, rs.getString("storage_container"),
                        rs.getString("storage_region"), rs.getString("staging_object_key"))).single();
        storageProvider.put(location, "video/mp4", new ByteArrayInputStream(bytes), bytes.length);
    }

    private void rejectCompletion(AppLoginSession user, long orderId, String uploadId) throws Exception {
        mockMvc.perform(post(sessionRoute(orderId) + "/" + uploadId + "/complete")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));
    }
}
