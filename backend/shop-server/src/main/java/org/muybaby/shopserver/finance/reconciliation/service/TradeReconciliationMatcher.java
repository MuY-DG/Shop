package org.muybaby.shopserver.finance.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceSeverity;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceType;
import org.muybaby.shopserver.finance.reconciliation.TradeBillEntryType;
import org.muybaby.shopserver.finance.reconciliation.parser.ParsedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.parser.TradeBillRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class TradeReconciliationMatcher {

    private static final int BUSINESS_ID_QUERY_CHUNK = 1_000;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public TradeReconciliationMatcher(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public TradeReconciliationResult compare(
            String mchId,
            LocalDate billDate,
            ParsedTradeBill bill
    ) {
        LocalDateTime start = TimePolicy.businessDayStartUtc(billDate);
        LocalDateTime end = TimePolicy.businessDayStartUtc(billDate.plusDays(1));
        List<TradeBillRow> channelPayments = paymentRows(bill.rows());
        List<TradeBillRow> channelRefunds = refundRows(bill.rows());
        List<LocalPayment> datedPayments = localPayments(mchId, start, end);
        List<LocalRefund> datedRefunds = localRefunds(mchId, start, end);
        List<LocalPayment> paymentCandidates = mergePayments(
                datedPayments, paymentCandidates(mchId, channelPayments));
        List<LocalRefund> refundCandidates = mergeRefunds(
                datedRefunds, refundCandidates(mchId, channelRefunds));
        List<DifferenceDraft> differences = new ArrayList<>();
        differences.addAll(comparePayments(channelPayments, paymentCandidates, datedPayments));
        differences.addAll(compareRefunds(channelRefunds, refundCandidates, datedRefunds));
        differences.sort(Comparator.comparing(DifferenceDraft::diffKey));
        long localPaymentAmount = exactSum(
                datedPayments, ignored -> true, LocalPayment::amountCent);
        long localRefundAmount = exactSum(
                datedRefunds, LocalRefund::providerAccepted, LocalRefund::amountCent);
        return new TradeReconciliationResult(differences, localPaymentAmount, localRefundAmount);
    }

    private List<DifferenceDraft> comparePayments(
            List<TradeBillRow> channelRows,
            List<LocalPayment> localRows,
            List<LocalPayment> datedRows
    ) {
        List<DifferenceDraft> differences = new ArrayList<>();
        Set<Long> matchedLocalIds = new HashSet<>();
        Map<String, List<TradeBillRow>> channelByIdentity = groupChannel(channelRows);
        Map<String, List<LocalPayment>> localByTrade = group(localRows, LocalPayment::outTradeNo);
        Map<String, List<LocalPayment>> localByTransaction = group(localRows, LocalPayment::transactionId);
        Map<String, List<LocalPayment>> localByIdentity = group(localRows, this::paymentIdentity);
        for (List<TradeBillRow> duplicateGroup : channelByIdentity.values()) {
            TradeBillRow channel = duplicateGroup.getFirst();
            List<LocalPayment> exactGroup = localByIdentity.get(paymentIdentity(channel));
            LocalPayment exact = single(exactGroup);
            LocalPayment matched = exact;
            if (exactGroup != null && exactGroup.size() > 1) {
                matched = exactGroup.getFirst();
                exactGroup.forEach(local -> matchedLocalIds.add(local.id()));
                differences.add(identityMismatch(channel, matched));
            } else if (exact == null) {
                LocalPayment partial = preferredCandidate(
                        localByTrade.get(channel.outTradeNo()),
                        localByTransaction.get(channel.transactionId()));
                matched = partial;
                if (partial == null) {
                    differences.add(channelOnly(channel));
                } else {
                    matchedLocalIds.add(partial.id());
                    differences.add(identityMismatch(channel, partial));
                    if (!"SUCCESS".equals(channel.channelStatus())
                            || !"PAID".equals(partial.status())
                            || !"CNY".equals(channel.currency())) {
                        differences.add(statusMismatch(channel, partial));
                    }
                }
            } else {
                matchedLocalIds.add(exact.id());
                if (channel.amountCent() != exact.amountCent()) {
                    differences.add(amountMismatch(channel, exact));
                }
                if (!"SUCCESS".equals(channel.channelStatus())
                        || !"PAID".equals(exact.status())
                        || !"CNY".equals(channel.currency())) {
                    differences.add(statusMismatch(channel, exact));
                }
            }
            if (duplicateGroup.size() > 1) {
                differences.add(duplicateDifference(channel, duplicateGroup, matched));
            }
        }
        for (LocalPayment local : datedRows) {
            if (!matchedLocalIds.contains(local.id())) {
                differences.add(localOnly(local));
            }
        }
        return differences;
    }

    private List<DifferenceDraft> compareRefunds(
            List<TradeBillRow> channelRows,
            List<LocalRefund> localRows,
            List<LocalRefund> datedRows
    ) {
        List<DifferenceDraft> differences = new ArrayList<>();
        Set<Long> matchedLocalIds = new HashSet<>();
        Map<String, List<TradeBillRow>> channelByIdentity = groupChannel(channelRows);
        Map<String, List<LocalRefund>> localByRefundNo = group(localRows, LocalRefund::outRefundNo);
        Map<String, List<LocalRefund>> localByRefundId = group(localRows, LocalRefund::refundId);
        Map<String, List<LocalRefund>> localByTradeNo = group(localRows, LocalRefund::outTradeNo);
        Map<String, List<LocalRefund>> localByTransaction = group(localRows, LocalRefund::transactionId);
        Map<String, List<LocalRefund>> localByIdentity = group(localRows, this::refundIdentity);
        for (List<TradeBillRow> duplicateGroup : channelByIdentity.values()) {
            TradeBillRow channel = duplicateGroup.getFirst();
            List<LocalRefund> exactGroup = localByIdentity.get(refundIdentity(channel));
            LocalRefund exact = single(exactGroup);
            LocalRefund matched = exact;
            if (exactGroup != null && exactGroup.size() > 1) {
                matched = exactGroup.getFirst();
                exactGroup.forEach(local -> matchedLocalIds.add(local.id()));
                differences.add(identityMismatch(channel, matched));
            } else if (exact == null) {
                LocalRefund partial = preferredCandidate(
                        localByRefundNo.get(channel.outRefundNo()),
                        localByRefundId.get(channel.refundId()),
                        localByTradeNo.get(channel.outTradeNo()),
                        localByTransaction.get(channel.transactionId()));
                matched = partial;
                if (partial == null) {
                    differences.add(channelOnly(channel));
                } else {
                    matchedLocalIds.add(partial.id());
                    differences.add(identityMismatch(channel, partial));
                    if (!refundStatusesCompatible(channel.channelStatus(), partial.status())
                            || !"CNY".equals(channel.currency())) {
                        differences.add(statusMismatch(channel, partial));
                    }
                }
            } else {
                matchedLocalIds.add(exact.id());
                if (channel.amountCent() != exact.amountCent()) {
                    differences.add(amountMismatch(channel, exact));
                }
                if (!refundStatusesCompatible(channel.channelStatus(), exact.status())
                        || !"CNY".equals(channel.currency())) {
                    differences.add(statusMismatch(channel, exact));
                }
            }
            if (duplicateGroup.size() > 1) {
                differences.add(duplicateDifference(channel, duplicateGroup, matched));
            }
        }
        for (LocalRefund local : datedRows) {
            if (!matchedLocalIds.contains(local.id()) && local.providerAccepted()) {
                differences.add(localOnly(local));
            }
        }
        return differences;
    }

    private List<LocalPayment> localPayments(
            String mchId,
            LocalDateTime start,
            LocalDateTime end
    ) {
        return jdbcClient.sql("""
                        select payment.id, payment.order_id, payment.out_trade_no,
                               payment.transaction_id, payment.amount_cent, payment.status
                        from payment_order payment
                        left join payment_config config on config.id = payment.payment_config_id
                        left join payment_config_snapshot snapshot
                          on snapshot.fingerprint = payment.payment_config_fingerprint
                        where payment.status = 'PAID'
                          and payment.paid_at >= :startAt and payment.paid_at < :endAt
                          and case
                                  when payment.payment_config_id is not null then config.mch_id
                                  else snapshot.mch_id
                              end = :mchId
                        order by payment.id
                        """)
                .param("startAt", start)
                .param("endAt", end)
                .param("mchId", mchId)
                .query(this::mapPayment)
                .list();
    }

    private List<LocalRefund> localRefunds(
            String mchId,
            LocalDateTime start,
            LocalDateTime end
    ) {
        return jdbcClient.sql("""
                        select refund.id, refund.order_id, refund.payment_order_id,
                               payment.out_trade_no, payment.transaction_id,
                               refund.out_refund_no, refund.refund_id,
                               refund.refund_amount_cent, refund.status
                        from refund_order refund
                        join payment_order payment on payment.id = refund.payment_order_id
                        left join payment_config config on config.id = payment.payment_config_id
                        left join payment_config_snapshot snapshot
                          on snapshot.fingerprint = payment.payment_config_fingerprint
                        where refund.requested_at >= :startAt and refund.requested_at < :endAt
                          and case
                                  when payment.payment_config_id is not null then config.mch_id
                                  else snapshot.mch_id
                              end = :mchId
                        order by refund.id
                        """)
                .param("startAt", start)
                .param("endAt", end)
                .param("mchId", mchId)
                .query(this::mapRefund)
                .list();
    }

    private List<LocalPayment> paymentCandidates(String mchId, List<TradeBillRow> channelRows) {
        Map<Long, LocalPayment> rowsById = new LinkedHashMap<>();
        for (int start = 0; start < channelRows.size(); start += BUSINESS_ID_QUERY_CHUNK) {
            int end = Math.min(start + BUSINESS_ID_QUERY_CHUNK, channelRows.size());
            List<TradeBillRow> chunk = channelRows.subList(start, end);
            List<String> outTradeNos = chunk.stream().map(TradeBillRow::outTradeNo).distinct().toList();
            List<String> transactionIds = chunk.stream().map(TradeBillRow::transactionId).distinct().toList();
            List<LocalPayment> loaded = jdbcClient.sql("""
                            select payment.id, payment.order_id, payment.out_trade_no,
                                   payment.transaction_id, payment.amount_cent, payment.status
                            from payment_order payment
                            left join payment_config config on config.id = payment.payment_config_id
                            left join payment_config_snapshot snapshot
                              on snapshot.fingerprint = payment.payment_config_fingerprint
                            where (
                                    payment.out_trade_no in (:outTradeNos)
                                    or payment.transaction_id in (:transactionIds)
                                )
                              and case
                                      when payment.payment_config_id is not null then config.mch_id
                                      else snapshot.mch_id
                                  end = :mchId
                            order by payment.id
                            """)
                    .param("outTradeNos", outTradeNos)
                    .param("transactionIds", transactionIds)
                    .param("mchId", mchId)
                    .query(this::mapPayment)
                    .list();
            loaded.forEach(row -> rowsById.putIfAbsent(row.id(), row));
        }
        return List.copyOf(rowsById.values());
    }

    private List<LocalRefund> refundCandidates(String mchId, List<TradeBillRow> channelRows) {
        Map<Long, LocalRefund> rowsById = new LinkedHashMap<>();
        for (int start = 0; start < channelRows.size(); start += BUSINESS_ID_QUERY_CHUNK) {
            int end = Math.min(start + BUSINESS_ID_QUERY_CHUNK, channelRows.size());
            List<TradeBillRow> chunk = channelRows.subList(start, end);
            List<String> outTradeNos = nonBlankBusinessIds(chunk, TradeBillRow::outTradeNo);
            List<String> transactionIds = nonBlankBusinessIds(chunk, TradeBillRow::transactionId);
            List<String> outRefundNos = nonBlankBusinessIds(chunk, TradeBillRow::outRefundNo);
            List<String> refundIds = nonBlankBusinessIds(chunk, TradeBillRow::refundId);
            List<String> candidateConditions = new ArrayList<>(4);
            addCandidateCondition(candidateConditions, outRefundNos,
                    "refund.out_refund_no in (:outRefundNos)");
            addCandidateCondition(candidateConditions, refundIds,
                    "refund.refund_id in (:refundIds)");
            addCandidateCondition(candidateConditions, outTradeNos,
                    "payment.out_trade_no in (:outTradeNos)");
            addCandidateCondition(candidateConditions, transactionIds,
                    "payment.transaction_id in (:transactionIds)");
            if (candidateConditions.isEmpty()) {
                continue;
            }
            String sql = """
                            select refund.id, refund.order_id, refund.payment_order_id,
                                   payment.out_trade_no, payment.transaction_id,
                                   refund.out_refund_no, refund.refund_id,
                                   refund.refund_amount_cent, refund.status
                            from refund_order refund
                            join payment_order payment on payment.id = refund.payment_order_id
                            left join payment_config config on config.id = payment.payment_config_id
                            left join payment_config_snapshot snapshot
                              on snapshot.fingerprint = payment.payment_config_fingerprint
                            where (
                                    %s
                                )
                              and case
                                      when payment.payment_config_id is not null then config.mch_id
                                      else snapshot.mch_id
                                  end = :mchId
                            order by refund.id
                            """.formatted(String.join("\n                                    or ", candidateConditions));
            JdbcClient.StatementSpec statement = jdbcClient.sql(sql).param("mchId", mchId);
            if (!outRefundNos.isEmpty()) {
                statement = statement.param("outRefundNos", outRefundNos);
            }
            if (!refundIds.isEmpty()) {
                statement = statement.param("refundIds", refundIds);
            }
            if (!outTradeNos.isEmpty()) {
                statement = statement.param("outTradeNos", outTradeNos);
            }
            if (!transactionIds.isEmpty()) {
                statement = statement.param("transactionIds", transactionIds);
            }
            List<LocalRefund> loaded = statement
                    .query(this::mapRefund)
                    .list();
            loaded.forEach(row -> rowsById.putIfAbsent(row.id(), row));
        }
        return List.copyOf(rowsById.values());
    }

    private List<String> nonBlankBusinessIds(
            List<TradeBillRow> rows,
            Function<TradeBillRow, String> value
    ) {
        return rows.stream()
                .map(value)
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .distinct()
                .toList();
    }

    private void addCandidateCondition(
            List<String> conditions,
            List<String> values,
            String condition
    ) {
        if (!values.isEmpty()) {
            conditions.add(condition);
        }
    }

    private List<LocalPayment> mergePayments(
            List<LocalPayment> first,
            List<LocalPayment> second
    ) {
        Map<Long, LocalPayment> merged = new LinkedHashMap<>();
        first.forEach(row -> merged.put(row.id(), row));
        second.forEach(row -> merged.putIfAbsent(row.id(), row));
        return List.copyOf(merged.values());
    }

    private List<LocalRefund> mergeRefunds(List<LocalRefund> first, List<LocalRefund> second) {
        Map<Long, LocalRefund> merged = new LinkedHashMap<>();
        first.forEach(row -> merged.put(row.id(), row));
        second.forEach(row -> merged.putIfAbsent(row.id(), row));
        return List.copyOf(merged.values());
    }

    private LocalPayment mapPayment(ResultSet rs, int rowNum) throws SQLException {
        return new LocalPayment(
                rs.getLong("id"),
                rs.getLong("order_id"),
                safe(rs.getString("out_trade_no")),
                safe(rs.getString("transaction_id")),
                rs.getLong("amount_cent"),
                safe(rs.getString("status"))
        );
    }

    private LocalRefund mapRefund(ResultSet rs, int rowNum) throws SQLException {
        return new LocalRefund(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("payment_order_id"),
                safe(rs.getString("out_trade_no")),
                safe(rs.getString("transaction_id")),
                safe(rs.getString("out_refund_no")),
                safe(rs.getString("refund_id")),
                rs.getLong("refund_amount_cent"),
                safe(rs.getString("status"))
        );
    }

    private List<TradeBillRow> paymentRows(List<TradeBillRow> rows) {
        return rows.stream().filter(row -> row.entryType() == TradeBillEntryType.PAYMENT).toList();
    }

    private List<TradeBillRow> refundRows(List<TradeBillRow> rows) {
        return rows.stream().filter(row -> row.entryType() == TradeBillEntryType.REFUND).toList();
    }

    private Map<String, List<TradeBillRow>> groupChannel(List<TradeBillRow> rows) {
        Map<String, List<TradeBillRow>> grouped = new LinkedHashMap<>();
        for (TradeBillRow row : rows) {
            String identity = row.entryType() == TradeBillEntryType.PAYMENT
                    ? "P|" + paymentIdentity(row)
                    : "R|" + refundIdentity(row);
            grouped.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private <T> Map<String, List<T>> group(List<T> rows, Function<T, String> key) {
        Map<String, List<T>> grouped = new HashMap<>();
        for (T row : rows) {
            String value = key.apply(row);
            if (value != null && !value.isBlank()) {
                grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(row);
            }
        }
        return grouped;
    }

    @SafeVarargs
    private final <T> T preferredCandidate(List<T>... candidateGroups) {
        for (List<T> group : candidateGroups) {
            if (group != null && group.size() == 1) {
                return group.getFirst();
            }
        }
        return null;
    }

    private <T> T single(List<T> rows) {
        return rows != null && rows.size() == 1 ? rows.getFirst() : null;
    }

    private String paymentIdentity(TradeBillRow row) {
        return row.transactionId() + "|" + row.outTradeNo();
    }

    private String paymentIdentity(LocalPayment row) {
        return row.transactionId() + "|" + row.outTradeNo();
    }

    private String refundIdentity(TradeBillRow row) {
        return row.transactionId() + "|" + row.outTradeNo() + "|"
                + row.refundId() + "|" + row.outRefundNo();
    }

    private String refundIdentity(LocalRefund row) {
        return row.transactionId() + "|" + row.outTradeNo() + "|"
                + row.refundId() + "|" + row.outRefundNo();
    }

    private boolean refundStatusesCompatible(String providerStatus, String localStatus) {
        return switch (providerStatus) {
            case "SUCCESS" -> "SUCCESS".equals(localStatus);
            case "PROCESSING", "REFUND", "REVOKED" ->
                    "PROCESSING".equals(localStatus) || "SUCCESS".equals(localStatus);
            default -> false;
        };
    }

    private <T> long exactSum(
            List<T> rows,
            Predicate<T> included,
            java.util.function.ToLongFunction<T> amount
    ) {
        long total = 0L;
        try {
            for (T row : rows) {
                if (included.test(row)) {
                    total = Math.addExact(total, amount.applyAsLong(row));
                }
            }
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Local reconciliation aggregate exceeds supported range", ex);
        }
        return total;
    }

    private DifferenceDraft channelOnly(TradeBillRow row) {
        return draft(
                ReconciliationDifferenceType.CHANNEL_ONLY,
                ReconciliationDifferenceSeverity.CRITICAL,
                row,
                null,
                row.amountCent(),
                null,
                row.channelStatus(),
                "",
                channelEvidence(row),
                "{}"
        );
    }

    private DifferenceDraft localOnly(LocalPayment local) {
        return draft(
                ReconciliationDifferenceType.LOCAL_ONLY,
                ReconciliationDifferenceSeverity.CRITICAL,
                null,
                local,
                null,
                local.amountCent(),
                "",
                local.status(),
                "{}",
                localEvidence(local)
        );
    }

    private DifferenceDraft localOnly(LocalRefund local) {
        return draft(
                ReconciliationDifferenceType.LOCAL_ONLY,
                ReconciliationDifferenceSeverity.CRITICAL,
                null,
                local,
                null,
                local.amountCent(),
                "",
                local.status(),
                "{}",
                localEvidence(local)
        );
    }

    private DifferenceDraft identityMismatch(TradeBillRow row, LocalPayment local) {
        return draft(
                ReconciliationDifferenceType.IDENTITY_MISMATCH,
                ReconciliationDifferenceSeverity.CRITICAL,
                row,
                local,
                row.amountCent(),
                local.amountCent(),
                row.channelStatus(),
                local.status(),
                channelEvidence(row),
                localEvidence(local)
        );
    }

    private DifferenceDraft identityMismatch(TradeBillRow row, LocalRefund local) {
        return draft(
                ReconciliationDifferenceType.IDENTITY_MISMATCH,
                ReconciliationDifferenceSeverity.CRITICAL,
                row,
                local,
                row.amountCent(),
                local.amountCent(),
                row.channelStatus(),
                local.status(),
                channelEvidence(row),
                localEvidence(local)
        );
    }

    private DifferenceDraft amountMismatch(TradeBillRow row, LocalPayment local) {
        return draft(
                ReconciliationDifferenceType.AMOUNT_MISMATCH,
                ReconciliationDifferenceSeverity.CRITICAL,
                row,
                local,
                row.amountCent(),
                local.amountCent(),
                row.channelStatus(),
                local.status(),
                channelEvidence(row),
                localEvidence(local)
        );
    }

    private DifferenceDraft amountMismatch(TradeBillRow row, LocalRefund local) {
        return draft(
                ReconciliationDifferenceType.AMOUNT_MISMATCH,
                ReconciliationDifferenceSeverity.CRITICAL,
                row,
                local,
                row.amountCent(),
                local.amountCent(),
                row.channelStatus(),
                local.status(),
                channelEvidence(row),
                localEvidence(local)
        );
    }

    private DifferenceDraft statusMismatch(TradeBillRow row, LocalPayment local) {
        return draft(
                ReconciliationDifferenceType.STATUS_MISMATCH,
                ReconciliationDifferenceSeverity.WARNING,
                row,
                local,
                row.amountCent(),
                local.amountCent(),
                row.channelStatus(),
                local.status(),
                channelEvidence(row),
                localEvidence(local)
        );
    }

    private DifferenceDraft statusMismatch(TradeBillRow row, LocalRefund local) {
        return draft(
                ReconciliationDifferenceType.STATUS_MISMATCH,
                ReconciliationDifferenceSeverity.WARNING,
                row,
                local,
                row.amountCent(),
                local.amountCent(),
                row.channelStatus(),
                local.status(),
                channelEvidence(row),
                localEvidence(local)
        );
    }

    private DifferenceDraft duplicateDifference(
            TradeBillRow row,
            List<TradeBillRow> duplicates,
            Object local
    ) {
        List<String> sortedDigests = duplicates.stream()
                .map(TradeBillRow::rowDigest)
                .sorted()
                .toList();
        String evidence = json(Map.of(
                "entryType", row.entryType().name(),
                "transactionId", row.transactionId(),
                "outTradeNo", row.outTradeNo(),
                "refundId", row.refundId(),
                "outRefundNo", row.outRefundNo(),
                "duplicateCount", sortedDigests.size(),
                "rowDigestSample", sortedDigests.stream().limit(10).toList(),
                "rowDigestGroupSha256", sha256(String.join("\u001f", sortedDigests))
        ));
        Long localAmount = local instanceof LocalPayment payment
                ? payment.amountCent()
                : local instanceof LocalRefund refund ? refund.amountCent() : null;
        String localStatus = local instanceof LocalPayment payment
                ? payment.status()
                : local instanceof LocalRefund refund ? refund.status() : "";
        String localEvidence = local instanceof LocalPayment payment
                ? localEvidence(payment)
                : local instanceof LocalRefund refund ? localEvidence(refund) : "{}";
        return draft(
                ReconciliationDifferenceType.DUPLICATE_CHANNEL_ROW,
                ReconciliationDifferenceSeverity.CRITICAL,
                row,
                local,
                row.amountCent(),
                localAmount,
                row.channelStatus(),
                localStatus,
                evidence,
                localEvidence
        );
    }

    private DifferenceDraft draft(
            ReconciliationDifferenceType type,
            ReconciliationDifferenceSeverity severity,
            TradeBillRow channel,
            Object local,
            Long providerAmount,
            Long localAmount,
            String providerStatus,
            String localStatus,
            String providerEvidence,
            String localEvidence
    ) {
        Identity identity = identity(channel, local);
        String keyMaterial = stableBusinessIdentity(channel, local);
        return new DifferenceDraft(
                diffKey(type, keyMaterial),
                type,
                severity,
                identity.transactionId(),
                identity.outTradeNo(),
                identity.refundId(),
                identity.outRefundNo(),
                identity.orderId(),
                identity.paymentOrderId(),
                identity.refundOrderId(),
                providerAmount,
                localAmount,
                providerStatus,
                localStatus,
                providerEvidence,
                localEvidence
        );
    }

    private String stableBusinessIdentity(TradeBillRow channel, Object local) {
        String providerIdentity = channel == null
                ? ""
                : channel.entryType().name() + "|" + channel.transactionId() + "|"
                + channel.outTradeNo() + "|" + channel.refundId() + "|" + channel.outRefundNo();
        String localIdentity;
        if (local instanceof LocalPayment payment) {
            localIdentity = "PAYMENT|" + payment.transactionId() + "|" + payment.outTradeNo();
        } else if (local instanceof LocalRefund refund) {
            localIdentity = "REFUND|" + refund.transactionId() + "|" + refund.outTradeNo()
                    + "|" + refund.refundId() + "|" + refund.outRefundNo();
        } else {
            localIdentity = "";
        }
        return "provider=" + providerIdentity + "|local=" + localIdentity;
    }

    private Identity identity(TradeBillRow channel, Object local) {
        if (local instanceof LocalPayment payment) {
            return new Identity(
                    channel == null ? payment.transactionId() : channel.transactionId(),
                    channel == null ? payment.outTradeNo() : channel.outTradeNo(),
                    "", "", payment.orderId(), payment.id(), null);
        }
        if (local instanceof LocalRefund refund) {
            return new Identity(
                    channel == null ? refund.transactionId() : channel.transactionId(),
                    channel == null ? refund.outTradeNo() : channel.outTradeNo(),
                    channel == null ? refund.refundId() : channel.refundId(),
                    channel == null ? refund.outRefundNo() : channel.outRefundNo(),
                    refund.orderId(), refund.paymentOrderId(), refund.id());
        }
        return new Identity(
                channel == null ? "" : channel.transactionId(),
                channel == null ? "" : channel.outTradeNo(),
                channel == null ? "" : channel.refundId(),
                channel == null ? "" : channel.outRefundNo(),
                null, null, null);
    }

    private String channelEvidence(TradeBillRow row) {
        return json(Map.of(
                "entryType", row.entryType().name(),
                "transactionId", row.transactionId(),
                "outTradeNo", row.outTradeNo(),
                "refundId", row.refundId(),
                "outRefundNo", row.outRefundNo(),
                "amountCent", row.amountCent(),
                "currency", row.currency(),
                "status", row.channelStatus(),
                "rowDigest", row.rowDigest()
        ));
    }

    private String localEvidence(LocalPayment local) {
        return json(Map.of(
                "orderId", local.orderId(),
                "paymentOrderId", local.id(),
                "transactionId", local.transactionId(),
                "outTradeNo", local.outTradeNo(),
                "amountCent", local.amountCent(),
                "status", local.status()
        ));
    }

    private String localEvidence(LocalRefund local) {
        return json(Map.of(
                "orderId", local.orderId(),
                "paymentOrderId", local.paymentOrderId(),
                "refundOrderId", local.id(),
                "transactionId", local.transactionId(),
                "outTradeNo", local.outTradeNo(),
                "refundId", local.refundId(),
                "outRefundNo", local.outRefundNo(),
                "amountCent", local.amountCent(),
                "status", local.status()
        ));
    }

    private String diffKey(ReconciliationDifferenceType type, String material) {
        return sha256(type.name() + "|" + material);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String json(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize reconciliation evidence", ex);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record LocalPayment(
            long id,
            long orderId,
            String outTradeNo,
            String transactionId,
            long amountCent,
            String status
    ) {
    }

    private record LocalRefund(
            long id,
            long orderId,
            long paymentOrderId,
            String outTradeNo,
            String transactionId,
            String outRefundNo,
            String refundId,
            long amountCent,
            String status
    ) {
        private boolean providerAccepted() {
            return refundId != null && !refundId.isBlank();
        }
    }

    private record Identity(
            String transactionId,
            String outTradeNo,
            String refundId,
            String outRefundNo,
            Long orderId,
            Long paymentOrderId,
            Long refundOrderId
    ) {
    }
}
