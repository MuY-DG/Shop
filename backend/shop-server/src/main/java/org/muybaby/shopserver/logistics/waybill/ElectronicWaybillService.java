package org.muybaby.shopserver.logistics.waybill;

import org.muybaby.shopserver.aftersale.service.AfterSaleFulfillmentPolicy;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.waybill.ElectronicWaybillStateStore.AttemptRow;
import org.muybaby.shopserver.logistics.waybill.ElectronicWaybillStateStore.ItemSnapshot;
import org.muybaby.shopserver.logistics.waybill.ElectronicWaybillStateStore.OrderRow;
import org.muybaby.shopserver.logistics.waybill.ElectronicWaybillStateStore.OrderSnapshot;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressAccount;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressConfigService;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressEffectiveConfig;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressMode;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressParcel;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressSender;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillAttemptResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillContextResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillCreateRequest;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillReceiverResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillSandboxActionResponse;
import org.muybaby.shopserver.logistics.waybill.dto.ElectronicWaybillSandboxEventRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillAddRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillCancelRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillEnvironment;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillGetRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillProvider;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillResult;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillTestUpdateRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressCargoItem;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressContact;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressShopItem;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ElectronicWaybillService {

    private static final Duration STALE_OPERATION_THRESHOLD = Duration.ofMinutes(10);
    private static final int MAX_PRINT_HTML_BASE64_LENGTH = 4_000_000;
    private static final int MAX_PRINT_HTML_DECODED_LENGTH = 3_000_000;
    private static final BigDecimal MAX_WEIGHT_EXCLUSIVE = new BigDecimal("10000000");
    private static final BigDecimal MAX_DIMENSION_EXCLUSIVE = new BigDecimal("100000000");
    private static final Set<Integer> SANDBOX_ACTION_TYPES = Set.of(100001, 200001, 300002, 300003);
    private static final List<ElectronicWaybillSandboxActionResponse> SANDBOX_ACTIONS = List.of(
            new ElectronicWaybillSandboxActionResponse(100001, "快递员已揽件"),
            new ElectronicWaybillSandboxActionResponse(200001, "快件运输中"),
            new ElectronicWaybillSandboxActionResponse(300002, "快递员正在派送"),
            new ElectronicWaybillSandboxActionResponse(300003, "快件已签收")
    );

    private final ElectronicWaybillStateStore stateStore;
    private final WechatExpressConfigService configService;
    private final WechatElectronicWaybillProvider provider;
    private final AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy;
    private final TransactionTemplate transaction;
    private final TransactionTemplate withoutTransaction;

    public ElectronicWaybillService(
            ElectronicWaybillStateStore stateStore,
            WechatExpressConfigService configService,
            WechatElectronicWaybillProvider provider,
            AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy,
            PlatformTransactionManager transactionManager
    ) {
        this.stateStore = stateStore;
        this.configService = configService;
        this.provider = provider;
        this.afterSaleFulfillmentPolicy = afterSaleFulfillmentPolicy;
        this.transaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public ElectronicWaybillContextResponse context(AuthenticatedPrincipal principal, long orderId) {
        requireAdmin(principal);
        WechatExpressEffectiveConfig config = configService.effectiveConfig();
        ContextMaterial material = transaction.execute(status -> {
            OrderSnapshot order = stateStore.loadOrderSnapshot(
                    orderId, false, afterSaleFulfillmentPolicy.blockingStatuses()
            );
            AttemptRow current = stateStore.currentAttempt(orderId).orElse(null);
            return new ContextMaterial(order, current, stateStore.activeAttemptExists(orderId));
        });
        if (material == null) {
            throw unavailable();
        }
        List<String> blockers = blockers(config, material.order(), material.activeAttempt());
        return new ElectronicWaybillContextResponse(
                config.mode(),
                blockers.isEmpty(),
                List.copyOf(blockers),
                completeSender(config.sender()) ? config.sender() : null,
                completeReceiver(material.order().order()) ? receiver(material.order().order()) : null,
                config.defaultParcel(),
                material.current() == null ? null : toResponse(material.current()),
                config.mode() == WechatExpressMode.SANDBOX ? SANDBOX_ACTIONS : List.of()
        );
    }

    public List<ElectronicWaybillAttemptResponse> list(AuthenticatedPrincipal principal, long orderId) {
        requireAdmin(principal);
        return transaction.execute(status -> {
            stateStore.loadOrderSnapshot(orderId, false, afterSaleFulfillmentPolicy.blockingStatuses());
            return stateStore.list(orderId).stream().map(this::toResponse).toList();
        });
    }

    public ElectronicWaybillAttemptResponse create(
            AuthenticatedPrincipal principal,
            long orderId,
            ElectronicWaybillCreateRequest request
    ) {
        long adminId = requireAdmin(principal);
        NormalizedCreate normalized = normalize(request);
        WechatExpressEffectiveConfig config = configService.effectiveConfig();
        LocalDateTime now = now();
        CreateClaim claim = transaction.execute(status -> claimCreate(
                orderId, adminId, config, normalized, now
        ));
        if (claim == null) {
            throw unavailable();
        }
        if (claim.recover()) {
            return refreshInternal(orderId, claim.row().id());
        }
        if (!claim.dispatchAdd()) {
            return toResponse(claim.row());
        }

        WechatElectronicWaybillResult result = callProvider(() -> provider.add(claim.addRequest()));
        AttemptRow finalized = transaction.execute(status -> finishCreate(claim.row(), result));
        return toResponse(requireResult(finalized));
    }

    public ElectronicWaybillAttemptResponse refresh(
            AuthenticatedPrincipal principal,
            long orderId,
            long recordId
    ) {
        requireAdmin(principal);
        return refreshInternal(orderId, recordId);
    }

    public ElectronicWaybillAttemptResponse cancel(
            AuthenticatedPrincipal principal,
            long orderId,
            long recordId
    ) {
        requireAdmin(principal);
        CancelClaim claim = transaction.execute(status -> {
            stateStore.loadOrderSnapshot(
                    orderId, true, afterSaleFulfillmentPolicy.blockingStatuses()
            );
            AttemptRow row = stateStore.requireAttempt(orderId, recordId, true);
            if ((row.status() == ElectronicWaybillStatus.CANCELING
                    || row.status() == ElectronicWaybillStatus.UNKNOWN)
                    && row.pendingOperation() == ElectronicWaybillPendingOperation.CANCEL) {
                return new CancelClaim(row, false);
            }
            if (row.status() != ElectronicWaybillStatus.CREATED
                    || row.pendingOperation() != ElectronicWaybillPendingOperation.NONE
                    || !StringUtils.hasText(row.waybillId())) {
                throw conflict();
            }
            if (!stateStore.claimCancel(row, now())) {
                throw conflict();
            }
            return new CancelClaim(stateStore.requireAttempt(orderId, recordId, false), true);
        });
        if (claim == null) {
            throw unavailable();
        }
        if (!claim.dispatch()) {
            return toResponse(claim.row());
        }

        AttemptRow row = claim.row();
        WechatElectronicWaybillResult result = callProvider(() -> provider.cancel(
                new WechatElectronicWaybillCancelRequest(
                        row.id(), row.providerOrderId(), row.payerOpenid(),
                        row.deliveryId(), row.waybillId()
                )
        ));
        AttemptRow finalized = transaction.execute(status -> finishCancel(row, result));
        return toResponse(requireResult(finalized));
    }

    public ElectronicWaybillPrintData print(
            AuthenticatedPrincipal principal,
            long orderId,
            long recordId,
            Integer printType
    ) {
        requireAdmin(principal);
        if (printType == null || printType < 0 || printType > 1) {
            throw validation();
        }
        AttemptRow row = transaction.execute(status -> {
            stateStore.loadOrderSnapshot(
                    orderId, false, afterSaleFulfillmentPolicy.blockingStatuses()
            );
            AttemptRow current = stateStore.requireAttempt(orderId, recordId, false);
            if ((current.status() != ElectronicWaybillStatus.CREATED
                    && current.status() != ElectronicWaybillStatus.CONFIRMED)
                    || current.pendingOperation() != ElectronicWaybillPendingOperation.NONE
                    || !StringUtils.hasText(current.waybillId())) {
                throw conflict();
            }
            return current;
        });
        if (row == null) {
            throw unavailable();
        }
        WechatElectronicWaybillResult result = callProvider(() -> provider.get(
                getRequest(row, printType)
        ));
        byte[] html = validPrintHtml(row, result);
        Boolean counted = transaction.execute(status -> stateStore.incrementPrint(recordId, now()));
        if (!Boolean.TRUE.equals(counted)) {
            throw conflict();
        }
        return new ElectronicWaybillPrintData(html);
    }

    public ElectronicWaybillAttemptResponse simulate(
            AuthenticatedPrincipal principal,
            long orderId,
            long recordId,
            ElectronicWaybillSandboxEventRequest request
    ) {
        requireAdmin(principal);
        if (request == null
                || request.actionType() == null
                || !SANDBOX_ACTION_TYPES.contains(request.actionType())
                || !StringUtils.hasText(request.actionMessage())
                || request.actionMessage().trim().length() > 255) {
            throw validation();
        }
        WechatExpressEffectiveConfig config = configService.effectiveConfig();
        if (!officialSandbox(config)) {
            throw conflict();
        }
        AttemptRow row = transaction.execute(status -> {
            stateStore.loadOrderSnapshot(
                    orderId, true, afterSaleFulfillmentPolicy.blockingStatuses()
            );
            AttemptRow current = stateStore.requireAttempt(orderId, recordId, true);
            if (!sandboxAttempt(current) || !stateStore.recordSandboxAttempt(recordId, now())) {
                throw conflict();
            }
            return stateStore.requireAttempt(orderId, recordId, false);
        });
        if (row == null) {
            throw unavailable();
        }
        String actionMessage = request.actionMessage().trim();
        WechatElectronicWaybillResult result = callProvider(() -> provider.testUpdate(
                new WechatElectronicWaybillTestUpdateRequest(
                        row.id(), row.bizId(), row.providerOrderId(), row.deliveryId(), row.waybillId(),
                        java.time.Instant.now().getEpochSecond(), request.actionType(), actionMessage
                )
        ));
        String errorCode = result.outcome() == WechatProviderOutcome.SUCCESS
                ? "" : safeCode(result.errorCode());
        String errorMessage = result.outcome() == WechatProviderOutcome.SUCCESS
                ? "" : safeMessage(result.errorMessage());
        transaction.executeWithoutResult(status -> stateStore.finishSandboxAttempt(
                recordId, errorCode, errorMessage, now()
        ));
        if (result.outcome() != WechatProviderOutcome.SUCCESS) {
            throw unavailable();
        }
        AttemptRow updated = transaction.execute(status -> stateStore.requireAttempt(orderId, recordId, false));
        return toResponse(requireResult(updated));
    }

    public ElectronicWaybillAttemptResponse latestSummary(long orderId) {
        AttemptRow row = transaction.execute(status -> stateStore.currentAttempt(orderId).orElse(null));
        return row == null ? null : toResponse(row);
    }

    private CreateClaim claimCreate(
            long orderId,
            long adminId,
            WechatExpressEffectiveConfig config,
            NormalizedCreate request,
            LocalDateTime now
    ) {
        OrderSnapshot order = stateStore.loadOrderSnapshot(
                orderId, true, afterSaleFulfillmentPolicy.blockingStatuses()
        );
        AttemptRow existing = stateStore.findByIdempotency(orderId, request.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(request.digest())) {
                throw conflict();
            }
            boolean recover = existing.status() == ElectronicWaybillStatus.UNKNOWN
                    || existing.status() == ElectronicWaybillStatus.CREATING && stale(existing, now);
            return new CreateClaim(existing, null, false, recover);
        }

        List<String> blockers = blockers(config, order, stateStore.activeAttemptExists(orderId));
        if (!blockers.isEmpty()) {
            throw conflict();
        }
        int attemptNo = stateStore.nextAttemptNo(orderId);
        String providerOrderId = providerOrderId(order.order().orderNo(), attemptNo);
        AttemptRow created = stateStore.insertCreating(new ElectronicWaybillStateStore.CreateInsert(
                order,
                config,
                request.parcel(),
                attemptNo,
                request.idempotencyKey(),
                request.digest(),
                providerOrderId,
                request.remark(),
                request.expectTime(),
                adminId
        ), now);
        return new CreateClaim(created, addRequest(created, order), true, false);
    }

    private AttemptRow finishCreate(AttemptRow claimed, WechatElectronicWaybillResult result) {
        LocalDateTime completedAt = now();
        ElectronicWaybillStatus status;
        ElectronicWaybillPendingOperation pending;
        String waybillId = claimed.waybillId();
        String errorCode = safeCode(result.errorCode());
        String errorMessage = safeMessage(result.errorMessage());
        if (validSuccessIdentity(claimed, result, true) && result.orderStatus() == 0) {
            status = ElectronicWaybillStatus.CREATED;
            pending = ElectronicWaybillPendingOperation.NONE;
            waybillId = result.waybillId().trim();
            errorCode = "";
            errorMessage = "";
        } else if (result.outcome() == WechatProviderOutcome.REJECTED
                || result.outcome() == WechatProviderOutcome.UNAVAILABLE) {
            status = ElectronicWaybillStatus.FAILED;
            pending = ElectronicWaybillPendingOperation.NONE;
        } else {
            status = ElectronicWaybillStatus.UNKNOWN;
            pending = ElectronicWaybillPendingOperation.CREATE;
            if (result.outcome() == WechatProviderOutcome.SUCCESS) {
                errorCode = "RESPONSE_IDENTITY_MISMATCH";
                errorMessage = "WeChat electronic waybill response identity did not match";
            }
        }
        stateStore.finishCreate(
                claimed.id(), status, pending, waybillId,
                errorCode, errorMessage, completedAt
        );
        return stateStore.requireAttempt(claimed.orderId(), claimed.id(), false);
    }

    private ElectronicWaybillAttemptResponse refreshInternal(long orderId, long recordId) {
        LocalDateTime claimedAt = now();
        RefreshClaim claim = transaction.execute(status -> {
            stateStore.loadOrderSnapshot(
                    orderId, true, afterSaleFulfillmentPolicy.blockingStatuses()
            );
            AttemptRow row = stateStore.requireAttempt(orderId, recordId, true);
            if (row.pendingOperation() == ElectronicWaybillPendingOperation.REFRESH
                    && !stale(row, claimedAt)) {
                return new RefreshClaim(row, false);
            }
            if ((row.status() == ElectronicWaybillStatus.CREATING
                    || row.status() == ElectronicWaybillStatus.CANCELING)
                    && !stale(row, claimedAt)) {
                return new RefreshClaim(row, false);
            }
            if (row.status() != ElectronicWaybillStatus.CREATING
                    && row.status() != ElectronicWaybillStatus.CREATED
                    && row.status() != ElectronicWaybillStatus.CANCELING
                    && row.status() != ElectronicWaybillStatus.UNKNOWN) {
                throw conflict();
            }
            if (!stateStore.claimRefresh(row, claimedAt)) {
                throw conflict();
            }
            return new RefreshClaim(row, true);
        });
        if (claim == null) {
            throw unavailable();
        }
        if (!claim.dispatch()) {
            return toResponse(claim.row());
        }
        WechatElectronicWaybillResult result = callProvider(() -> provider.get(
                getRequest(claim.row(), null)
        ));
        AttemptRow finalized = transaction.execute(status -> finishRefresh(claim.row(), result));
        return toResponse(requireResult(finalized));
    }

    private AttemptRow finishRefresh(AttemptRow claimed, WechatElectronicWaybillResult result) {
        LocalDateTime completedAt = now();
        ElectronicWaybillStatus status;
        ElectronicWaybillPendingOperation pending;
        String waybillId = claimed.waybillId();
        LocalDateTime canceledAt = null;
        String errorCode = safeCode(result.errorCode());
        String errorMessage = safeMessage(result.errorMessage());
        if (validSuccessIdentity(claimed, result, false)) {
            waybillId = result.waybillId().trim();
            errorCode = "";
            errorMessage = "";
            if (result.orderStatus() == 1) {
                status = ElectronicWaybillStatus.CANCELED;
                pending = ElectronicWaybillPendingOperation.NONE;
                canceledAt = completedAt;
            } else {
                status = ElectronicWaybillStatus.CREATED;
                pending = ElectronicWaybillPendingOperation.NONE;
            }
        } else if (claimed.status() == ElectronicWaybillStatus.CREATED) {
            status = ElectronicWaybillStatus.CREATED;
            pending = ElectronicWaybillPendingOperation.NONE;
            if (result.outcome() == WechatProviderOutcome.SUCCESS) {
                errorCode = "RESPONSE_IDENTITY_MISMATCH";
                errorMessage = "WeChat electronic waybill response identity did not match";
            }
        } else {
            status = ElectronicWaybillStatus.UNKNOWN;
            pending = recoveryOperation(claimed);
            if (result.outcome() == WechatProviderOutcome.SUCCESS) {
                errorCode = "RESPONSE_IDENTITY_MISMATCH";
                errorMessage = "WeChat electronic waybill response identity did not match";
            }
        }
        stateStore.finishRefresh(
                claimed, status, pending, waybillId,
                errorCode, errorMessage, canceledAt, completedAt
        );
        return stateStore.requireAttempt(claimed.orderId(), claimed.id(), false);
    }

    private AttemptRow finishCancel(AttemptRow claimed, WechatElectronicWaybillResult result) {
        LocalDateTime completedAt = now();
        ElectronicWaybillStatus status;
        ElectronicWaybillPendingOperation pending;
        LocalDateTime canceledAt = null;
        String errorCode = safeCode(result.errorCode());
        String errorMessage = safeMessage(result.errorMessage());
        if (result.outcome() == WechatProviderOutcome.SUCCESS) {
            status = ElectronicWaybillStatus.CANCELED;
            pending = ElectronicWaybillPendingOperation.NONE;
            canceledAt = completedAt;
            errorCode = "";
            errorMessage = "";
        } else if (result.outcome() == WechatProviderOutcome.REJECTED) {
            status = ElectronicWaybillStatus.CREATED;
            pending = ElectronicWaybillPendingOperation.NONE;
        } else {
            status = ElectronicWaybillStatus.UNKNOWN;
            pending = ElectronicWaybillPendingOperation.CANCEL;
        }
        stateStore.finishCancel(
                claimed.id(), status, pending, errorCode, errorMessage, canceledAt, completedAt
        );
        return stateStore.requireAttempt(claimed.orderId(), claimed.id(), false);
    }

    private List<String> blockers(
            WechatExpressEffectiveConfig config,
            OrderSnapshot order,
            boolean activeAttempt
    ) {
        List<String> blockers = new ArrayList<>();
        if (config.mode() == WechatExpressMode.DISABLED) {
            blockers.add("电子面单功能未启用，请先完成配置");
        } else if (!completeConfig(config)) {
            blockers.add("电子面单配置不完整，请先完成配置");
        }
        if (!"PAID".equals(order.order().status())) {
            blockers.add("订单不是待发货状态，不能生成电子面单");
        }
        if (order.blockingAfterSale()) {
            blockers.add("订单存在进行中的售后，暂不能生成电子面单");
        }
        if (!completeReceiver(order.order())) {
            blockers.add("订单缺少结构化收货地址，请买家重新选择地址");
        }
        if (order.payment() == null || !StringUtils.hasText(order.payment().payerOpenid())) {
            blockers.add("订单缺少微信支付 OpenID，不能生成电子面单");
        }
        if (order.payment() == null || !StringUtils.hasText(order.payment().transactionId())) {
            blockers.add("订单缺少微信支付交易号，不能生成电子面单");
        }
        if (order.items().isEmpty()) {
            blockers.add("订单缺少商品快照，不能生成电子面单");
        } else if (order.items().stream().anyMatch(item -> !validItem(item))) {
            blockers.add("商品图片必须是公开 HTTPS 地址");
        }
        if (order.shipmentExists()) {
            blockers.add("订单已存在发货记录");
        }
        if (activeAttempt) {
            blockers.add("订单已有待处理的电子面单，请先恢复或取消");
        }
        return blockers;
    }

    private boolean completeConfig(WechatExpressEffectiveConfig config) {
        WechatExpressAccount account = config.account();
        return completeSender(config.sender())
                && account != null
                && StringUtils.hasText(account.deliveryId())
                && StringUtils.hasText(account.deliveryName())
                && StringUtils.hasText(account.bizId())
                && account.serviceType() != null
                && account.serviceType() >= 0
                && StringUtils.hasText(account.serviceName())
                && validParcel(config.defaultParcel());
    }

    private boolean completeSender(WechatExpressSender sender) {
        return sender != null
                && StringUtils.hasText(sender.name())
                && StringUtils.hasText(sender.mobile())
                && StringUtils.hasText(sender.province())
                && StringUtils.hasText(sender.city())
                && StringUtils.hasText(sender.district())
                && StringUtils.hasText(sender.detailAddress());
    }

    private boolean completeReceiver(OrderRow order) {
        return order != null
                && StringUtils.hasText(order.receiverName())
                && StringUtils.hasText(order.receiverPhone())
                && StringUtils.hasText(order.receiverProvince())
                && StringUtils.hasText(order.receiverCity())
                && StringUtils.hasText(order.receiverDistrict())
                && StringUtils.hasText(order.receiverDetailAddress());
    }

    private boolean validItem(ItemSnapshot item) {
        return item != null
                && StringUtils.hasText(item.title())
                && item.quantity() > 0
                && publicHttps(image(item));
    }

    private boolean publicHttps(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean validParcel(WechatExpressParcel parcel) {
        return parcel != null
                && parcel.count() == 1
                && validDecimal(parcel.weightKg(), 3, MAX_WEIGHT_EXCLUSIVE)
                && validDecimal(parcel.lengthCm(), 2, MAX_DIMENSION_EXCLUSIVE)
                && validDecimal(parcel.widthCm(), 2, MAX_DIMENSION_EXCLUSIVE)
                && validDecimal(parcel.heightCm(), 2, MAX_DIMENSION_EXCLUSIVE);
    }

    private boolean validDecimal(BigDecimal value, int scale, BigDecimal maximumExclusive) {
        if (value == null || value.signum() <= 0 || value.compareTo(maximumExclusive) >= 0) {
            return false;
        }
        try {
            value.setScale(scale, RoundingMode.UNNECESSARY);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private NormalizedCreate normalize(ElectronicWaybillCreateRequest request) {
        if (request == null
                || request.count() == null
                || request.count() != 1
                || request.expectTime() != null && request.expectTime() < 0) {
            throw validation();
        }
        String key;
        try {
            key = UUID.fromString(request.idempotencyKey().trim()).toString();
        } catch (RuntimeException ex) {
            throw validation();
        }
        WechatExpressParcel parcel = new WechatExpressParcel(
                request.count(), request.weightKg(), request.lengthCm(),
                request.widthCm(), request.heightCm()
        );
        String remark = trimToEmpty(request.remark());
        if (!validParcel(parcel) || remark.length() > 1024) {
            throw validation();
        }
        String digest = sha256(String.join("\u0000",
                key,
                Integer.toString(parcel.count()),
                decimal(parcel.weightKg()),
                decimal(parcel.lengthCm()),
                decimal(parcel.widthCm()),
                decimal(parcel.heightCm()),
                remark,
                request.expectTime() == null ? "" : request.expectTime().toString()
        ));
        return new NormalizedCreate(key, parcel, remark, request.expectTime(), digest);
    }

    private WechatElectronicWaybillAddRequest addRequest(AttemptRow row, OrderSnapshot order) {
        List<WechatExpressCargoItem> cargoItems = order.items().stream()
                .map(item -> new WechatExpressCargoItem(item.title().trim(), item.quantity()))
                .toList();
        List<WechatExpressShopItem> shopItems = order.items().stream()
                .map(item -> new WechatExpressShopItem(
                        item.title().trim(),
                        image(item),
                        itemDescription(item)
                ))
                .toList();
        return new WechatElectronicWaybillAddRequest(
                row.id(),
                environment(row),
                row.providerOrderId(),
                row.payerOpenid(),
                row.deliveryId(),
                row.bizId(),
                blankToNull(row.remark()),
                contact(row.sender()),
                contact(row.receiver()),
                row.parcel().count(),
                row.parcel().weightKg(),
                row.parcel().lengthCm(),
                row.parcel().widthCm(),
                row.parcel().heightCm(),
                cargoItems,
                "pages/order/detail/detail?order_id=" + row.orderId(),
                shopItems,
                row.serviceType(),
                row.serviceName(),
                row.expectTime()
        );
    }

    private WechatExpressContact contact(WechatExpressSender sender) {
        return new WechatExpressContact(
                sender.name(), sender.mobile(), sender.company(), "中国",
                sender.province(), sender.city(), sender.district(), sender.detailAddress()
        );
    }

    private WechatExpressContact contact(ElectronicWaybillStateStore.ReceiverSnapshot receiver) {
        return new WechatExpressContact(
                receiver.name(), receiver.phone(), "", "中国",
                receiver.province(), receiver.city(), receiver.district(),
                joinAddress(receiver.detailAddress(), receiver.locationName(), receiver.doorplate())
        );
    }

    private WechatElectronicWaybillGetRequest getRequest(AttemptRow row, Integer printType) {
        return new WechatElectronicWaybillGetRequest(
                row.id(), row.providerOrderId(), row.payerOpenid(),
                row.deliveryId(), blankToNull(row.waybillId()), printType
        );
    }

    private boolean validSuccessIdentity(
            AttemptRow row,
            WechatElectronicWaybillResult result,
            boolean add
    ) {
        if (result.outcome() != WechatProviderOutcome.SUCCESS
                || !row.providerOrderId().equals(result.providerOrderId())
                || !row.deliveryId().equals(result.deliveryId())
                || !StringUtils.hasText(result.waybillId())
                || result.orderStatus() == null
                || result.orderStatus() < 0
                || result.orderStatus() > 1) {
            return false;
        }
        return add || !StringUtils.hasText(row.waybillId())
                || row.waybillId().equals(result.waybillId());
    }

    private byte[] validPrintHtml(AttemptRow row, WechatElectronicWaybillResult result) {
        if (!validSuccessIdentity(row, result, false)
                || result.orderStatus() != 0
                || !StringUtils.hasText(result.printHtmlBase64())
                || result.printHtmlBase64().length() > MAX_PRINT_HTML_BASE64_LENGTH) {
            throw unavailable();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(result.printHtmlBase64());
            if (decoded.length == 0
                    || decoded.length > MAX_PRINT_HTML_DECODED_LENGTH
                    || !Base64.getEncoder().encodeToString(decoded).equals(result.printHtmlBase64())) {
                throw unavailable();
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    private WechatElectronicWaybillResult callProvider(Supplier<WechatElectronicWaybillResult> call) {
        WechatElectronicWaybillResult result = withoutTransaction.execute(status -> {
            try {
                WechatElectronicWaybillResult value = call.get();
                return value == null
                        ? WechatElectronicWaybillResult.failure(
                        WechatProviderOutcome.UNKNOWN, "EMPTY_RESULT", "WeChat result is unknown")
                        : value;
            } catch (RuntimeException ex) {
                return WechatElectronicWaybillResult.failure(
                        WechatProviderOutcome.UNKNOWN,
                        "REQUEST_AMBIGUOUS",
                        "WeChat electronic waybill result is unknown"
                );
            }
        });
        return result == null
                ? WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.UNKNOWN, "EMPTY_RESULT", "WeChat result is unknown")
                : result;
    }

    private ElectronicWaybillAttemptResponse toResponse(AttemptRow row) {
        LocalDateTime reference = now();
        boolean pendingRefresh = row.pendingOperation() == ElectronicWaybillPendingOperation.REFRESH;
        boolean canRefresh = pendingRefresh
                ? stale(row, reference)
                : row.status() == ElectronicWaybillStatus.CREATED
                || row.status() == ElectronicWaybillStatus.UNKNOWN
                || (row.status() == ElectronicWaybillStatus.CREATING
                || row.status() == ElectronicWaybillStatus.CANCELING) && stale(row, reference);
        boolean idle = row.pendingOperation() == ElectronicWaybillPendingOperation.NONE;
        boolean created = row.status() == ElectronicWaybillStatus.CREATED && idle;
        boolean printable = created
                || row.status() == ElectronicWaybillStatus.CONFIRMED && idle;
        return new ElectronicWaybillAttemptResponse(
                row.id(),
                row.orderId(),
                environment(row),
                row.status(),
                row.deliveryId(),
                row.deliveryName(),
                maskBizId(row),
                row.serviceType(),
                row.serviceName(),
                blankToNull(row.waybillId()),
                row.parcel(),
                blankToNull(row.remark()),
                row.expectTime(),
                row.printCount(),
                row.lastPrintedAt(),
                row.createdAt(),
                row.canceledAt(),
                row.confirmedAt(),
                canRefresh,
                created,
                printable,
                created,
                sandboxAttempt(row)
        );
    }

    private ElectronicWaybillReceiverResponse receiver(OrderRow order) {
        return new ElectronicWaybillReceiverResponse(
                order.receiverName(),
                order.receiverPhone(),
                "",
                order.receiverProvince(),
                order.receiverCity(),
                order.receiverDistrict(),
                order.receiverDetailAddress(),
                trimToEmpty(order.receiverLocationName()),
                trimToEmpty(order.receiverDoorplate())
        );
    }

    private boolean sandboxAttempt(AttemptRow row) {
        return row.status() == ElectronicWaybillStatus.CREATED
                && row.pendingOperation() == ElectronicWaybillPendingOperation.NONE
                && WechatExpressMode.SANDBOX.name().equals(row.mode())
                && WechatExpressConfigService.SANDBOX_DELIVERY_ID.equals(row.deliveryId())
                && WechatExpressConfigService.SANDBOX_BIZ_ID.equals(row.bizId());
    }

    private boolean officialSandbox(WechatExpressEffectiveConfig config) {
        return config.mode() == WechatExpressMode.SANDBOX
                && WechatExpressConfigService.SANDBOX_DELIVERY_ID.equals(config.account().deliveryId())
                && WechatExpressConfigService.SANDBOX_BIZ_ID.equals(config.account().bizId())
                && config.account().serviceType() != null
                && config.account().serviceType() == WechatExpressConfigService.SANDBOX_SERVICE_TYPE
                && WechatExpressConfigService.SANDBOX_SERVICE_NAME.equals(config.account().serviceName());
    }

    private boolean stale(AttemptRow row, LocalDateTime reference) {
        LocalDateTime attemptedAt = row.lastAttemptAt() == null ? row.updatedAt() : row.lastAttemptAt();
        return attemptedAt != null && attemptedAt.isBefore(reference.minus(STALE_OPERATION_THRESHOLD));
    }

    private boolean cancelRecovery(AttemptRow row) {
        return row.status() == ElectronicWaybillStatus.CANCELING
                || row.pendingOperation() == ElectronicWaybillPendingOperation.CANCEL;
    }

    private ElectronicWaybillPendingOperation recoveryOperation(AttemptRow row) {
        if (cancelRecovery(row)) {
            return ElectronicWaybillPendingOperation.CANCEL;
        }
        if (row.pendingOperation() == ElectronicWaybillPendingOperation.CREATE) {
            return ElectronicWaybillPendingOperation.CREATE;
        }
        return ElectronicWaybillPendingOperation.REFRESH;
    }

    private WechatElectronicWaybillEnvironment environment(AttemptRow row) {
        return WechatElectronicWaybillEnvironment.valueOf(row.mode());
    }

    private String maskBizId(AttemptRow row) {
        if (WechatExpressMode.SANDBOX.name().equals(row.mode())) {
            return row.bizId();
        }
        String value = trimToEmpty(row.bizId());
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 2) + "******" + value.substring(value.length() - 2);
    }

    private String image(ItemSnapshot item) {
        if (StringUtils.hasText(item.displayImage())) {
            return item.displayImage().trim();
        }
        if (StringUtils.hasText(item.skuImage())) {
            return item.skuImage().trim();
        }
        return trimToEmpty(item.mainImage());
    }

    private String itemDescription(ItemSnapshot item) {
        if (StringUtils.hasText(item.specText())) {
            return item.specText().trim();
        }
        if (StringUtils.hasText(item.subtitle())) {
            return item.subtitle().trim();
        }
        return item.title().trim() + " x" + item.quantity();
    }

    private String providerOrderId(String orderNo, int attemptNo) {
        String base = trimToEmpty(orderNo).replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.length() > 110) {
            base = base.substring(0, 110);
        }
        return "WB-" + base + "-" + attemptNo;
    }

    private String joinAddress(String... values) {
        return java.util.Arrays.stream(values)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String safeCode(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String safe = value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    private String safeMessage(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String safe = value.trim().replaceAll("[\\r\\n\\t]", " ");
        return safe.substring(0, Math.min(255, safe.length()));
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private long requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.WECHAT_WAYBILL_CONFLICT);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.WECHAT_WAYBILL_UNAVAILABLE);
    }

    private AttemptRow requireResult(AttemptRow row) {
        if (row == null) {
            throw unavailable();
        }
        return row;
    }

    public record ElectronicWaybillPrintData(byte[] html) {
        public ElectronicWaybillPrintData {
            html = html.clone();
        }

        @Override
        public byte[] html() {
            return html.clone();
        }
    }

    private record ContextMaterial(OrderSnapshot order, AttemptRow current, boolean activeAttempt) {
    }

    private record NormalizedCreate(
            String idempotencyKey,
            WechatExpressParcel parcel,
            String remark,
            Long expectTime,
            String digest
    ) {
    }

    private record CreateClaim(
            AttemptRow row,
            WechatElectronicWaybillAddRequest addRequest,
            boolean dispatchAdd,
            boolean recover
    ) {
    }

    private record RefreshClaim(AttemptRow row, boolean dispatch) {
    }

    private record CancelClaim(AttemptRow row, boolean dispatch) {
    }
}
