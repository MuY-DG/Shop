package org.muybaby.shopserver.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ProductParameterService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProductParameterService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<AdminProductParameterDefinitionResponse> definitions(Long categoryId, boolean enabledOnly) {
        String categoryClause = categoryId == null
                ? ""
                : "and exists (select 1 from product_category_parameter cp where cp.parameter_id = d.id and cp.category_id = :categoryId)";
        String enabledClause = enabledOnly ? "and d.status = 'ENABLED'" : "";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select d.id, d.parameter_code, d.parameter_name, d.value_type, d.unit, d.description,
                               d.required_value, d.filterable, d.card_visible, d.detail_visible,
                               d.sort_order, d.status, d.created_at, d.updated_at
                        from product_parameter_definition d
                        where 1 = 1
                          %s
                          %s
                        order by d.sort_order, d.id
                        """.formatted(categoryClause, enabledClause));
        if (categoryId != null) {
            statement = statement.param("categoryId", categoryId);
        }
        return statement.query((rs, rowNum) -> definitionResponse(
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
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        )).list();
    }

    public AdminProductParameterDefinitionResponse definition(Long parameterId) {
        return jdbcClient.sql("""
                        select id, parameter_code, parameter_name, value_type, unit, description,
                               required_value, filterable, card_visible, detail_visible,
                               sort_order, status, created_at, updated_at
                        from product_parameter_definition
                        where id = :parameterId
                        """)
                .param("parameterId", parameterId)
                .query((rs, rowNum) -> definitionResponse(
                        rs.getLong("id"), rs.getString("parameter_code"), rs.getString("parameter_name"),
                        rs.getString("value_type"), rs.getString("unit"), rs.getString("description"),
                        rs.getBoolean("required_value"), rs.getBoolean("filterable"),
                        rs.getBoolean("card_visible"), rs.getBoolean("detail_visible"),
                        rs.getInt("sort_order"), rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    @Transactional
    public Long createDefinition(AdminProductParameterDefinitionRequest request) {
        NormalizedDefinition normalized = normalizeDefinition(request);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into product_parameter_definition
                                (parameter_code, parameter_name, value_type, unit, description,
                                 required_value, filterable, card_visible, detail_visible, sort_order, status)
                            values
                                (:parameterCode, :parameterName, :valueType, :unit, :description,
                                 :requiredValue, :filterable, :cardVisible, :detailVisible, :sortOrder, :status)
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
    }

    @Transactional
    public void deleteDefinition(Long parameterId) {
        lockDefinition(parameterId);
        if (hasValues(parameterId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        jdbcClient.sql("delete from product_category_parameter where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        jdbcClient.sql("delete from product_parameter_option where parameter_id = :parameterId")
                .param("parameterId", parameterId).update();
        jdbcClient.sql("delete from product_parameter_definition where id = :parameterId")
                .param("parameterId", parameterId).update();
    }

    public List<AdminSpuParameterValueResponse> spuValues(Long spuId) {
        requireSpu(spuId, false);
        return jdbcClient.sql("""
                        select v.parameter_id, v.text_value, v.number_value, v.boolean_value,
                               v.option_codes_json, d.parameter_name, d.value_type, d.unit
                        from product_spu_parameter_value v
                        join product_parameter_definition d on d.id = v.parameter_id
                        where v.spu_id = :spuId
                        order by d.sort_order, d.id
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> {
                    Long parameterId = rs.getLong("parameter_id");
                    String textValue = rs.getString("text_value");
                    BigDecimal numberValue = rs.getBigDecimal("number_value");
                    Boolean booleanValue = rs.getObject("boolean_value", Boolean.class);
                    List<String> optionCodes = readOptionCodes(rs.getString("option_codes_json"));
                    return new AdminSpuParameterValueResponse(
                            parameterId, textValue, numberValue, booleanValue, optionCodes,
                            displayText(
                                    parameterId,
                                    ProductParameterValueType.valueOf(rs.getString("value_type")),
                                    textValue,
                                    numberValue,
                                    booleanValue,
                                    optionCodes,
                                    rs.getString("unit")
                            )
                    );
                })
                .list();
    }

    public List<AppProductParameterValueResponse> displayValues(Long spuId, boolean cardVisible) {
        String visibilityColumn = cardVisible ? "d.card_visible" : "d.detail_visible";
        return jdbcClient.sql("""
                        select v.parameter_id, v.text_value, v.number_value, v.boolean_value,
                               v.option_codes_json, d.parameter_code, d.parameter_name,
                               d.value_type, d.unit
                        from product_spu_parameter_value v
                        join product_parameter_definition d on d.id = v.parameter_id
                        where v.spu_id = :spuId
                          and d.status = 'ENABLED'
                          and %s = true
                        order by d.sort_order, d.id
                        """.formatted(visibilityColumn))
                .param("spuId", spuId)
                .query((rs, rowNum) -> {
                    Long parameterId = rs.getLong("parameter_id");
                    ProductParameterValueType valueType = ProductParameterValueType.valueOf(
                            rs.getString("value_type")
                    );
                    String textValue = rs.getString("text_value");
                    BigDecimal numberValue = rs.getBigDecimal("number_value");
                    Boolean booleanValue = rs.getObject("boolean_value", Boolean.class);
                    List<String> optionCodes = readOptionCodes(rs.getString("option_codes_json"));
                    return new AppProductParameterValueResponse(
                            parameterId,
                            rs.getString("parameter_code"),
                            rs.getString("parameter_name"),
                            valueType.name(),
                            rs.getString("unit"),
                            displayText(
                                    parameterId,
                                    valueType,
                                    textValue,
                                    numberValue,
                                    booleanValue,
                                    optionCodes,
                                    rs.getString("unit")
                            ),
                            selectedOptionValues(parameterId, optionCodes)
                    );
                })
                .list();
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

    private AdminProductParameterDefinitionResponse definitionResponse(
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
            Integer sortOrder,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        List<Long> categoryIds = jdbcClient.sql("""
                        select category_id from product_category_parameter
                        where parameter_id = :parameterId order by category_id
                        """)
                .param("parameterId", id)
                .query(Long.class)
                .list();
        List<AdminProductParameterOptionResponse> options = jdbcClient.sql("""
                        select id, option_code, option_label, display_level, sort_order
                        from product_parameter_option
                        where parameter_id = :parameterId
                        order by sort_order, id
                        """)
                .param("parameterId", id)
                .query((rs, rowNum) -> new AdminProductParameterOptionResponse(
                        rs.getLong("id"), rs.getString("option_code"), rs.getString("option_label"),
                        rs.getObject("display_level", Integer.class), rs.getInt("sort_order")
                ))
                .list();
        return new AdminProductParameterDefinitionResponse(
                id, code, name, valueType, unit, description, required, filterable, cardVisible,
                detailVisible, sortOrder, status, categoryIds, options, createdAt, updatedAt
        );
    }

    private NormalizedDefinition normalizeDefinition(AdminProductParameterDefinitionRequest request) {
        String code = request.parameterCode() == null ? "" : request.parameterCode().trim().toUpperCase(Locale.ROOT);
        String name = request.parameterName() == null ? "" : request.parameterName().trim();
        if (!CODE_PATTERN.matcher(code).matches() || !StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ProductParameterValueType valueType = parseEnum(request.valueType(), ProductParameterValueType.class);
        ProductParameterStatus status = parseEnum(request.status(), ProductParameterStatus.class);
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
                Boolean.TRUE.equals(request.detailVisible()), request.sortOrder() == null ? 0 : request.sortOrder(),
                status, categoryIds, normalizedOptions
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
                        select count(*) from product_spu_parameter_value
                        where parameter_id = :parameterId
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
            String unit
    ) {
        return switch (type) {
            case TEXT -> defaultString(textValue);
            case NUMBER -> numberValue == null ? "" : numberValue.stripTrailingZeros().toPlainString() + defaultString(unit);
            case BOOLEAN -> Boolean.TRUE.equals(booleanValue) ? "是" : "否";
            case SINGLE_SELECT, MULTI_SELECT -> {
                Map<String, String> labels = new HashMap<>();
                jdbcClient.sql("""
                                select option_code, option_label from product_parameter_option
                                where parameter_id = :parameterId
                                """)
                        .param("parameterId", parameterId)
                        .query((rs, rowNum) -> {
                            labels.put(rs.getString("option_code"), rs.getString("option_label"));
                            return rs.getString("option_code");
                        }).list();
                yield String.join("、", optionCodes.stream().map(code -> labels.getOrDefault(code, code)).toList());
            }
        };
    }

    private List<AppProductParameterOptionValueResponse> selectedOptionValues(
            Long parameterId,
            List<String> optionCodes
    ) {
        if (optionCodes.isEmpty()) {
            return List.of();
        }
        Map<String, AppProductParameterOptionValueResponse> options = new HashMap<>();
        jdbcClient.sql("""
                        select option_code, option_label, display_level
                        from product_parameter_option
                        where parameter_id = :parameterId
                        """)
                .param("parameterId", parameterId)
                .query((rs, rowNum) -> {
                    AppProductParameterOptionValueResponse option =
                            new AppProductParameterOptionValueResponse(
                                    rs.getString("option_code"),
                                    rs.getString("option_label"),
                                    rs.getObject("display_level", Integer.class)
                            );
                    options.put(option.optionCode(), option);
                    return option.optionCode();
                })
                .list();
        return optionCodes.stream()
                .map(options::get)
                .filter(java.util.Objects::nonNull)
                .toList();
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
