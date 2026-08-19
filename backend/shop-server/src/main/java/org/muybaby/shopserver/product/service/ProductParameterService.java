package org.muybaby.shopserver.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.product.ProductParameterCardRenderer;
import org.muybaby.shopserver.product.ProductParameterCardRole;
import org.muybaby.shopserver.product.ProductParameterStatus;
import org.muybaby.shopserver.product.ProductParameterValueType;
import org.muybaby.shopserver.product.dto.AppProductParameterOptionValueResponse;
import org.muybaby.shopserver.product.dto.AppProductParameterValueResponse;
import org.muybaby.shopserver.product.dto.AdminProductParameterDefinitionRequest;
import org.muybaby.shopserver.product.dto.AdminProductParameterDefinitionResponse;
import org.muybaby.shopserver.product.dto.AdminProductParameterOptionRequest;
import org.muybaby.shopserver.product.dto.AdminProductParameterOptionResponse;
import org.muybaby.shopserver.product.dto.AdminSpuParameterValueRequest;
import org.muybaby.shopserver.product.dto.AdminSpuParameterValueResponse;
import org.muybaby.shopserver.product.dto.AppProductFilterGroupResponse;
import org.muybaby.shopserver.product.dto.AppProductFilterOptionResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ProductParameterService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 已由商品 SKU 结构化字段（netContentText / weightGram / volumeCubicMeter / specText）统一维护
     * 的事实，参数编码不允许占用，避免同一事实出现两份数据源。
     */
    private static final Set<String> RESERVED_PARAMETER_CODES = Set.of(
            "NET_CONTENT", "NET_WEIGHT", "WEIGHT", "WEIGHT_GRAM", "GROSS_WEIGHT", "GRAM",
            "VOLUME", "SPEC_TEXT");

    /** 与结构化物理量同义的参数名，与前端提示保持一致。 */
    private static final Pattern PHYSICAL_FACT_NAME_PATTERN =
            Pattern.compile("(净含量|净重|克重|重量|体积)");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ProductParameterService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public List<AdminProductParameterDefinitionResponse> definitions(Long categoryId, boolean enabledOnly) {
        List<Long> categoryLineage = categoryId == null ? List.of() : categoryLineage(categoryId);
        String categoryClause = categoryId == null
                ? ""
                : "and exists (select 1 from product_category_parameter cp " +
                "where cp.parameter_id = d.id and cp.category_id in (%s))"
                .formatted(categoryParameters(categoryLineage.size()));
        String enabledClause = enabledOnly ? "and d.status = 'ENABLED'" : "";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select d.id, d.parameter_code, d.parameter_name, d.value_type, d.unit, d.description,
                               d.required_value, d.filterable, d.card_visible, d.detail_visible,
                               d.card_role, d.card_renderer, d.card_priority,
                               d.sort_order, d.status, d.created_at, d.updated_at
                        from product_parameter_definition d
                        where 1 = 1
                          %s
                          %s
                        order by d.sort_order, d.id
                        """.formatted(categoryClause, enabledClause));
        if (categoryId != null) {
            for (int index = 0; index < categoryLineage.size(); index++) {
                statement = statement.param("categoryId" + index, categoryLineage.get(index));
            }
        }
        List<DefinitionRow> rows = statement.query((rs, rowNum) -> new DefinitionRow(
                rs.getLong("id"),
                rs.getString("parameter_code"),
                rs.getString("parameter_name"),
                rs.getString("value_type"),
                rs.getString("unit"),
                rs.getString("description"),
                rs.getBoolean("required_value"),
                rs.getBoolean("filterable"),
                rs.getBoolean("card_visible"),
                rs.getBoolean("detail_visible"),
                rs.getString("card_role"),
                rs.getString("card_renderer"),
                rs.getInt("card_priority"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        )).list();
        return hydrateDefinitions(rows);
    }

    public List<AppProductFilterGroupResponse> filterFacets(Long categoryId, String keyword) {
        List<AdminProductParameterDefinitionResponse> definitions = definitions(categoryId, true).stream()
                .filter(definition -> Boolean.TRUE.equals(definition.filterable()))
                .filter(definition -> ProductParameterValueType.SINGLE_SELECT.name().equals(definition.valueType())
                        || ProductParameterValueType.MULTI_SELECT.name().equals(definition.valueType()))
                .filter(definition -> !definition.options().isEmpty())
                .toList();
        if (definitions.isEmpty()) {
            return List.of();
        }

        List<Long> parameterIds = definitions.stream()
                .map(AdminProductParameterDefinitionResponse::id)
                .toList();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("parameterIds", parameterIds)
                .addValue("spuStatus", "ON_SALE")
                .addValue("categoryStatus", "ENABLED")
                .addValue("categoryId", categoryId)
                .addValue("keywordLike", StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null);
        Map<Long, Map<String, Long>> counts = new HashMap<>();
        namedParameterJdbcTemplate.query("""
                        select v.parameter_id, v.option_codes_json
                        from product_spu_parameter_value v
                        join product_spu s on s.id = v.spu_id
                        join product_category c on c.id = s.category_id
                        where v.parameter_id in (:parameterIds)
                          and s.status = :spuStatus
                          and s.deleted_at is null
                          and c.status = :categoryStatus
                          and (:categoryId is null or s.category_id = :categoryId)
                          and (:keywordLike is null or s.title like :keywordLike)
                        """,
                parameters,
                rs -> {
                    Long parameterId = rs.getLong("parameter_id");
                    Map<String, Long> optionCounts = counts.computeIfAbsent(
                            parameterId,
                            ignored -> new HashMap<>()
                    );
                    for (String optionCode : readOptionCodes(rs.getString("option_codes_json"))) {
                        optionCounts.merge(optionCode, 1L, Long::sum);
                    }
                });

        return definitions.stream()
                .map(definition -> new AppProductFilterGroupResponse(
                        definition.id(),
                        definition.parameterCode(),
                        definition.parameterName(),
                        definition.valueType(),
                        definition.options().stream()
                                .map(option -> new AppProductFilterOptionResponse(
                                        option.optionCode(),
                                        option.optionLabel(),
                                        option.displayLevel(),
                                        counts.getOrDefault(definition.id(), Map.of())
                                                .getOrDefault(option.optionCode(), 0L)
                                ))
                                .toList()
                ))
                .toList();
    }

    private List<Long> categoryLineage(Long categoryId) {
        LinkedHashSet<Long> lineage = new LinkedHashSet<>();
        Long currentCategoryId = categoryId;
        while (currentCategoryId != null && currentCategoryId > 0) {
            if (!lineage.add(currentCategoryId)) {
                throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_CYCLE);
            }
            Long resolvedCategoryId = currentCategoryId;
            currentCategoryId = jdbcClient.sql("""
                            select parent_id from product_category where id = :categoryId
                            """)
                    .param("categoryId", resolvedCategoryId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE));
        }
        return new ArrayList<>(lineage);
    }

    private String categoryParameters(int size) {
        List<String> parameters = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            parameters.add(":categoryId" + index);
        }
        return String.join(", ", parameters);
    }

    public AdminProductParameterDefinitionResponse definition(Long parameterId) {
        DefinitionRow row = jdbcClient.sql("""
                        select id, parameter_code, parameter_name, value_type, unit, description,
                               required_value, filterable, card_visible, detail_visible,
                               card_role, card_renderer, card_priority,
                               sort_order, status, created_at, updated_at
                        from product_parameter_definition
                        where id = :parameterId
                """)
                .param("parameterId", parameterId)
                .query((rs, rowNum) -> new DefinitionRow(
                        rs.getLong("id"), rs.getString("parameter_code"), rs.getString("parameter_name"),
                        rs.getString("value_type"), rs.getString("unit"), rs.getString("description"),
                        rs.getBoolean("required_value"), rs.getBoolean("filterable"),
                        rs.getBoolean("card_visible"), rs.getBoolean("detail_visible"),
                        rs.getString("card_role"), rs.getString("card_renderer"), rs.getInt("card_priority"),
                        rs.getInt("sort_order"), rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return hydrateDefinitions(List.of(row)).getFirst();
    }

    @Transactional
    public Long createDefinition(AdminProductParameterDefinitionRequest request) {
        NormalizedDefinition normalized = normalizeDefinition(request);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into product_parameter_definition
                                (parameter_code, parameter_name, value_type, unit, description,
                                 required_value, filterable, card_visible, detail_visible,
                                 card_role, card_renderer, card_priority, sort_order, status)
                            values
                                (:parameterCode, :parameterName, :valueType, :unit, :description,
                                 :requiredValue, :filterable, :cardVisible, :detailVisible,
                                 :cardRole, :cardRenderer, :cardPriority, :sortOrder, :status)
                            """,
                    definitionParameters(normalized),
                    keyHolder,
                    new String[]{"id"});
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create product parameter");
        }
        Long parameterId = key.longValue();
        replaceOptions(parameterId, normalized.options());
        replaceCategoryBindings(parameterId, normalized.categoryIds());
        publishHomeChanged();
        return parameterId;
    }

    @Transactional
    public void updateDefinition(Long parameterId, AdminProductParameterDefinitionRequest request) {
        DefinitionSnapshot existing = lockDefinition(parameterId);
        NormalizedDefinition normalized = normalizeDefinition(request);
        if (!existing.valueType().equals(normalized.valueType().name()) && hasValues(parameterId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        validateRemovedOptions(parameterId, normalized.options());
        try {
            int updated = jdbcClient.sql("""
                            update product_parameter_definition
                            set parameter_code = :parameterCode,
                                parameter_name = :parameterName,
                                value_type = :valueType,
                                unit = :unit,
                                description = :description,
                                required_value = :requiredValue,
                                filterable = :filterable,
                                card_visible = :cardVisible,
                                detail_visible = :detailVisible,
                                card_role = :cardRole,
                                card_renderer = :cardRenderer,
                                card_priority = :cardPriority,
                                sort_order = :sortOrder,
                                status = :status,
                                updated_at = current_timestamp
                            where id = :parameterId
                            """)
                    .params(definitionParameters(normalized).getValues())
                    .param("parameterId", parameterId)
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        replaceOptions(parameterId, normalized.options());
        replaceCategoryBindings(parameterId, normalized.categoryIds());
        publishHomeChanged();
    }

    @Transactional
    public void deleteDefinition(Long parameterId) {
        lockDefinition(parameterId);
        if (hasValues(parameterId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        // 删除定义后值表只剩回收站商品的孤儿数据，一并清理，避免恢复商品时挂到不存在的参数。
        jdbcClient.sql("delete from product_spu_parameter_value where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        jdbcClient.sql("delete from product_category_parameter where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        jdbcClient.sql("delete from product_parameter_option where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        jdbcClient.sql("delete from product_parameter_definition where id = :parameterId")
                .param("parameterId", parameterId).update();
        publishHomeChanged();
    }

    public List<AdminSpuParameterValueResponse> spuValues(Long spuId) {
        requireSpu(spuId, false);
        List<StoredParameterValueRow> rows = jdbcClient.sql("""
                        select v.parameter_id, v.text_value, v.number_value, v.boolean_value,
                               v.option_codes_json, d.parameter_name, d.value_type, d.unit
                        from product_spu_parameter_value v
                        join product_parameter_definition d on d.id = v.parameter_id
                        where v.spu_id = :spuId
                        order by d.sort_order, d.id
                """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new StoredParameterValueRow(
                        rs.getLong("parameter_id"),
                        rs.getString("parameter_name"),
                        ProductParameterValueType.valueOf(rs.getString("value_type")),
                        rs.getString("unit"),
                        rs.getString("text_value"),
                        rs.getBigDecimal("number_value"),
                        rs.getObject("boolean_value", Boolean.class),
                        readOptionCodes(rs.getString("option_codes_json"))
                ))
                .list();
        ParameterOptions options = loadParameterOptions(rows.stream()
                .map(StoredParameterValueRow::parameterId)
                .toList());
        return rows.stream()
                .map(row -> new AdminSpuParameterValueResponse(
                        row.parameterId(),
                        row.textValue(),
                        row.numberValue(),
                        row.booleanValue(),
                        row.optionCodes(),
                        displayText(
                                row.parameterId(),
                                row.valueType(),
                                row.textValue(),
                                row.numberValue(),
                                row.booleanValue(),
                                row.optionCodes(),
                                row.unit(),
                                options.byCode()
                        )
                ))
                .toList();
    }

    public List<AppProductParameterValueResponse> displayValues(Long spuId, boolean cardVisible) {
        return displayValuesBySpuIds(List.of(spuId), cardVisible).getOrDefault(spuId, List.of());
    }

    public Map<Long, List<AppProductParameterValueResponse>> displayValuesBySpuIds(
            List<Long> spuIds,
            boolean cardVisible
    ) {
        List<Long> normalizedSpuIds = spuIds == null
                ? List.of()
                : spuIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedSpuIds.isEmpty()) {
            return Map.of();
        }
        String visibilityColumn = cardVisible ? "d.card_visible" : "d.detail_visible";
        List<DisplayParameterValueRow> rows = namedParameterJdbcTemplate.query("""
                        select v.spu_id, v.parameter_id, v.text_value, v.number_value, v.boolean_value,
                               v.option_codes_json, d.parameter_code, d.parameter_name,
                               d.value_type, d.unit, d.card_role, d.card_renderer, d.card_priority
                        from product_spu_parameter_value v
                        join product_parameter_definition d on d.id = v.parameter_id
                        where v.spu_id in (:spuIds)
                          and d.status = 'ENABLED'
                          and %s = true
                        order by v.spu_id, d.card_priority, d.sort_order, d.id
                        """.formatted(visibilityColumn),
                Map.of("spuIds", normalizedSpuIds),
                (rs, rowNum) -> new DisplayParameterValueRow(
                        rs.getLong("spu_id"),
                        rs.getLong("parameter_id"),
                        rs.getString("parameter_code"),
                        rs.getString("parameter_name"),
                        ProductParameterValueType.valueOf(rs.getString("value_type")),
                        rs.getString("unit"),
                        rs.getString("card_role"),
                        rs.getString("card_renderer"),
                        rs.getInt("card_priority"),
                        rs.getString("text_value"),
                        rs.getBigDecimal("number_value"),
                        rs.getObject("boolean_value", Boolean.class),
                        readOptionCodes(rs.getString("option_codes_json"))
                ));
        ParameterOptions options = loadParameterOptions(rows.stream()
                .map(DisplayParameterValueRow::parameterId)
                .toList());
        Map<Long, List<AppProductParameterValueResponse>> valuesBySpuId = new LinkedHashMap<>();
        for (Long normalizedSpuId : normalizedSpuIds) {
            valuesBySpuId.put(normalizedSpuId, new ArrayList<>());
        }
        for (DisplayParameterValueRow row : rows) {
            valuesBySpuId.get(row.spuId()).add(new AppProductParameterValueResponse(
                    row.parameterId(),
                    row.parameterCode(),
                    row.parameterName(),
                    row.valueType().name(),
                    row.unit(),
                    displayText(
                            row.parameterId(),
                            row.valueType(),
                            row.textValue(),
                            row.numberValue(),
                            row.booleanValue(),
                            row.optionCodes(),
                            row.unit(),
                            options.byCode()
                    ),
                    row.cardRole(),
                    row.cardRenderer(),
                    row.cardPriority(),
                    selectedOptionValues(row.parameterId(), row.optionCodes(), options.byCode())
            ));
        }
        valuesBySpuId.replaceAll((ignored, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(valuesBySpuId);
    }

    @Transactional
    public void replaceSpuValues(Long spuId, List<AdminSpuParameterValueRequest> requests) {
        Long categoryId = requireSpu(spuId, true);
        List<AdminProductParameterDefinitionResponse> definitions = definitions(categoryId, true);
        Map<Long, AdminProductParameterDefinitionResponse> allowed = new HashMap<>();
        for (AdminProductParameterDefinitionResponse definition : definitions) {
            allowed.put(definition.id(), definition);
        }
        Map<Long, NormalizedValue> normalizedValues = new HashMap<>();
        for (AdminSpuParameterValueRequest request : requests == null ? List.<AdminSpuParameterValueRequest>of() : requests) {
            AdminProductParameterDefinitionResponse definition = allowed.get(request.parameterId());
            if (definition == null || normalizedValues.containsKey(request.parameterId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            NormalizedValue normalized = normalizeValue(definition, request);
            if (normalized != null) {
                normalizedValues.put(request.parameterId(), normalized);
            }
        }
        for (AdminProductParameterDefinitionResponse definition : definitions) {
            if (Boolean.TRUE.equals(definition.required()) && !normalizedValues.containsKey(definition.id())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        jdbcClient.sql("delete from product_spu_parameter_value where spu_id = :spuId")
                .param("spuId", spuId).update();
        for (Map.Entry<Long, NormalizedValue> entry : normalizedValues.entrySet()) {
            NormalizedValue value = entry.getValue();
            jdbcClient.sql("""
                            insert into product_spu_parameter_value
                                (spu_id, parameter_id, text_value, number_value, boolean_value, option_codes_json)
                            values
                                (:spuId, :parameterId, :textValue, :numberValue, :booleanValue, :optionCodesJson)
                            """)
                    .param("spuId", spuId)
                    .param("parameterId", entry.getKey())
                    .param("textValue", value.textValue())
                    .param("numberValue", value.numberValue())
                    .param("booleanValue", value.booleanValue())
                    .param("optionCodesJson", writeOptionCodes(value.optionCodes()))
                    .update();
        }
        publishHomeChanged();
    }

    public boolean requiredValuesComplete(Long spuId, Long categoryId) {
        Set<Long> requiredIds = new HashSet<>();
        for (AdminProductParameterDefinitionResponse definition : definitions(categoryId, true)) {
            if (Boolean.TRUE.equals(definition.required())) {
                requiredIds.add(definition.id());
            }
        }
        if (requiredIds.isEmpty()) {
            return true;
        }
        Set<Long> valueIds = new HashSet<>(jdbcClient.sql("""
                        select parameter_id from product_spu_parameter_value
                        where spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list());
        return valueIds.containsAll(requiredIds);
    }

    private List<AdminProductParameterDefinitionResponse> hydrateDefinitions(List<DefinitionRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> parameterIds = rows.stream().map(DefinitionRow::id).toList();
        Map<Long, List<Long>> categoryIdsByParameterId = new HashMap<>();
        namedParameterJdbcTemplate.query("""
                        select parameter_id, category_id
                        from product_category_parameter
                        where parameter_id in (:parameterIds)
                        order by parameter_id, category_id
                        """,
                Map.of("parameterIds", parameterIds),
                rs -> {
                    categoryIdsByParameterId
                            .computeIfAbsent(rs.getLong("parameter_id"), ignored -> new ArrayList<>())
                            .add(rs.getLong("category_id"));
                });
        ParameterOptions options = loadParameterOptions(parameterIds);
        return rows.stream()
                .map(row -> new AdminProductParameterDefinitionResponse(
                        row.id(),
                        row.code(),
                        row.name(),
                        row.valueType(),
                        row.unit(),
                        row.description(),
                        row.required(),
                        row.filterable(),
                        row.cardVisible(),
                        row.detailVisible(),
                        row.cardRole(),
                        row.cardRenderer(),
                        row.cardPriority(),
                        row.sortOrder(),
                        row.status(),
                        List.copyOf(categoryIdsByParameterId.getOrDefault(row.id(), List.of())),
                        options.ordered().getOrDefault(row.id(), List.of()).stream()
                                .map(option -> new AdminProductParameterOptionResponse(
                                        option.id(),
                                        option.optionCode(),
                                        option.optionLabel(),
                                        option.displayLevel(),
                                        option.sortOrder()
                                ))
                                .toList(),
                        row.createdAt(),
                        row.updatedAt()
                ))
                .toList();
    }

    private NormalizedDefinition normalizeDefinition(AdminProductParameterDefinitionRequest request) {
        String code = request.parameterCode() == null ? "" : request.parameterCode().trim().toUpperCase(Locale.ROOT);
        String name = request.parameterName() == null ? "" : request.parameterName().trim();
        if (!CODE_PATTERN.matcher(code).matches() || !StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (RESERVED_PARAMETER_CODES.contains(code) || PHYSICAL_FACT_NAME_PATTERN.matcher(name).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ProductParameterValueType valueType = parseEnum(request.valueType(), ProductParameterValueType.class);
        ProductParameterStatus status = parseEnum(request.status(), ProductParameterStatus.class);
        ProductParameterCardRole cardRole = parseEnum(
                StringUtils.hasText(request.cardRole()) ? request.cardRole() : ProductParameterCardRole.META.name(),
                ProductParameterCardRole.class
        );
        ProductParameterCardRenderer cardRenderer = parseEnum(
                StringUtils.hasText(request.cardRenderer())
                        ? request.cardRenderer()
                        : ProductParameterCardRenderer.TEXT.name(),
                ProductParameterCardRenderer.class
        );
        List<Long> categoryIds = new ArrayList<>(new LinkedHashSet<>(
                request.categoryIds() == null ? List.of() : request.categoryIds()
        ));
        for (Long categoryId : categoryIds) {
            if (categoryId == null || jdbcClient.sql("select id from product_category where id = :categoryId")
                    .param("categoryId", categoryId).query(Long.class).optional().isEmpty()) {
                throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE);
            }
        }
        List<AdminProductParameterOptionRequest> options = request.options() == null ? List.of() : request.options();
        boolean selectable = valueType == ProductParameterValueType.SINGLE_SELECT
                || valueType == ProductParameterValueType.MULTI_SELECT;
        if (selectable && options.isEmpty() || !selectable && !options.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> optionCodes = new HashSet<>();
        List<AdminProductParameterOptionRequest> normalizedOptions = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            AdminProductParameterOptionRequest option = options.get(index);
            String optionCode = option.optionCode() == null
                    ? ""
                    : option.optionCode().trim().toUpperCase(Locale.ROOT);
            String optionLabel = option.optionLabel() == null ? "" : option.optionLabel().trim();
            if (!CODE_PATTERN.matcher(optionCode).matches() || !StringUtils.hasText(optionLabel)
                    || !optionCodes.add(optionCode)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            normalizedOptions.add(new AdminProductParameterOptionRequest(
                    option.id(), optionCode, optionLabel, option.displayLevel(),
                    option.sortOrder() == null ? index : option.sortOrder()
            ));
        }
        return new NormalizedDefinition(
                code, name, valueType, defaultString(request.unit()).trim(),
                defaultString(request.description()).trim(), Boolean.TRUE.equals(request.required()),
                Boolean.TRUE.equals(request.filterable()), Boolean.TRUE.equals(request.cardVisible()),
                Boolean.TRUE.equals(request.detailVisible()), cardRole, cardRenderer,
                request.cardPriority() == null ? 0 : request.cardPriority(),
                request.sortOrder() == null ? 0 : request.sortOrder(), status, categoryIds, normalizedOptions
        );
    }

    private NormalizedValue normalizeValue(
            AdminProductParameterDefinitionResponse definition,
            AdminSpuParameterValueRequest request
    ) {
        ProductParameterValueType type = ProductParameterValueType.valueOf(definition.valueType());
        String textValue = request.textValue() == null ? null : request.textValue().trim();
        List<String> optionCodes = request.optionCodes() == null
                ? List.of()
                : request.optionCodes().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        boolean empty = switch (type) {
            case TEXT -> !StringUtils.hasText(textValue);
            case NUMBER -> request.numberValue() == null;
            case BOOLEAN -> request.booleanValue() == null;
            case SINGLE_SELECT, MULTI_SELECT -> optionCodes.isEmpty();
        };
        if (empty) {
            return null;
        }
        Set<String> allowedOptions = definition.options().stream()
                .map(AdminProductParameterOptionResponse::optionCode)
                .collect(java.util.stream.Collectors.toSet());
        if ((type == ProductParameterValueType.SINGLE_SELECT && optionCodes.size() != 1)
                || (type == ProductParameterValueType.MULTI_SELECT && optionCodes.isEmpty())
                || (!optionCodes.isEmpty() && !allowedOptions.containsAll(optionCodes))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new NormalizedValue(
                type == ProductParameterValueType.TEXT ? textValue : null,
                type == ProductParameterValueType.NUMBER ? request.numberValue() : null,
                type == ProductParameterValueType.BOOLEAN ? request.booleanValue() : null,
                type == ProductParameterValueType.SINGLE_SELECT || type == ProductParameterValueType.MULTI_SELECT
                        ? optionCodes
                        : List.of()
        );
    }

    private void replaceOptions(Long parameterId, List<AdminProductParameterOptionRequest> options) {
        jdbcClient.sql("delete from product_parameter_option where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        for (AdminProductParameterOptionRequest option : options) {
            jdbcClient.sql("""
                            insert into product_parameter_option
                                (parameter_id, option_code, option_label, display_level, sort_order)
                            values
                                (:parameterId, :optionCode, :optionLabel, :displayLevel, :sortOrder)
                            """)
                    .param("parameterId", parameterId)
                    .param("optionCode", option.optionCode())
                    .param("optionLabel", option.optionLabel())
                    .param("displayLevel", option.displayLevel())
                    .param("sortOrder", option.sortOrder())
                    .update();
        }
    }

    private void replaceCategoryBindings(Long parameterId, List<Long> categoryIds) {
        jdbcClient.sql("delete from product_category_parameter where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        for (Long categoryId : categoryIds) {
            jdbcClient.sql("""
                            insert into product_category_parameter (category_id, parameter_id)
                            values (:categoryId, :parameterId)
                            """)
                    .param("categoryId", categoryId)
                    .param("parameterId", parameterId)
                    .update();
        }
    }

    private void validateRemovedOptions(Long parameterId, List<AdminProductParameterOptionRequest> options) {
        Set<String> retained = options.stream()
                .map(AdminProductParameterOptionRequest::optionCode)
                .collect(java.util.stream.Collectors.toSet());
        List<String> values = jdbcClient.sql("""
                        select option_codes_json from product_spu_parameter_value
                        where parameter_id = :parameterId
                        """)
                .param("parameterId", parameterId)
                .query(String.class)
                .list();
        for (String value : values) {
            if (!retained.containsAll(readOptionCodes(value))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private DefinitionSnapshot lockDefinition(Long parameterId) {
        return jdbcClient.sql("""
                        select id, value_type from product_parameter_definition
                        where id = :parameterId for update
                        """)
                .param("parameterId", parameterId)
                .query((rs, rowNum) -> new DefinitionSnapshot(
                        rs.getLong("id"), rs.getString("value_type")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private boolean hasValues(Long parameterId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from product_spu_parameter_value v
                        join product_spu s on s.id = v.spu_id
                        where v.parameter_id = :parameterId
                          and s.deleted_at is null
                          and s.purged_at is null
                        """)
                .param("parameterId", parameterId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private Long requireSpu(Long spuId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbcClient.sql("""
                        select category_id from product_spu
                        where id = :spuId and deleted_at is null and purged_at is null%s
                        """.formatted(suffix))
                .param("spuId", spuId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
    }

    private String displayText(
            Long parameterId,
            ProductParameterValueType type,
            String textValue,
            BigDecimal numberValue,
            Boolean booleanValue,
            List<String> optionCodes,
            String unit,
            Map<Long, Map<String, ParameterOptionRow>> optionsByParameterAndCode
    ) {
        return switch (type) {
            case TEXT -> defaultString(textValue);
            case NUMBER -> numberValue == null ? "" : numberValue.stripTrailingZeros().toPlainString() + defaultString(unit);
            case BOOLEAN -> Boolean.TRUE.equals(booleanValue) ? "是" : "否";
            case SINGLE_SELECT, MULTI_SELECT -> {
                Map<String, ParameterOptionRow> options = optionsByParameterAndCode.getOrDefault(
                        parameterId,
                        Map.of()
                );
                yield String.join("、", optionCodes.stream()
                        .map(code -> {
                            ParameterOptionRow option = options.get(code);
                            return option == null ? code : option.optionLabel();
                        })
                        .toList());
            }
        };
    }

    private List<AppProductParameterOptionValueResponse> selectedOptionValues(
            Long parameterId,
            List<String> optionCodes,
            Map<Long, Map<String, ParameterOptionRow>> optionsByParameterAndCode
    ) {
        if (optionCodes.isEmpty()) {
            return List.of();
        }
        Map<String, ParameterOptionRow> options = optionsByParameterAndCode.getOrDefault(parameterId, Map.of());
        return optionCodes.stream()
                .map(options::get)
                .filter(java.util.Objects::nonNull)
                .map(option -> new AppProductParameterOptionValueResponse(
                        option.optionCode(),
                        option.optionLabel(),
                        option.displayLevel()
                ))
                .toList();
    }

    private ParameterOptions loadParameterOptions(List<Long> parameterIds) {
        List<Long> normalizedParameterIds = parameterIds == null
                ? List.of()
                : parameterIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedParameterIds.isEmpty()) {
            return ParameterOptions.empty();
        }
        List<ParameterOptionRow> rows = namedParameterJdbcTemplate.query("""
                        select id, parameter_id, option_code, option_label, display_level, sort_order
                        from product_parameter_option
                        where parameter_id in (:parameterIds)
                        order by parameter_id, sort_order, id
                        """,
                Map.of("parameterIds", normalizedParameterIds),
                (rs, rowNum) -> new ParameterOptionRow(
                        rs.getLong("id"),
                        rs.getLong("parameter_id"),
                        rs.getString("option_code"),
                        rs.getString("option_label"),
                        rs.getObject("display_level", Integer.class),
                        rs.getInt("sort_order")
                ));
        Map<Long, List<ParameterOptionRow>> ordered = new HashMap<>();
        Map<Long, Map<String, ParameterOptionRow>> byCode = new HashMap<>();
        for (ParameterOptionRow row : rows) {
            ordered.computeIfAbsent(row.parameterId(), ignored -> new ArrayList<>()).add(row);
            byCode.computeIfAbsent(row.parameterId(), ignored -> new HashMap<>()).put(row.optionCode(), row);
        }
        ordered.replaceAll((ignored, options) -> List.copyOf(options));
        byCode.replaceAll((ignored, options) -> Map.copyOf(options));
        return new ParameterOptions(Map.copyOf(ordered), Map.copyOf(byCode));
    }

    private MapSqlParameterSource definitionParameters(NormalizedDefinition definition) {
        return new MapSqlParameterSource()
                .addValue("parameterCode", definition.parameterCode())
                .addValue("parameterName", definition.parameterName())
                .addValue("valueType", definition.valueType().name())
                .addValue("unit", definition.unit())
                .addValue("description", definition.description())
                .addValue("requiredValue", definition.required())
                .addValue("filterable", definition.filterable())
                .addValue("cardVisible", definition.cardVisible())
                .addValue("detailVisible", definition.detailVisible())
                .addValue("cardRole", definition.cardRole().name())
                .addValue("cardRenderer", definition.cardRenderer().name())
                .addValue("cardPriority", definition.cardPriority())
                .addValue("sortOrder", definition.sortOrder())
                .addValue("status", definition.status().name());
    }

    private List<String> readOptionCodes(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid product parameter option snapshot", ex);
        }
    }

    private String writeOptionCodes(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize product parameter options", ex);
        }
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> enumType) {
        try {
            return Enum.valueOf(enumType, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private void publishHomeChanged() {
        eventPublisher.publishEvent(PublicContentChangedEvent.home());
    }

    private record DefinitionRow(
            Long id,
            String code,
            String name,
            String valueType,
            String unit,
            String description,
            Boolean required,
            Boolean filterable,
            Boolean cardVisible,
            Boolean detailVisible,
            String cardRole,
            String cardRenderer,
            Integer cardPriority,
            Integer sortOrder,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record StoredParameterValueRow(
            Long parameterId,
            String parameterName,
            ProductParameterValueType valueType,
            String unit,
            String textValue,
            BigDecimal numberValue,
            Boolean booleanValue,
            List<String> optionCodes
    ) {
    }

    private record DisplayParameterValueRow(
            Long spuId,
            Long parameterId,
            String parameterCode,
            String parameterName,
            ProductParameterValueType valueType,
            String unit,
            String cardRole,
            String cardRenderer,
            Integer cardPriority,
            String textValue,
            BigDecimal numberValue,
            Boolean booleanValue,
            List<String> optionCodes
    ) {
    }

    private record ParameterOptionRow(
            Long id,
            Long parameterId,
            String optionCode,
            String optionLabel,
            Integer displayLevel,
            Integer sortOrder
    ) {
    }

    private record ParameterOptions(
            Map<Long, List<ParameterOptionRow>> ordered,
            Map<Long, Map<String, ParameterOptionRow>> byCode
    ) {
        private static ParameterOptions empty() {
            return new ParameterOptions(Map.of(), Map.of());
        }
    }

    private record NormalizedDefinition(
            String parameterCode,
            String parameterName,
            ProductParameterValueType valueType,
            String unit,
            String description,
            Boolean required,
            Boolean filterable,
            Boolean cardVisible,
            Boolean detailVisible,
            ProductParameterCardRole cardRole,
            ProductParameterCardRenderer cardRenderer,
            Integer cardPriority,
            Integer sortOrder,
            ProductParameterStatus status,
            List<Long> categoryIds,
            List<AdminProductParameterOptionRequest> options
    ) {
    }

    private record NormalizedValue(
            String textValue,
            BigDecimal numberValue,
            Boolean booleanValue,
            List<String> optionCodes
    ) {
    }

    private record DefinitionSnapshot(Long id, String valueType) {
    }
}
