package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationExportQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class FinanceReconciliationExportService {

    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final Set<String> BATCH_STATUSES = Set.of(
            "PENDING", "RUNNING", "RETRY_WAIT", "BALANCED", "DIFFERENCES", "EMPTY", "FAILED");
    private static final Set<String> DIFFERENCE_STATUSES = Set.of(
            "OPEN", "INVESTIGATING", "RESOLVED", "AUTO_CLEARED");
    private static final Set<String> DIFFERENCE_TYPES = Set.of(
            "CHANNEL_ONLY", "LOCAL_ONLY", "AMOUNT_MISMATCH", "IDENTITY_MISMATCH",
            "STATUS_MISMATCH", "DUPLICATE_CHANNEL_ROW", "SOURCE_CHANGED");

    private final JdbcClient jdbcClient;
    private final FinanceReconciliationProperties properties;

    public FinanceReconciliationExportService(
            JdbcClient jdbcClient,
            FinanceReconciliationProperties properties
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    public ExportedCsv export(AdminReconciliationExportQuery query) {
        ExportFilter filter = validate(query);
        long queryLimit;
        try {
            queryLimit = Math.addExact(properties.exportMaxRows(), 1L);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.FINANCE_EXPORT_LIMIT_EXCEEDED);
        }
        List<ExportRow> rows = jdbcClient.sql("""
                        select batch.bill_date, batch.mch_id, batch.id as batch_id,
                               batch.status as batch_status,
                               difference.id as difference_id,
                               difference.difference_type, difference.severity,
                               difference.status as difference_status,
                               difference.transaction_id, difference.out_trade_no,
                               difference.refund_id, difference.out_refund_no,
                               difference.order_id, difference.provider_amount_cent,
                               difference.local_amount_cent, difference.provider_status,
                               difference.local_status, difference.resolution_code,
                               difference.resolution_reason,
                               difference.created_at, difference.updated_at
                        from finance_reconciliation_difference difference
                        join finance_reconciliation_batch batch on batch.id = difference.batch_id
                        where batch.bill_date >= :fromDate and batch.bill_date <= :toDate
                          and (:mchId = '' or batch.mch_id = :mchId)
                          and (:batchStatus = '' or batch.status = :batchStatus)
                          and (:differenceStatus = '' or difference.status = :differenceStatus)
                          and (:differenceType = '' or difference.difference_type = :differenceType)
                        order by batch.bill_date, batch.id, difference.id
                        limit :rowLimit
                        """)
                .param("fromDate", filter.from())
                .param("toDate", filter.to())
                .param("mchId", filter.mchId())
                .param("batchStatus", filter.batchStatus())
                .param("differenceStatus", filter.differenceStatus())
                .param("differenceType", filter.differenceType())
                .param("rowLimit", queryLimit)
                .query(this::mapRow)
                .list();
        if (rows.size() > properties.exportMaxRows()) {
            throw new BusinessException(ErrorCode.FINANCE_EXPORT_LIMIT_EXCEEDED);
        }
        byte[] csv = encode(rows);
        return new ExportedCsv(csv, rows.size(), filter);
    }

    private ExportFilter validate(AdminReconciliationExportQuery query) {
        if (query == null || query.from() == null || query.to() == null
                || query.from().isAfter(query.to())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long inclusiveDays;
        try {
            inclusiveDays = Math.addExact(ChronoUnit.DAYS.between(query.from(), query.to()), 1L);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.FINANCE_EXPORT_LIMIT_EXCEEDED);
        }
        if (inclusiveDays > properties.exportMaxDays()) {
            throw new BusinessException(ErrorCode.FINANCE_EXPORT_LIMIT_EXCEEDED);
        }
        String mchId = normalize(query.mchId());
        if (mchId.length() > 32) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new ExportFilter(
                query.from(),
                query.to(),
                mchId,
                validatedEnum(query.batchStatus(), BATCH_STATUSES),
                validatedEnum(query.differenceStatus(), DIFFERENCE_STATUSES),
                validatedEnum(query.differenceType(), DIFFERENCE_TYPES)
        );
    }

    private String validatedEnum(String raw, Set<String> allowed) {
        String value = normalize(raw).toUpperCase(Locale.ROOT);
        if (!value.isEmpty() && !allowed.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private byte[] encode(List<ExportRow> rows) {
        StringBuilder output = new StringBuilder(Math.max(256, rows.size() * 256));
        appendRecord(output, List.of(
                "账单日期", "商户号", "批次ID", "批次状态", "差异ID", "差异类型", "严重级别",
                "差异状态", "微信订单号", "商户订单号", "微信退款单号", "商户退款单号",
                "订单ID", "渠道金额(分)", "本地金额(分)", "渠道状态", "本地状态",
                "处理编码", "处理原因", "创建时间", "更新时间"));
        for (ExportRow row : rows) {
            appendRecord(output, List.of(
                    row.billDate().toString(), textCell(row.mchId()), textCell(row.batchId()),
                    row.batchStatus(), textCell(row.differenceId()), row.differenceType(),
                    row.severity(), row.differenceStatus(), textCell(row.transactionId()),
                    textCell(row.outTradeNo()),
                    textCell(row.refundId()), textCell(row.outRefundNo()), textCell(row.orderId()),
                    nullableNumber(row.providerAmountCent()), nullableNumber(row.localAmountCent()),
                    row.providerStatus(), row.localStatus(), row.resolutionCode(),
                    row.resolutionReason(), value(row.createdAt()), value(row.updatedAt())));
        }
        byte[] body = output.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private void appendRecord(StringBuilder output, List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            String safe = formulaSafe(value(fields.get(index)));
            output.append('"').append(safe.replace("\"", "\"\"")).append('"');
        }
        output.append("\r\n");
    }

    private String formulaSafe(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        boolean leadingControl = first == '\t' || first == '\r' || first == '\n';
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (!(Character.isWhitespace(current) || current <= 0x20 || current == '\ufeff')) {
                break;
            }
            index++;
        }
        boolean dangerous = index < value.length()
                && (value.charAt(index) == '=' || value.charAt(index) == '+'
                || value.charAt(index) == '-' || value.charAt(index) == '@');
        return leadingControl || dangerous ? "'" + value : value;
    }

    private String nullableNumber(Number value) {
        return value == null ? "" : value.toString();
    }

    private String textCell(Object value) {
        String text = value(value);
        return text.isEmpty() ? "" : "'" + text;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private ExportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExportRow(
                rs.getDate("bill_date").toLocalDate(),
                rs.getString("mch_id"),
                rs.getLong("batch_id"),
                rs.getString("batch_status"),
                rs.getLong("difference_id"),
                rs.getString("difference_type"),
                rs.getString("severity"),
                rs.getString("difference_status"),
                rs.getString("transaction_id"),
                rs.getString("out_trade_no"),
                rs.getString("refund_id"),
                rs.getString("out_refund_no"),
                rs.getObject("order_id", Long.class),
                rs.getObject("provider_amount_cent", Long.class),
                rs.getObject("local_amount_cent", Long.class),
                rs.getString("provider_status"),
                rs.getString("local_status"),
                rs.getString("resolution_code"),
                rs.getString("resolution_reason"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    public record ExportedCsv(byte[] bytes, long recordCount, ExportFilter filter) {
        public ExportedCsv {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ExportFilter(
            LocalDate from,
            LocalDate to,
            String mchId,
            String batchStatus,
            String differenceStatus,
            String differenceType
    ) {
    }

    private record ExportRow(
            LocalDate billDate,
            String mchId,
            long batchId,
            String batchStatus,
            long differenceId,
            String differenceType,
            String severity,
            String differenceStatus,
            String transactionId,
            String outTradeNo,
            String refundId,
            String outRefundNo,
            Long orderId,
            Long providerAmountCent,
            Long localAmountCent,
            String providerStatus,
            String localStatus,
            String resolutionCode,
            String resolutionReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
