package org.muybaby.shopserver.finance.reconciliation.parser;

import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.TradeBillEntryType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WechatTradeBillParser {

    private static final DateTimeFormatter WECHAT_LOCAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final FinanceReconciliationProperties properties;

    public WechatTradeBillParser(FinanceReconciliationProperties properties) {
        this.properties = properties;
    }

    public ParsedTradeBill parse(Path path) throws IOException {
        try (BufferedReader buffered = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                Rfc4180Reader csv = new Rfc4180Reader(buffered, properties.maxFieldLength())) {
            List<String> header = findHeader(csv);
            Map<String, Integer> columns = columns(header);
            requireAny(columns, "商户订单号");
            requireAny(columns, "微信订单号");
            requireAny(columns, "微信退款单号");
            requireAny(columns, "商户退款单号");
            requireAny(columns, "交易时间");
            requireAny(columns, "交易状态");
            requireAny(columns, "订单金额");
            requireAny(columns, "申请退款金额");
            requireAny(columns, "退款状态");
            requireAny(columns, "货币种类");
            List<TradeBillRow> rows = new ArrayList<>();
            List<String> summaryHeader = null;
            while (true) {
                List<String> record = csv.nextRecord();
                if (record == null) {
                    break;
                }
                if (isSummaryHeader(record)) {
                    summaryHeader = record;
                    break;
                }
                if (record.stream().allMatch(value -> normalizeValue(value).isEmpty())) {
                    continue;
                }
                if (rows.size() >= properties.maxRows()) {
                    throw new IOException("Trade bill row count exceeds configured limit");
                }
                rows.add(toRow(rows.size() + 1L, columns, record));
            }
            ParsedTradeBill bill;
            try {
                bill = ParsedTradeBill.of(rows);
            } catch (IllegalArgumentException ex) {
                throw new IOException("WeChat trade bill aggregate exceeds supported range", ex);
            }
            validateSummary(csv, summaryHeader, bill);
            return bill;
        }
    }

    private void validateSummary(
            Rfc4180Reader csv,
            List<String> summaryHeader,
            ParsedTradeBill bill
    ) throws IOException {
        if (summaryHeader == null) {
            throw new IOException("WeChat trade bill summary header is missing");
        }
        List<String> summaryValues = csv.nextRecord();
        if (summaryValues == null) {
            throw new IOException("WeChat trade bill summary values are missing");
        }
        Map<String, Integer> summaryColumns = columns(summaryHeader);
        requireAny(summaryColumns, "总交易单数");
        requireAny(summaryColumns, "订单总金额");
        requireAny(summaryColumns, "申请退款总金额");
        String countValue = value(summaryColumns, summaryValues, "总交易单数");
        long summaryRows = nonNegativeWholeNumber(countValue, "总交易单数");
        if (summaryRows != bill.rows().size()) {
            throw new IOException("WeChat trade bill summary row count does not match detail rows");
        }

        long summaryPaymentAmount = yuanToCent(
                value(summaryColumns, summaryValues, "订单总金额"), -1L);
        long summaryRefundAmount = yuanToCent(
                value(summaryColumns, summaryValues, "申请退款总金额"), -1L);
        if (summaryPaymentAmount != bill.paymentAmountCent()
                || summaryRefundAmount != bill.refundAmountCent()) {
            throw new IOException("WeChat trade bill summary amounts do not match detail rows");
        }

        while (true) {
            List<String> trailing = csv.nextRecord();
            if (trailing == null) {
                return;
            }
            if (trailing.stream().anyMatch(value -> !normalizeValue(value).isEmpty())) {
                throw new IOException("Unexpected data follows WeChat trade bill summary");
            }
        }
    }

    private List<String> findHeader(Rfc4180Reader csv) throws IOException {
        for (int index = 0; index < 10; index++) {
            List<String> candidate = csv.nextRecord();
            if (candidate == null) {
                break;
            }
            if (candidate.stream().map(this::normalizeHeader).anyMatch("商户订单号"::equals)) {
                return candidate;
            }
        }
        throw new IOException("WeChat trade bill header was not found");
    }

    private TradeBillRow toRow(long rowNo, Map<String, Integer> columns, List<String> record)
            throws IOException {
        String transactionId = businessIdValue(columns, record, "微信订单号");
        String outTradeNo = businessIdValue(columns, record, "商户订单号");
        String refundId = businessIdValue(columns, record, "微信退款单号");
        String outRefundNo = businessIdValue(columns, record, "商户退款单号");
        String tradeStatus = value(columns, record, "交易状态").toUpperCase(Locale.ROOT);
        TradeBillEntryType type = switch (tradeStatus) {
            case "SUCCESS" -> TradeBillEntryType.PAYMENT;
            case "REFUND", "REVOKED" -> TradeBillEntryType.REFUND;
            default -> throw new IOException(
                    "Unsupported WeChat trade bill transaction status at row " + rowNo);
        };
        if (outTradeNo.isEmpty() || transactionId.isEmpty()) {
            throw new IOException("WeChat trade bill business identity is incomplete at row " + rowNo);
        }
        if ("REFUND".equals(tradeStatus) && (refundId.isEmpty() || outRefundNo.isEmpty())) {
            throw new IOException("WeChat refund identity is incomplete at row " + rowNo);
        }
        String amountValue = type == TradeBillEntryType.REFUND
                ? value(columns, record, "申请退款金额")
                : value(columns, record, "订单金额");
        long amountCent = yuanToCent(amountValue, rowNo);
        String status = type == TradeBillEntryType.REFUND
                ? value(columns, record, "退款状态")
                : value(columns, record, "交易状态");
        if (status.isEmpty()) {
            throw new IOException("WeChat trade bill status is missing at row " + rowNo);
        }
        String currency = value(columns, record, "货币种类");
        if (currency.isEmpty()) {
            throw new IOException("WeChat trade bill currency is missing at row " + rowNo);
        }
        String occurred = value(columns, record, "交易时间");
        return new TradeBillRow(
                rowNo,
                type,
                transactionId,
                outTradeNo,
                refundId,
                outRefundNo,
                parseOccurredAt(occurred, rowNo),
                amountCent,
                currency.toUpperCase(Locale.ROOT),
                status.toUpperCase(Locale.ROOT),
                sha256(String.join("\u001f", record.stream().map(this::normalizeValue).toList()))
        );
    }

    private Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) {
            columns.putIfAbsent(normalizeHeader(header.get(index)), index);
        }
        return Map.copyOf(columns);
    }

    private void requireAny(Map<String, Integer> columns, String... names) throws IOException {
        for (String name : names) {
            if (columns.containsKey(name)) {
                return;
            }
        }
        throw new IOException("Required WeChat trade bill column is missing: " + String.join("/", names));
    }

    private String value(Map<String, Integer> columns, List<String> record, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= record.size()) {
            return "";
        }
        return normalizeValue(record.get(index));
    }

    private String businessIdValue(
            Map<String, Integer> columns,
            List<String> record,
            String name
    ) {
        String value = value(columns, record, name);
        return "0".equals(value) ? "" : value;
    }

    private boolean isSummaryHeader(List<String> record) {
        if (record.isEmpty()) {
            return false;
        }
        String first = normalizeHeader(record.getFirst());
        return "总交易单数".equals(first);
    }

    private long nonNegativeWholeNumber(String value, String field) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("WeChat trade bill summary field is missing: " + field);
        }
        try {
            long parsed = new BigDecimal(value).longValueExact();
            if (parsed < 0) {
                throw new IOException("WeChat trade bill summary field is negative: " + field);
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IOException("Invalid WeChat trade bill summary field: " + field, ex);
        }
    }

    private String normalizeHeader(String value) {
        String normalized = normalizeValue(value);
        return normalized.startsWith("\ufeff") ? normalized.substring(1).trim() : normalized;
    }

    private String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("\ufeff")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.startsWith("`")) {
            normalized = normalized.substring(1);
        }
        return normalized.trim();
    }

    private long yuanToCent(String value, long rowNo) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("WeChat trade bill amount is missing at row " + rowNo);
        }
        try {
            BigDecimal yuan = new BigDecimal(value);
            if (yuan.signum() < 0) {
                throw new IOException("WeChat trade bill amount is negative at row " + rowNo);
            }
            return yuan.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            String location = rowNo < 0 ? " in summary" : " at row " + rowNo;
            throw new IOException("Invalid WeChat trade bill amount" + location, ex);
        }
    }

    private LocalDateTime parseOccurredAt(String value, long rowNo) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("WeChat trade bill timestamp is missing at row " + rowNo);
        }
        try {
            return TimePolicy.businessWallTimeToUtc(LocalDateTime.parse(value, WECHAT_LOCAL_TIME));
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).withOffsetSameInstant(TimePolicy.UTC).toLocalDateTime();
            } catch (DateTimeParseException ex) {
                throw new IOException("Invalid WeChat trade bill timestamp at row " + rowNo, ex);
            }
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
