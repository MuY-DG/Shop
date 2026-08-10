package org.muybaby.shopserver.compliance.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.compliance.ComplianceProperties;
import org.muybaby.shopserver.compliance.LegalDocumentType;
import org.muybaby.shopserver.compliance.PublicationStatus;
import org.muybaby.shopserver.compliance.dto.LegalDocumentDraftRequest;
import org.muybaby.shopserver.compliance.dto.LegalDocumentResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class LegalDocumentService {

    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._-]{1,40}");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            ".*(示例条款|待填写|待补充|占位内容|example policy|placeholder|todo|tbd).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> MINI_PROGRAM_ENVS = Set.of("develop", "trial", "release");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ComplianceProperties properties;

    public LegalDocumentService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ComplianceProperties properties
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public LegalDocumentResponse current(LegalDocumentType type) {
        return findByPredicate(
                "document.current_publication_key = :currentKey",
                Map.of("currentKey", type.name())).stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<LegalDocumentResponse> history(LegalDocumentType type) {
        return findByPredicate(
                "document.document_type = :documentType",
                Map.of("documentType", type.name()));
    }

    @Transactional
    public LegalDocumentResponse createDraft(
            LegalDocumentType type,
            LegalDocumentDraftRequest request,
            long adminUserId
    ) {
        if (type == null || request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String version = normalize(request.version());
        String title = normalize(request.title());
        String content = normalizeDocument(request.content());
        if (!VERSION.matcher(version).matches()
                || !StringUtils.hasText(title)
                || title.length() > 160
                || !StringUtils.hasText(content)
                || content.length() > 100_000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into legal_document_revision (
                                document_type, version, title, content, content_sha256,
                                status, effective_at, created_by, created_at, updated_at
                            ) values (
                                :documentType, :version, :title, :content, :contentSha256,
                                'DRAFT', :effectiveAt, :createdBy, :now, :now
                            )
                            """,
                    new MapSqlParameterSource()
                            .addValue("documentType", type.name())
                            .addValue("version", version)
                            .addValue("title", title)
                            .addValue("content", content)
                            .addValue("contentSha256", sha256(content))
                            .addValue("effectiveAt", request.effectiveAt())
                            .addValue("createdBy", adminUserId)
                            .addValue("now", now),
                    keyHolder,
                    new String[]{"id"});
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return require(generatedId(keyHolder));
    }

    @Transactional
    public LegalDocumentResponse publish(long id, long adminUserId) {
        LegalDocumentResponse candidate = requireForUpdate(id);
        if (!PublicationStatus.DRAFT.name().equals(candidate.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (!StringUtils.hasText(candidate.content())
                || PLACEHOLDER.matcher(candidate.title()).matches()
                || PLACEHOLDER.matcher(candidate.content()).matches()
                || !VERSION.matcher(candidate.version()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (candidate.effectiveAt() != null && candidate.effectiveAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        LocalDateTime effectiveAt = candidate.effectiveAt() == null ? now : candidate.effectiveAt();
        jdbcClient.sql("""
                        update legal_document_revision
                        set status = 'SUPERSEDED', current_publication_key = null, updated_at = :now
                        where current_publication_key = :currentKey and id <> :id
                        """)
                .param("now", now)
                .param("currentKey", candidate.documentType())
                .param("id", id)
                .update();
        int updated;
        try {
            updated = jdbcClient.sql("""
                            update legal_document_revision
                            set status = 'PUBLISHED', current_publication_key = document_type,
                                effective_at = :effectiveAt, published_by = :publishedBy,
                                published_at = :publishedAt, updated_at = :publishedAt
                            where id = :id and status = 'DRAFT' and current_publication_key is null
                            """)
                    .param("effectiveAt", effectiveAt)
                    .param("publishedBy", adminUserId)
                    .param("publishedAt", now)
                    .param("id", id)
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return require(id);
    }

    @Transactional
    public void recordPrivacyConsent(
            long userId,
            String requestedVersion,
            Boolean accepted,
            String miniProgramEnv
    ) {
        boolean requestContainsConsent = Boolean.TRUE.equals(accepted)
                || StringUtils.hasText(requestedVersion)
                || StringUtils.hasText(miniProgramEnv);
        if (!properties.privacyConsentRequired() && !requestContainsConsent) {
            return;
        }
        if (!Boolean.TRUE.equals(accepted)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalizedEnv = normalize(miniProgramEnv).toLowerCase(Locale.ROOT);
        if (!MINI_PROGRAM_ENVS.contains(normalizedEnv)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        CurrentConsentDocument current = currentPrivacyForUpdate();
        if (current == null || !current.version().equals(normalize(requestedVersion))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            jdbcClient.sql("""
                            insert into app_user_document_consent (
                                user_id, legal_document_revision_id, document_type,
                                document_version, content_sha256, channel, mini_program_env,
                                accepted_at, created_at
                            ) values (
                                :userId, :revisionId, 'PRIVACY_POLICY', :version,
                                :contentSha256, 'WECHAT_MINIPROGRAM', :miniProgramEnv,
                                :acceptedAt, :acceptedAt
                            )
                            """)
                    .param("userId", userId)
                    .param("revisionId", current.id())
                    .param("version", current.version())
                    .param("contentSha256", current.contentSha256())
                    .param("miniProgramEnv", normalizedEnv)
                    .param("acceptedAt", LocalDateTime.now(ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException ignored) {
            // The immutable revision proves the same accepted content; replay is idempotent.
        }
    }

    private CurrentConsentDocument currentPrivacyForUpdate() {
        return jdbcClient.sql("""
                        select id, version, content_sha256
                        from legal_document_revision
                        where current_publication_key = 'PRIVACY_POLICY'
                          and status = 'PUBLISHED'
                        for update
                        """)
                .query((rs, rowNum) -> new CurrentConsentDocument(
                        rs.getLong("id"), rs.getString("version"), rs.getString("content_sha256")))
                .optional()
                .orElse(null);
    }

    private LegalDocumentResponse require(long id) {
        return findByPredicate("document.id = :id", Map.of("id", id)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private LegalDocumentResponse requireForUpdate(long id) {
        return jdbcClient.sql("""
                        select id, document_type, version, title, content, content_sha256,
                               status, effective_at, created_by, published_by, published_at,
                               created_at, updated_at
                        from legal_document_revision
                        where id = :id
                        for update
                        """)
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private List<LegalDocumentResponse> findByPredicate(String predicate, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select document.id, document.document_type, document.version,
                               document.title, document.content, document.content_sha256,
                               document.status, document.effective_at, document.created_by,
                               document.published_by, document.published_at,
                               document.created_at, document.updated_at
                        from legal_document_revision document
                        where %s
                        order by document.id desc
                        """.formatted(predicate));
        if (!parameters.isEmpty()) {
            statement = statement.params(parameters);
        }
        return statement.query(this::map).list();
    }

    private LegalDocumentResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new LegalDocumentResponse(
                rs.getLong("id"),
                rs.getString("document_type"),
                rs.getString("version"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("content_sha256"),
                rs.getString("status"),
                rs.getObject("effective_at", LocalDateTime.class),
                rs.getLong("created_by"),
                nullableLong(rs, "published_by"),
                rs.getObject("published_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long generatedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Legal document revision id was not generated");
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeDocument(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").trim();
    }

    private record CurrentConsentDocument(long id, String version, String contentSha256) {
    }
}
