package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateGroupRequest;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateGroupResponse;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateRequest;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateValueRequest;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateValueResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductSpecTemplateService {

    private static final int MAX_GROUPS = 10;
    private static final int MAX_VALUES_PER_GROUP = 50;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ProductSpecTemplateReadMapper readMapper;

    public ProductSpecTemplateService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ProductSpecTemplateReadMapper readMapper
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.readMapper = readMapper;
    }

    @Transactional
    public Long create(AdminSpecTemplateRequest request) {
        String name = requireText(request == null ? null : request.name(), 64);
        ensureNameAvailable(name, null);
        List<NormalizedGroup> groups = normalizeCreateGroups(request.groups());

        KeyHolder templateKeyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into product_spec_template (name)
                        values (:name)
                        """,
                new MapSqlParameterSource("name", name),
                templateKeyHolder,
                new String[]{"id"});
        Long templateId = requireGeneratedId(templateKeyHolder);

        for (NormalizedGroup group : groups) {
            Long groupId = insertGroup(templateId, group);
            for (NormalizedValue value : group.values()) {
                insertValue(groupId, value);
            }
        }
        return templateId;
    }

    @Transactional
    public Long createFromSpu(Long spuId, String templateName) {
        boolean activeMultiSpecProduct = jdbcClient.sql("""
                        select id
                        from product_spu
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                          and spec_type = 'MULTI'
                        for update
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!activeMultiSpecProduct) {
            throw validationException();
        }
        List<AdminSpecTemplateGroupRequest> groups = jdbcClient.sql("""
                        select id, group_key, name, image_enabled, sort_order
                        from product_spu_spec_group
                        where spu_id = :spuId and deleted_at is null
                        order by sort_order, id
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> {
                    Long groupId = rs.getLong("id");
                    List<AdminSpecTemplateValueRequest> values = jdbcClient.sql("""
                                    select value_key, value_name, sort_order
                                    from product_spu_spec_value
                                    where group_id = :groupId and deleted_at is null
                                    order by sort_order, id
                                    """)
                            .param("groupId", groupId)
                            .query((valueRs, valueRowNum) -> new AdminSpecTemplateValueRequest(
                                    null,
                                    valueRs.getString("value_key"),
                                    valueRs.getString("value_name"),
                                    valueRs.getInt("sort_order")
                            ))
                            .list();
                    return new AdminSpecTemplateGroupRequest(
                            null,
                            rs.getString("group_key"),
                            rs.getString("name"),
                            rs.getBoolean("image_enabled"),
                            rs.getInt("sort_order"),
                            values
                    );
                })
                .list();
        return create(new AdminSpecTemplateRequest(templateName, groups));
    }

    @Transactional
    public void update(Long templateId, AdminSpecTemplateRequest request) {
        AdminSpecTemplateDetailResponse existing = readMapper.findDetail(templateId)
                .orElseThrow(this::validationException);
        String name = requireText(request == null ? null : request.name(), 64);
        ensureNameAvailable(name, templateId);
        List<NormalizedGroup> groups = normalizeUpdateGroups(request.groups(), existing.groups());

        int templateRows = jdbcClient.sql("""
                        update product_spec_template
                        set name = :name,
                            updated_at = current_timestamp
                        where id = :templateId
                        """)
                .param("name", name)
                .param("templateId", templateId)
                .update();
        if (templateRows != 1) {
            throw validationException();
        }

        for (NormalizedGroup group : groups) {
            int groupRows = jdbcClient.sql("""
                            update product_spec_template_group
                            set name = :name
                            where id = :groupId
                              and template_id = :templateId
                            """)
                    .param("name", group.name())
                    .param("groupId", group.id())
                    .param("templateId", templateId)
                    .update();
            if (groupRows != 1) {
                throw validationException();
            }
            for (NormalizedValue value : group.values()) {
                int valueRows = jdbcClient.sql("""
                                update product_spec_template_value
                                set value_name = :valueName
                                where id = :valueId
                                  and group_id = :groupId
                                """)
                        .param("valueName", value.valueName())
                        .param("valueId", value.id())
                        .param("groupId", group.id())
                        .update();
                if (valueRows != 1) {
                    throw validationException();
                }
            }
        }
    }

    private List<NormalizedGroup> normalizeCreateGroups(List<AdminSpecTemplateGroupRequest> requests) {
        validateGroupList(requests);
        List<NormalizedGroup> groups = new ArrayList<>();
        Set<String> groupKeys = new HashSet<>();
        Set<String> groupNames = new HashSet<>();
        int imageGroupCount = 0;
        for (int groupIndex = 0; groupIndex < requests.size(); groupIndex++) {
            AdminSpecTemplateGroupRequest request = requests.get(groupIndex);
            if (request == null || request.id() != null) {
                throw validationException();
            }
            String key = normalizeOrGenerateKey(request.groupKey(), "g_");
            String name = requireText(request.name(), 30);
            boolean imageEnabled = requireBoolean(request.imageEnabled());
            if (!groupKeys.add(key) || !groupNames.add(name)) {
                throw validationException();
            }
            if (imageEnabled) {
                imageGroupCount++;
            }
            groups.add(new NormalizedGroup(
                    null,
                    key,
                    name,
                    imageEnabled,
                    defaultSortOrder(request.sortOrder(), groupIndex),
                    normalizeCreateValues(request.values())
            ));
        }
        requireSingleImageGroup(imageGroupCount);
        return List.copyOf(groups);
    }

    private List<NormalizedValue> normalizeCreateValues(List<AdminSpecTemplateValueRequest> requests) {
        validateValueList(requests);
        List<NormalizedValue> values = new ArrayList<>();
        Set<String> valueKeys = new HashSet<>();
        Set<String> valueNames = new HashSet<>();
        for (int valueIndex = 0; valueIndex < requests.size(); valueIndex++) {
            AdminSpecTemplateValueRequest request = requests.get(valueIndex);
            if (request == null || request.id() != null) {
                throw validationException();
            }
            String key = normalizeOrGenerateKey(request.valueKey(), "v_");
            String name = requireText(request.valueName(), 30);
            if (!valueKeys.add(key) || !valueNames.add(name)) {
                throw validationException();
            }
            values.add(new NormalizedValue(
                    null,
                    key,
                    name,
                    defaultSortOrder(request.sortOrder(), valueIndex)
            ));
        }
        return List.copyOf(values);
    }

    private List<NormalizedGroup> normalizeUpdateGroups(
            List<AdminSpecTemplateGroupRequest> requests,
            List<AdminSpecTemplateGroupResponse> existingGroups
    ) {
        validateGroupList(requests);
        Map<Long, AdminSpecTemplateGroupResponse> existingById = new LinkedHashMap<>();
        for (AdminSpecTemplateGroupResponse group : existingGroups) {
            existingById.put(group.id(), group);
        }
        Set<Long> submittedIds = new HashSet<>();
        for (AdminSpecTemplateGroupRequest request : requests) {
            if (request == null || request.id() == null || !submittedIds.add(request.id())) {
                throw validationException();
            }
        }
        if (!submittedIds.equals(existingById.keySet())) {
            throw validationException();
        }

        List<NormalizedGroup> groups = new ArrayList<>();
        Set<String> groupNames = new HashSet<>();
        int imageGroupCount = 0;
        for (int groupIndex = 0; groupIndex < requests.size(); groupIndex++) {
            AdminSpecTemplateGroupRequest request = requests.get(groupIndex);
            AdminSpecTemplateGroupResponse existing = existingById.get(request.id());
            if (StringUtils.hasText(request.groupKey())
                    && !existing.groupKey().equals(request.groupKey().trim())) {
                throw validationException();
            }
            String name = requireText(request.name(), 30);
            boolean imageEnabled = requireBoolean(request.imageEnabled());
            if (imageEnabled != existing.imageEnabled()
                    || (request.sortOrder() != null && !Objects.equals(request.sortOrder(), existing.sortOrder()))) {
                throw validationException();
            }
            if (!groupNames.add(name)) {
                throw validationException();
            }
            if (imageEnabled) {
                imageGroupCount++;
            }
            groups.add(new NormalizedGroup(
                    existing.id(),
                    existing.groupKey(),
                    name,
                    existing.imageEnabled(),
                    existing.sortOrder(),
                    normalizeUpdateValues(request.values(), existing.values())
            ));
        }
        requireSingleImageGroup(imageGroupCount);
        return List.copyOf(groups);
    }

    private List<NormalizedValue> normalizeUpdateValues(
            List<AdminSpecTemplateValueRequest> requests,
            List<AdminSpecTemplateValueResponse> existingValues
    ) {
        validateValueList(requests);
        Map<Long, AdminSpecTemplateValueResponse> existingById = new LinkedHashMap<>();
        for (AdminSpecTemplateValueResponse value : existingValues) {
            existingById.put(value.id(), value);
        }
        Set<Long> submittedIds = new HashSet<>();
        for (AdminSpecTemplateValueRequest request : requests) {
            if (request == null || request.id() == null || !submittedIds.add(request.id())) {
                throw validationException();
            }
        }
        if (!submittedIds.equals(existingById.keySet())) {
            throw validationException();
        }

        List<NormalizedValue> values = new ArrayList<>();
        Set<String> valueNames = new HashSet<>();
        for (int valueIndex = 0; valueIndex < requests.size(); valueIndex++) {
            AdminSpecTemplateValueRequest request = requests.get(valueIndex);
            AdminSpecTemplateValueResponse existing = existingById.get(request.id());
            if (StringUtils.hasText(request.valueKey())
                    && !existing.valueKey().equals(request.valueKey().trim())) {
                throw validationException();
            }
            String valueName = requireText(request.valueName(), 30);
            if (request.sortOrder() != null && !Objects.equals(request.sortOrder(), existing.sortOrder())) {
                throw validationException();
            }
            if (!valueNames.add(valueName)) {
                throw validationException();
            }
            values.add(new NormalizedValue(
                    existing.id(),
                    existing.valueKey(),
                    valueName,
                    existing.sortOrder()
            ));
        }
        return List.copyOf(values);
    }

    private Long insertGroup(Long templateId, NormalizedGroup group) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into product_spec_template_group
                            (template_id, group_key, name, image_enabled, sort_order)
                        values
                            (:templateId, :groupKey, :name, :imageEnabled, :sortOrder)
                        """,
                new MapSqlParameterSource()
                        .addValue("templateId", templateId)
                        .addValue("groupKey", group.groupKey())
                        .addValue("name", group.name())
                        .addValue("imageEnabled", group.imageEnabled())
                        .addValue("sortOrder", group.sortOrder()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void insertValue(Long groupId, NormalizedValue value) {
        jdbcClient.sql("""
                        insert into product_spec_template_value
                            (group_id, value_key, value_name, sort_order)
                        values
                            (:groupId, :valueKey, :valueName, :sortOrder)
                        """)
                .param("groupId", groupId)
                .param("valueKey", value.valueKey())
                .param("valueName", value.valueName())
                .param("sortOrder", value.sortOrder())
                .update();
    }

    private void ensureNameAvailable(String name, Long excludedTemplateId) {
        Integer count;
        if (excludedTemplateId == null) {
            count = jdbcClient.sql("""
                            select count(*)
                            from product_spec_template
                            where name = :name
                            """)
                    .param("name", name)
                    .query(Integer.class)
                    .single();
        } else {
            count = jdbcClient.sql("""
                            select count(*)
                            from product_spec_template
                            where name = :name
                              and id <> :templateId
                            """)
                    .param("name", name)
                    .param("templateId", excludedTemplateId)
                    .query(Integer.class)
                    .single();
        }
        if (count != null && count > 0) {
            throw validationException();
        }
    }

    private void validateGroupList(List<AdminSpecTemplateGroupRequest> groups) {
        if (groups == null || groups.isEmpty() || groups.size() > MAX_GROUPS) {
            throw validationException();
        }
    }

    private void validateValueList(List<AdminSpecTemplateValueRequest> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_VALUES_PER_GROUP) {
            throw validationException();
        }
    }

    private void requireSingleImageGroup(int imageGroupCount) {
        if (imageGroupCount != 1) {
            throw validationException();
        }
    }

    private String requireText(String value, int maxLength) {
        String normalized = StringUtils.hasText(value) ? value.trim() : null;
        if (normalized == null || normalized.length() > maxLength) {
            throw validationException();
        }
        return normalized;
    }

    private String normalizeOrGenerateKey(String value, String prefix) {
        if (!StringUtils.hasText(value)) {
            return prefix + UUID.randomUUID().toString().replace("-", "");
        }
        return requireText(value, 64);
    }

    private boolean requireBoolean(Boolean value) {
        if (value == null) {
            throw validationException();
        }
        return value;
    }

    private int defaultSortOrder(Integer sortOrder, int index) {
        return sortOrder == null ? index : sortOrder;
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw validationException();
        }
        return key.longValue();
    }

    private BusinessException validationException() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record NormalizedGroup(
            Long id,
            String groupKey,
            String name,
            boolean imageEnabled,
            int sortOrder,
            List<NormalizedValue> values
    ) {
    }

    private record NormalizedValue(Long id, String valueKey, String valueName, int sortOrder) {
    }
}
