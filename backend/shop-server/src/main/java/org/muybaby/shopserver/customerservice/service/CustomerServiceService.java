package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.customerservice.CustomerServiceChangedEvent;
import org.muybaby.shopserver.customerservice.CustomerServiceTransferChangedEvent;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.AgentResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.AgentStateResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConsultationContextResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationDetailResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationSummaryResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ImageMessageResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.LinkedOrderResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.LinkedProductResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.MessageResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.SendMessageRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.TransferRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.TransferRequestResponse;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CustomerServiceService {

    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final Set<String> CONVERSATION_STATUSES = Set.of("WAITING", "ACTIVE", "CLOSED");
    private static final Set<String> CONTEXT_TYPES = Set.of("GENERAL", "PRODUCT", "ORDER");
    private static final Set<String> AGENT_WORK_STATUSES = Set.of("AVAILABLE", "BUSY");
    private static final long TRANSFER_REQUEST_TTL_SECONDS = 60L;
    private static final int DEFAULT_AGENT_CAPACITY = 5;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final StorageService storageService;
    private final RealtimeSessionHub realtimeSessionHub;

    public CustomerServiceService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            StorageService storageService,
            RealtimeSessionHub realtimeSessionHub
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.realtimeSessionHub = realtimeSessionHub;
    }

    @Transactional
    public ConversationDetailResponse openForApp(
            AuthenticatedPrincipal principal,
            String requestedContextType,
            Long requestedContextId,
            Long legacyOrderId
    ) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        ConversationRow conversation = findOrCreateConversation(appUserId);
        ContextRequest context = normalizeContext(requestedContextType, requestedContextId, legacyOrderId);
        if ("CLOSED".equals(conversation.status())) {
            conversation = startDraftConsultation(conversation);
        }
        if ("DRAFT".equals(conversation.status())) {
            replaceDraftContext(conversation, context, appUserId);
            return detailForApp(conversation.id(), appUserId);
        }
        if ("ORDER".equals(context.type())) {
            addOrderCard(conversation, context.resourceId(), "APP_USER", appUserId, true);
        } else if ("PRODUCT".equals(context.type())) {
            addProductCard(conversation, context.resourceId(), "APP_USER", appUserId, true);
        }
        publish(conversation.id(), appUserId, "CONVERSATION_OPENED", null);
        return detailForApp(conversation.id(), appUserId);
    }

    @Transactional
    public ConversationDetailResponse currentForApp(AuthenticatedPrincipal principal) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        Optional<ConversationRow> conversation = findConversationByAppUser(appUserId);
        if (conversation.isEmpty()) {
            return null;
        }
        return detailForApp(conversation.get().id(), appUserId);
    }

    @Transactional
    public List<MessageResponse> messagesForApp(AuthenticatedPrincipal principal, Long afterId) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        Optional<ConversationRow> conversation = findConversationByAppUser(appUserId);
        if (conversation.isEmpty()) {
            return List.of();
        }
        jdbcClient.sql("""
                        update customer_service_conversation
                        set app_unread_count = 0, updated_at = :now
                        where id = :conversationId
                        """)
                .param("now", LocalDateTime.now())
                .param("conversationId", conversation.get().id())
                .update();
        return messages(conversation.get().id(), afterId);
    }

    @Transactional
    public MessageResponse sendFromApp(AuthenticatedPrincipal principal, SendMessageRequest request) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        ConversationRow conversation = findOrCreateConversation(appUserId);
        Optional<MessageResponse> duplicate = findClientMessage(
                conversation.id(), "APP_USER", appUserId, request.clientMessageId()
        );
        if (duplicate.isPresent()) {
            return duplicate.get();
        }

        conversation = prepareForAppAction(conversation, appUserId);

        MessageResponse message = insertMessage(
                conversation, "APP_USER", appUserId, "TEXT",
                normalizeMessage(request.content()), null, request.clientMessageId()
        );
        touchForAdminNotification(conversation.id(), message.createdAt());
        publish(conversation.id(), appUserId, "MESSAGE_CREATED", message.messageId());
        return message;
    }

    @Transactional
    public LinkedOrderResponse linkOrderFromApp(AuthenticatedPrincipal principal, Long orderId) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        ConversationRow conversation = findOrCreateConversation(appUserId);
        conversation = prepareForAppAction(conversation, appUserId);
        LinkedOrderResponse linked = addOrderCard(conversation, orderId, "APP_USER", appUserId, true);
        publish(conversation.id(), appUserId, "ORDER_LINKED", null);
        return linked;
    }

    @Transactional
    public LinkedProductResponse linkProductFromApp(AuthenticatedPrincipal principal, Long productId) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        ConversationRow conversation = findOrCreateConversation(appUserId);
        conversation = prepareForAppAction(conversation, appUserId);
        LinkedProductResponse linked = addProductCard(conversation, productId, "APP_USER", appUserId, true);
        publish(conversation.id(), appUserId, "PRODUCT_LINKED", null);
        return linked;
    }

    @Transactional
    public MessageResponse sendImageFromApp(AuthenticatedPrincipal principal, MultipartFile file) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        ConversationRow conversation = findOrCreateConversation(appUserId);
        conversation = prepareForAppAction(conversation, appUserId);
        StorageAssetResponse asset = storageService.uploadCustomerServiceImage(principal, conversation.id(), file);
        MessageResponse message = insertMessage(
                conversation, "APP_USER", appUserId, "IMAGE",
                asset.originalFilename(), asset.id(), null
        );
        touchForAdminNotification(conversation.id(), message.createdAt());
        publish(conversation.id(), appUserId, "MESSAGE_CREATED", message.messageId());
        return message;
    }

    public PageResult<ConversationSummaryResponse> adminPage(String rawStatus, Long rawCurrent, Long rawSize) {
        String status = normalizeStatus(rawStatus);
        long current = rawCurrent == null || rawCurrent < 1 ? 1L : rawCurrent;
        long size = rawSize == null || rawSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(rawSize, MAX_PAGE_SIZE);
        long offset = (current - 1) * size;

        long total = jdbcClient.sql("""
                        select count(*)
                        from customer_service_conversation c
                        where c.status <> 'DRAFT'
                          and (:status = '' or c.status = :status)
                        """)
                .param("status", status)
                .query(Long.class)
                .single();

        List<ConversationSummaryResponse> records = jdbcClient.sql(summarySelect() + """
                        where c.status <> 'DRAFT'
                          and (:status = '' or c.status = :status)
                        order by
                          case c.status when 'WAITING' then 0 when 'ACTIVE' then 1 else 2 end,
                          coalesce(c.last_message_at, c.created_at) desc,
                          c.id desc
                        limit :size offset :offset
                        """)
                .param("status", status)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapConversationSummary)
                .list();
        return PageResult.of(records, total, current, size);
    }

    @Transactional
    public ConversationDetailResponse adminDetail(Long conversationId) {
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        jdbcClient.sql("""
                        update customer_service_conversation
                        set admin_unread_count = 0, updated_at = :now
                        where id = :conversationId
                        """)
                .param("now", LocalDateTime.now())
                .param("conversationId", conversationId)
                .update();
        return detail(conversation.id());
    }

    @Transactional
    public List<MessageResponse> messagesForAdmin(Long conversationId, Long afterId) {
        requireAdminVisibleConversation(conversationId);
        jdbcClient.sql("""
                        update customer_service_conversation
                        set admin_unread_count = 0, updated_at = :now
                        where id = :conversationId
                        """)
                .param("now", LocalDateTime.now())
                .param("conversationId", conversationId)
                .update();
        return messages(conversationId, afterId);
    }

    @Transactional
    public ConversationDetailResponse claim(AuthenticatedPrincipal principal, Long conversationId) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        if ("ACTIVE".equals(conversation.status()) && adminUserId.equals(conversation.assignedAdminUserId())) {
            return detail(conversationId);
        }
        requireCanReceive(adminUserId);
        int updated = jdbcClient.sql("""
                        update customer_service_conversation
                        set status = 'ACTIVE', assigned_admin_user_id = :adminUserId,
                            claimed_at = :now, closed_at = null, admin_unread_count = 0,
                            updated_at = :now
                        where id = :conversationId and status = 'WAITING'
                        """)
                .param("adminUserId", adminUserId)
                .param("now", LocalDateTime.now())
                .param("conversationId", conversationId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        recordAssignment(conversationId, "CLAIM", null, adminUserId, "ADMIN", adminUserId);
        MessageResponse systemMessage = insertSystemMessage(
                conversationId,
                "客服 " + adminDisplayName(adminUserId) + " 已接入"
        );
        touchForAppNotification(conversationId, systemMessage.createdAt());
        publish(conversationId, conversation.appUserId(), "CONVERSATION_CLAIMED", systemMessage.messageId());
        return detail(conversationId);
    }

    @Transactional
    public TransferRequestResponse requestTransfer(
            AuthenticatedPrincipal principal,
            Long conversationId,
            TransferRequest request
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        expireTransferRequests();
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        requireAssigned(conversation, adminUserId);
        Long targetAdminUserId = request.targetAdminUserId();
        requireCustomerServiceAgent(targetAdminUserId);
        if (adminUserId.equals(targetAdminUserId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        requireCanReceive(targetAdminUserId);
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into customer_service_transfer_request
                                (conversation_id, from_admin_user_id, to_admin_user_id,
                                 status, reason_code, reason_note, pending_key,
                                 expires_at, created_at, updated_at)
                            values
                                (:conversationId, :fromAdminUserId, :toAdminUserId,
                                 'PENDING', :reasonCode, :reasonNote, 1,
                                 :expiresAt, :now, :now)
                            """,
                    new MapSqlParameterSource()
                            .addValue("conversationId", conversationId)
                            .addValue("fromAdminUserId", adminUserId)
                            .addValue("toAdminUserId", targetAdminUserId)
                            .addValue("reasonCode", normalizeTransferReason(request.reasonCode()))
                            .addValue("reasonNote", normalizeTransferNote(request.reasonNote()))
                            .addValue("expiresAt", now.plusSeconds(TRANSFER_REQUEST_TTL_SECONDS))
                            .addValue("now", now),
                    keyHolder,
                    new String[]{"id"});
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_TRANSFER_PENDING);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        long requestId = key.longValue();
        recordAssignment(
                conversationId, "TRANSFER_REQUEST", adminUserId, targetAdminUserId, "ADMIN", adminUserId
        );
        TransferRequestResponse response = requireTransferRequest(requestId);
        publishTransfer(response, "REQUESTED");
        return response;
    }

    @Transactional
    public List<TransferRequestResponse> pendingTransferRequests(AuthenticatedPrincipal principal) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        expireTransferRequests();
        return jdbcClient.sql(transferRequestSelect() + """
                        where request.to_admin_user_id = :adminUserId
                          and request.status = 'PENDING'
                          and request.expires_at > :now
                        order by request.created_at, request.id
                        """)
                .param("adminUserId", adminUserId)
                .param("now", LocalDateTime.now())
                .query(this::mapTransferRequest)
                .list();
    }

    @Transactional
    public ConversationDetailResponse acceptTransfer(
            AuthenticatedPrincipal principal,
            Long requestId
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        expireTransferRequests();
        TransferRequestResponse request = requirePendingTransferRequest(requestId, adminUserId);
        requireCanReceive(adminUserId);
        ConversationRow conversation = requireAdminVisibleConversation(request.conversationId());
        if (!"ACTIVE".equals(conversation.status())
                || !request.fromAdminUserId().equals(conversation.assignedAdminUserId())) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        int requestUpdated = jdbcClient.sql("""
                        update customer_service_transfer_request
                        set status = 'ACCEPTED', pending_key = null,
                            resolved_at = :now, updated_at = :now
                        where id = :requestId
                          and status = 'PENDING'
                          and to_admin_user_id = :adminUserId
                          and expires_at > :now
                        """)
                .param("now", now)
                .param("requestId", requestId)
                .param("adminUserId", adminUserId)
                .update();
        if (requestUpdated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_TRANSFER_UNAVAILABLE);
        }
        int conversationUpdated = jdbcClient.sql("""
                        update customer_service_conversation
                        set assigned_admin_user_id = :adminUserId,
                            claimed_at = :now, updated_at = :now
                        where id = :conversationId
                          and status = 'ACTIVE'
                          and assigned_admin_user_id = :fromAdminUserId
                        """)
                .param("adminUserId", adminUserId)
                .param("now", now)
                .param("conversationId", request.conversationId())
                .param("fromAdminUserId", request.fromAdminUserId())
                .update();
        if (conversationUpdated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        recordAssignment(
                request.conversationId(), "TRANSFER",
                request.fromAdminUserId(), adminUserId, "ADMIN", adminUserId
        );
        MessageResponse systemMessage = insertSystemMessage(
                request.conversationId(), "客服 " + adminDisplayName(adminUserId) + " 已接入"
        );
        touchForAppNotification(request.conversationId(), systemMessage.createdAt());
        publish(
                request.conversationId(), conversation.appUserId(),
                "CONVERSATION_TRANSFERRED", systemMessage.messageId()
        );
        publishTransfer(requireTransferRequest(requestId), "ACCEPTED");
        return detail(request.conversationId());
    }

    @Transactional
    public TransferRequestResponse rejectTransfer(
            AuthenticatedPrincipal principal,
            Long requestId
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        expireTransferRequests();
        TransferRequestResponse request = requirePendingTransferRequest(requestId, adminUserId);
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        update customer_service_transfer_request
                        set status = 'REJECTED', pending_key = null,
                            resolved_at = :now, updated_at = :now
                        where id = :requestId
                          and status = 'PENDING'
                          and to_admin_user_id = :adminUserId
                          and expires_at > :now
                        """)
                .param("now", now)
                .param("requestId", requestId)
                .param("adminUserId", adminUserId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_TRANSFER_UNAVAILABLE);
        }
        recordAssignment(
                request.conversationId(), "TRANSFER_REJECT",
                request.fromAdminUserId(), request.toAdminUserId(), "ADMIN", adminUserId
        );
        TransferRequestResponse response = requireTransferRequest(requestId);
        publishTransfer(response, "REJECTED");
        return response;
    }

    @Transactional
    public ConversationDetailResponse release(
            AuthenticatedPrincipal principal,
            Long conversationId
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        boolean manager = principal.permissions().contains("customer-service:agent:manage");
        if (!manager) {
            requireAssigned(conversation, adminUserId);
        } else if (!"ACTIVE".equals(conversation.status()) || conversation.assignedAdminUserId() == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_ASSIGNMENT_REQUIRED);
        }
        Long previousAdminUserId = conversation.assignedAdminUserId();
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        update customer_service_conversation
                        set status = 'WAITING', assigned_admin_user_id = null,
                            claimed_at = null, closed_at = null, updated_at = :now
                        where id = :conversationId
                          and status = 'ACTIVE'
                          and assigned_admin_user_id = :previousAdminUserId
                        """)
                .param("now", now)
                .param("conversationId", conversationId)
                .param("previousAdminUserId", previousAdminUserId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        cancelPendingTransferRequests(conversationId, now);
        recordAssignment(
                conversationId, "RELEASE", previousAdminUserId, null, "ADMIN", adminUserId
        );
        MessageResponse systemMessage = insertSystemMessage(conversationId, "正在为您重新安排客服");
        touchForAppNotification(conversationId, systemMessage.createdAt());
        publish(conversationId, conversation.appUserId(), "CONVERSATION_RELEASED", systemMessage.messageId());
        return detail(conversationId);
    }

    @Transactional
    public ConversationDetailResponse forceTransfer(
            AuthenticatedPrincipal principal,
            Long conversationId,
            TransferRequest request
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        if (!principal.permissions().contains("customer-service:agent:manage")) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        if (!"ACTIVE".equals(conversation.status()) || conversation.assignedAdminUserId() == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_ASSIGNMENT_REQUIRED);
        }
        Long targetAdminUserId = request.targetAdminUserId();
        requireCustomerServiceAgent(targetAdminUserId);
        requireOnline(targetAdminUserId);
        if (targetAdminUserId.equals(conversation.assignedAdminUserId())) {
            return detail(conversationId);
        }
        LocalDateTime now = LocalDateTime.now();
        long auditRequestId = insertResolvedTransferRecord(
                conversationId,
                conversation.assignedAdminUserId(),
                targetAdminUserId,
                normalizeTransferReason(request.reasonCode()),
                normalizeTransferNote(request.reasonNote()),
                now
        );
        int updated = jdbcClient.sql("""
                        update customer_service_conversation
                        set assigned_admin_user_id = :targetAdminUserId,
                            claimed_at = :now, updated_at = :now
                        where id = :conversationId
                          and status = 'ACTIVE'
                          and assigned_admin_user_id = :previousAdminUserId
                        """)
                .param("targetAdminUserId", targetAdminUserId)
                .param("now", now)
                .param("conversationId", conversationId)
                .param("previousAdminUserId", conversation.assignedAdminUserId())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        cancelPendingTransferRequests(conversationId, now);
        recordAssignment(
                conversationId, "FORCE_TRANSFER", conversation.assignedAdminUserId(),
                targetAdminUserId, "ADMIN", adminUserId
        );
        MessageResponse systemMessage = insertSystemMessage(
                conversationId, "客服 " + adminDisplayName(targetAdminUserId) + " 已接入"
        );
        touchForAppNotification(conversationId, systemMessage.createdAt());
        publish(conversationId, conversation.appUserId(), "CONVERSATION_TRANSFERRED", systemMessage.messageId());
        publishTransfer(requireTransferRequest(auditRequestId), "FORCED");
        return detail(conversationId);
    }

    @Transactional
    public int expireTransferRequests() {
        LocalDateTime now = LocalDateTime.now();
        List<TransferRequestResponse> expired = jdbcClient.sql(transferRequestSelect() + """
                        where request.status = 'PENDING'
                          and request.expires_at <= :now
                        order by request.id
                        """)
                .param("now", now)
                .query(this::mapTransferRequest)
                .list();
        int count = 0;
        for (TransferRequestResponse request : expired) {
            int updated = jdbcClient.sql("""
                            update customer_service_transfer_request
                            set status = 'TIMEOUT', pending_key = null,
                                resolved_at = :now, updated_at = :now
                            where id = :requestId and status = 'PENDING'
                            """)
                    .param("now", now)
                    .param("requestId", request.requestId())
                    .update();
            if (updated == 1) {
                count++;
                recordAssignment(
                        request.conversationId(), "TRANSFER_TIMEOUT",
                        request.fromAdminUserId(), request.toAdminUserId(), "SYSTEM", null
                );
                publishTransfer(requireTransferRequest(request.requestId()), "TIMEOUT");
            }
        }
        return count;
    }

    @Transactional
    public ConversationDetailResponse close(AuthenticatedPrincipal principal, Long conversationId) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        requireAssigned(conversation, adminUserId);
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        update customer_service_conversation
                        set status = 'CLOSED', closed_at = :now, updated_at = :now
                        where id = :conversationId
                          and status = 'ACTIVE'
                          and assigned_admin_user_id = :adminUserId
                        """)
                .param("now", now)
                .param("conversationId", conversationId)
                .param("adminUserId", adminUserId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        recordAssignment(conversationId, "CLOSE", adminUserId, null, "ADMIN", adminUserId);
        MessageResponse systemMessage = insertSystemMessage(conversationId, "本次会话已结束");
        touchForAppNotification(conversationId, systemMessage.createdAt());
        publish(conversationId, conversation.appUserId(), "CONVERSATION_CLOSED", systemMessage.messageId());
        return detail(conversationId);
    }

    @Transactional
    public MessageResponse sendFromAdmin(
            AuthenticatedPrincipal principal,
            Long conversationId,
            SendMessageRequest request
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        requireAssigned(conversation, adminUserId);
        Optional<MessageResponse> duplicate = findClientMessage(
                conversationId, "ADMIN", adminUserId, request.clientMessageId()
        );
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        MessageResponse message = insertMessage(
                conversation, "ADMIN", adminUserId, "TEXT",
                normalizeMessage(request.content()), null, request.clientMessageId()
        );
        touchForAppNotification(conversationId, message.createdAt());
        publish(conversationId, conversation.appUserId(), "MESSAGE_CREATED", message.messageId());
        return message;
    }

    public List<LinkedOrderResponse> orderCandidates(Long conversationId) {
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        return orderCandidatesForUser(conversation.appUserId());
    }

    public List<LinkedOrderResponse> orderCandidatesForApp(AuthenticatedPrincipal principal) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        return orderCandidatesForUser(appUserId);
    }

    private List<LinkedOrderResponse> orderCandidatesForUser(Long appUserId) {
        return jdbcClient.sql(orderSelect() + """
                        from shop_order o
                        where o.user_id = :appUserId
                        order by o.created_at desc, o.id desc
                        limit 50
                        """)
                .param("appUserId", appUserId)
                .query(this::mapLinkedOrder)
                .list();
    }

    @Transactional
    public LinkedOrderResponse linkOrderFromAdmin(
            AuthenticatedPrincipal principal,
            Long conversationId,
            Long orderId
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        requireAssigned(conversation, adminUserId);
        LinkedOrderResponse linked = addOrderCard(conversation, orderId, "ADMIN", adminUserId, true);
        publish(conversationId, conversation.appUserId(), "ORDER_LINKED", null);
        return linked;
    }

    public List<LinkedProductResponse> productCandidates(Long conversationId, String keyword) {
        requireAdminVisibleConversation(conversationId);
        return productCandidates(keyword);
    }

    public List<LinkedProductResponse> productCandidatesForApp(
            AuthenticatedPrincipal principal,
            String keyword
    ) {
        requirePrincipal(principal, TokenKind.APP);
        return productCandidates(keyword);
    }

    private List<LinkedProductResponse> productCandidates(String keyword) {
        String keywordLike = StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
        return jdbcClient.sql(productSelect() + """
                        where p.deleted_at is null
                          and p.status = 'ON_SALE'
                          and (:keywordLike is null or p.title like :keywordLike)
                        order by p.sort_order, p.id desc
                        limit 50
                        """)
                .param("keywordLike", keywordLike)
                .query(this::mapLinkedProduct)
                .list();
    }

    @Transactional
    public LinkedProductResponse linkProductFromAdmin(
            AuthenticatedPrincipal principal,
            Long conversationId,
            Long productId
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        requireAssigned(conversation, adminUserId);
        LinkedProductResponse linked = addProductCard(conversation, productId, "ADMIN", adminUserId, true);
        publish(conversationId, conversation.appUserId(), "PRODUCT_LINKED", null);
        return linked;
    }

    @Transactional
    public MessageResponse sendImageFromAdmin(
            AuthenticatedPrincipal principal,
            Long conversationId,
            MultipartFile file
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        ConversationRow conversation = requireAdminVisibleConversation(conversationId);
        requireAssigned(conversation, adminUserId);
        StorageAssetResponse asset = storageService.uploadCustomerServiceImage(principal, conversation.id(), file);
        MessageResponse message = insertMessage(
                conversation, "ADMIN", adminUserId, "IMAGE",
                asset.originalFilename(), asset.id(), null
        );
        touchForAppNotification(conversationId, message.createdAt());
        publish(conversationId, conversation.appUserId(), "MESSAGE_CREATED", message.messageId());
        return message;
    }

    public ResponseEntity<InputStreamResource> imageForApp(
            AuthenticatedPrincipal principal,
            Long messageId
    ) {
        Long appUserId = requirePrincipal(principal, TokenKind.APP);
        ImageReference image = requireImageReference(messageId);
        ConversationRow conversation = requireConversation(image.conversationId());
        if (!conversation.appUserId().equals(appUserId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE);
        }
        return storageService.customerServiceImageResource(image.assetId(), conversation.id());
    }

    public ResponseEntity<InputStreamResource> imageForAdmin(
            AuthenticatedPrincipal principal,
            Long messageId
    ) {
        requirePrincipal(principal, TokenKind.ADMIN);
        ImageReference image = requireImageReference(messageId);
        requireConversation(image.conversationId());
        return storageService.customerServiceImageResource(image.assetId(), image.conversationId());
    }

    public List<AgentResponse> agents() {
        return jdbcClient.sql("""
                        select u.id, u.username, u.display_name, u.avatar,
                               coalesce(state.work_status, 'BUSY') as manual_work_status,
                               coalesce(state.max_active_conversations, 5) as max_active_conversations,
                               (select count(*)
                                from customer_service_conversation conversation
                                where conversation.status = 'ACTIVE'
                                  and conversation.assigned_admin_user_id = u.id)
                                   as active_conversation_count
                        from admin_user u
                        left join customer_service_agent_state state on state.admin_user_id = u.id
                        where u.status = 'ENABLED'
                          and exists (
                            select 1
                            from admin_user_role ur
                            join admin_role r on r.id = ur.role_id
                            where ur.user_id = u.id
                              and r.enabled = true
                              and r.code = 'R_CUSTOMER_SERVICE'
                          )
                        order by u.display_name, u.id
                        """)
                .query((rs, rowNum) -> {
                    long adminUserId = rs.getLong("id");
                    boolean online = realtimeSessionHub.isAdminOnline(adminUserId);
                    String manualWorkStatus = rs.getString("manual_work_status");
                    String effectiveWorkStatus = online ? manualWorkStatus : "OFFLINE";
                    int activeCount = rs.getInt("active_conversation_count");
                    int maxActive = rs.getInt("max_active_conversations");
                    return new AgentResponse(
                            adminUserId,
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("avatar"),
                            online,
                            effectiveWorkStatus,
                            activeCount,
                            maxActive,
                            online && "AVAILABLE".equals(manualWorkStatus) && activeCount < maxActive
                    );
                })
                .list();
    }

    public AgentStateResponse agentState(AuthenticatedPrincipal principal) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        return agentState(adminUserId);
    }

    @Transactional
    public AgentStateResponse updateAgentState(
            AuthenticatedPrincipal principal,
            String rawWorkStatus
    ) {
        Long adminUserId = requirePrincipal(principal, TokenKind.ADMIN);
        String workStatus = normalizeAgentWorkStatus(rawWorkStatus);
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        update customer_service_agent_state
                        set work_status = :workStatus, updated_at = :now
                        where admin_user_id = :adminUserId
                        """)
                .param("workStatus", workStatus)
                .param("now", now)
                .param("adminUserId", adminUserId)
                .update();
        if (updated == 0) {
            try {
                jdbcClient.sql("""
                                insert into customer_service_agent_state
                                    (admin_user_id, work_status, max_active_conversations, updated_at)
                                values
                                    (:adminUserId, :workStatus, :maxActiveConversations, :now)
                                """)
                        .param("adminUserId", adminUserId)
                        .param("workStatus", workStatus)
                        .param("maxActiveConversations", DEFAULT_AGENT_CAPACITY)
                        .param("now", now)
                        .update();
            } catch (DuplicateKeyException ex) {
                jdbcClient.sql("""
                                update customer_service_agent_state
                                set work_status = :workStatus, updated_at = :now
                                where admin_user_id = :adminUserId
                                """)
                        .param("workStatus", workStatus)
                        .param("now", now)
                        .param("adminUserId", adminUserId)
                        .update();
            }
        }
        return agentState(adminUserId);
    }

    private ConversationDetailResponse detailForApp(Long conversationId, Long appUserId) {
        ConversationRow conversation = requireConversation(conversationId);
        if (!conversation.appUserId().equals(appUserId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE);
        }
        jdbcClient.sql("""
                        update customer_service_conversation
                        set app_unread_count = 0, updated_at = :now
                        where id = :conversationId
                        """)
                .param("now", LocalDateTime.now())
                .param("conversationId", conversationId)
                .update();
        return detail(conversationId);
    }

    private ConversationDetailResponse detail(Long conversationId) {
        ConversationSummaryResponse summary = jdbcClient.sql(summarySelect() + " where c.id = :conversationId")
                .param("conversationId", conversationId)
                .query(this::mapConversationSummary)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE));
        return new ConversationDetailResponse(
                summary.conversationId(),
                summary.appUserId(),
                summary.userNickname(),
                summary.status(),
                summary.assignedAdminUserId(),
                summary.assignedAdminDisplayName(),
                summary.lastMessagePreview(),
                summary.lastMessageAt(),
                summary.appUnreadCount(),
                summary.adminUnreadCount(),
                summary.claimedAt(),
                summary.closedAt(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.consultationNo(),
                summary.currentContext(),
                messages(conversationId, null),
                linkedOrders(conversationId, summary.consultationNo()),
                linkedProducts(conversationId, summary.consultationNo())
        );
    }

    private List<MessageResponse> messages(Long conversationId, Long afterId) {
        long effectiveAfterId = afterId == null || afterId < 0 ? 0L : afterId;
        return jdbcClient.sql(messageSelect() + """
                        where m.conversation_id = :conversationId and m.id > :afterId
                        order by m.id
                        limit 500
                        """)
                .param("conversationId", conversationId)
                .param("afterId", effectiveAfterId)
                .query(this::mapMessage)
                .list();
    }

    private List<LinkedOrderResponse> linkedOrders(Long conversationId, int consultationNo) {
        return jdbcClient.sql(orderSelect() + """
                        from customer_service_consultation_resource resource
                        join shop_order o on o.id = resource.resource_id
                        where resource.conversation_id = :conversationId
                          and resource.consultation_no = :consultationNo
                          and resource.resource_type = 'ORDER'
                        order by resource.created_at desc, resource.id desc
                        """)
                .param("conversationId", conversationId)
                .param("consultationNo", consultationNo)
                .query(this::mapLinkedOrder)
                .list();
    }

    private List<LinkedProductResponse> linkedProducts(Long conversationId, int consultationNo) {
        return jdbcClient.sql(productSelect() + """
                        join customer_service_consultation_resource resource
                          on resource.resource_id = p.id
                        where resource.conversation_id = :conversationId
                          and resource.consultation_no = :consultationNo
                          and resource.resource_type = 'PRODUCT'
                        order by resource.created_at desc, resource.id desc
                        """)
                .param("conversationId", conversationId)
                .param("consultationNo", consultationNo)
                .query(this::mapLinkedProduct)
                .list();
    }

    private LinkedOrderResponse addOrderCard(
            ConversationRow conversation,
            Long orderId,
            String addedByType,
            Long addedById,
            boolean updateCurrentContext
    ) {
        LinkedOrderResponse order = requireOwnedOrder(conversation.appUserId(), orderId);
        boolean added = addConsultationResource(
                conversation, "ORDER", orderId, addedByType, addedById
        );
        if (updateCurrentContext) {
            updateContext(conversation.id(), "ORDER", orderId);
        }
        if (added) {
            MessageResponse message = insertMessage(
                    conversation, addedByType, addedById, "ORDER_CARD",
                    "订单 " + order.orderNo(), orderId, null
            );
            touchForRecipient(conversation.id(), addedByType, message.createdAt());
        }
        return order;
    }

    private LinkedProductResponse addProductCard(
            ConversationRow conversation,
            Long productId,
            String addedByType,
            Long addedById,
            boolean updateCurrentContext
    ) {
        LinkedProductResponse product = requireProduct(productId);
        boolean added = addConsultationResource(
                conversation, "PRODUCT", productId, addedByType, addedById
        );
        if (updateCurrentContext) {
            updateContext(conversation.id(), "PRODUCT", productId);
        }
        if (added) {
            MessageResponse message = insertMessage(
                    conversation, addedByType, addedById, "PRODUCT_CARD",
                    product.title(), productId, null
            );
            touchForRecipient(conversation.id(), addedByType, message.createdAt());
        }
        return product;
    }

    private boolean addConsultationResource(
            ConversationRow conversation,
            String resourceType,
            Long resourceId,
            String addedByType,
            Long addedById
    ) {
        try {
            jdbcClient.sql("""
                            insert into customer_service_consultation_resource
                                (conversation_id, consultation_no, resource_type, resource_id,
                                 added_by_type, added_by_id, created_at)
                            values
                                (:conversationId, :consultationNo, :resourceType, :resourceId,
                                 :addedByType, :addedById, :createdAt)
                            """)
                    .param("conversationId", conversation.id())
                    .param("consultationNo", conversation.consultationNo())
                    .param("resourceType", resourceType)
                    .param("resourceId", resourceId)
                    .param("addedByType", addedByType)
                    .param("addedById", addedById)
                    .param("createdAt", LocalDateTime.now())
                    .update();
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private ConversationRow findOrCreateConversation(Long appUserId) {
        Optional<ConversationRow> existing = findConversationByAppUser(appUserId);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into customer_service_conversation
                                (app_user_id, status, app_unread_count, admin_unread_count,
                                 consultation_no, context_type, created_at, updated_at)
                            values
                                (:appUserId, 'DRAFT', 0, 0, 1, 'GENERAL', :now, :now)
                            """,
                    new MapSqlParameterSource()
                            .addValue("appUserId", appUserId)
                            .addValue("now", now),
                    keyHolder,
                    new String[]{"id"});
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE);
            }
            return requireConversation(key.longValue());
        } catch (DuplicateKeyException ignored) {
            return findConversationByAppUser(appUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE));
        }
    }

    private ConversationRow startDraftConsultation(ConversationRow conversation) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        update customer_service_conversation
                        set status = 'DRAFT',
                            assigned_admin_user_id = null,
                            consultation_no = consultation_no + 1,
                            context_type = 'GENERAL',
                            context_id = null,
                            activated_at = null,
                            app_unread_count = 0,
                            admin_unread_count = 0,
                            claimed_at = null,
                            closed_at = null,
                            updated_at = :now
                        where id = :conversationId and status = 'CLOSED'
                        """)
                .param("now", now)
                .param("conversationId", conversation.id())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        return requireConversation(conversation.id());
    }

    private ConversationRow prepareForAppAction(ConversationRow conversation, Long appUserId) {
        ConversationRow prepared = conversation;
        if ("CLOSED".equals(prepared.status())) {
            prepared = startDraftConsultation(prepared);
        }
        if ("DRAFT".equals(prepared.status())) {
            prepared = activateDraft(prepared, appUserId);
        }
        return prepared;
    }

    private void replaceDraftContext(
            ConversationRow conversation,
            ContextRequest context,
            Long appUserId
    ) {
        if (!"DRAFT".equals(conversation.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        if ("ORDER".equals(context.type())) {
            requireOwnedOrder(appUserId, context.resourceId());
        } else if ("PRODUCT".equals(context.type())) {
            requireProduct(context.resourceId());
        }

        jdbcClient.sql("""
                        delete from customer_service_consultation_resource
                        where conversation_id = :conversationId
                          and consultation_no = :consultationNo
                        """)
                .param("conversationId", conversation.id())
                .param("consultationNo", conversation.consultationNo())
                .update();
        updateContext(conversation.id(), context.type(), context.resourceId());
        if (!"GENERAL".equals(context.type())) {
            addConsultationResource(
                    conversation, context.type(), context.resourceId(), "APP_USER", appUserId
            );
        }
    }

    private ConversationRow activateDraft(ConversationRow conversation, Long appUserId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        update customer_service_conversation
                        set status = 'WAITING',
                            activated_at = :now,
                            closed_at = null,
                            updated_at = :now
                        where id = :conversationId and status = 'DRAFT'
                        """)
                .param("now", now)
                .param("conversationId", conversation.id())
                .update();
        if (updated != 1) {
            ConversationRow current = requireConversation(conversation.id());
            if ("WAITING".equals(current.status()) || "ACTIVE".equals(current.status())) {
                return current;
            }
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }

        ConversationRow activated = requireConversation(conversation.id());
        if (activated.consultationNo() > 1) {
            recordAssignment(
                    activated.id(), "REOPEN", null, null, "APP_USER", appUserId
            );
            insertSystemMessage(activated.id(), "新的咨询已开始");
        }
        insertPendingContextCard(activated, appUserId);
        return activated;
    }

    private void insertPendingContextCard(ConversationRow conversation, Long appUserId) {
        ConsultationContextResponse context = contextResponse(
                conversation.contextType(), conversation.contextId(), conversation.appUserId()
        );
        MessageResponse message = null;
        if (context.order() != null) {
            message = insertMessage(
                    conversation, "APP_USER", appUserId, "ORDER_CARD",
                    "订单 " + context.order().orderNo(), context.order().orderId(), null
            );
        } else if (context.product() != null) {
            message = insertMessage(
                    conversation, "APP_USER", appUserId, "PRODUCT_CARD",
                    context.product().title(), context.product().productId(), null
            );
        }
        if (message != null) {
            touchForAdminNotification(conversation.id(), message.createdAt());
        }
    }

    private ContextRequest normalizeContext(String requestedType, Long requestedId, Long legacyOrderId) {
        if (legacyOrderId != null) {
            if (StringUtils.hasText(requestedType) || requestedId != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (legacyOrderId <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return new ContextRequest("ORDER", legacyOrderId);
        }
        String type = StringUtils.hasText(requestedType)
                ? requestedType.trim().toUpperCase()
                : "GENERAL";
        if (!CONTEXT_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if ("GENERAL".equals(type)) {
            if (requestedId != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return new ContextRequest(type, null);
        }
        if (requestedId == null || requestedId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new ContextRequest(type, requestedId);
    }

    private void updateContext(Long conversationId, String contextType, Long contextId) {
        jdbcClient.sql("""
                        update customer_service_conversation
                        set context_type = :contextType,
                            context_id = :contextId,
                            updated_at = :now
                        where id = :conversationId
                        """)
                .param("contextType", contextType)
                .param("contextId", contextId)
                .param("now", LocalDateTime.now())
                .param("conversationId", conversationId)
                .update();
    }

    private LinkedProductResponse requireProduct(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        return jdbcClient.sql(productSelect() + """
                        where p.id = :productId
                          and p.deleted_at is null
                          and p.status = 'ON_SALE'
                        """)
                .param("productId", productId)
                .query(this::mapLinkedProduct)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
    }

    private LinkedOrderResponse requireOwnedOrder(Long appUserId, Long orderId) {
        return jdbcClient.sql(orderSelect() + """
                        from shop_order o
                        where o.id = :orderId and o.user_id = :appUserId
                        """)
                .param("orderId", orderId)
                .param("appUserId", appUserId)
                .query(this::mapLinkedOrder)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_ORDER_UNAVAILABLE));
    }

    private ImageReference requireImageReference(Long messageId) {
        if (messageId == null || messageId <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return jdbcClient.sql("""
                        select conversation_id, resource_id
                        from customer_service_message
                        where id = :messageId
                          and message_type = 'IMAGE'
                          and resource_id is not null
                        """)
                .param("messageId", messageId)
                .query((rs, rowNum) -> new ImageReference(
                        rs.getLong("conversation_id"),
                        rs.getLong("resource_id")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private Optional<ConversationRow> findConversationByAppUser(Long appUserId) {
        return jdbcClient.sql(conversationRowSelect() + " where app_user_id = :appUserId")
                .param("appUserId", appUserId)
                .query(this::mapConversationRow)
                .optional();
    }

    private ConversationRow requireConversation(Long conversationId) {
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE);
        }
        return jdbcClient.sql(conversationRowSelect() + " where id = :conversationId")
                .param("conversationId", conversationId)
                .query(this::mapConversationRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE));
    }

    private ConversationRow requireAdminVisibleConversation(Long conversationId) {
        ConversationRow conversation = requireConversation(conversationId);
        if ("DRAFT".equals(conversation.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONVERSATION_UNAVAILABLE);
        }
        return conversation;
    }

    private MessageResponse insertSystemMessage(Long conversationId, String content) {
        return insertMessage(requireConversation(conversationId), "SYSTEM", null, "SYSTEM", content, null, null);
    }

    private MessageResponse insertMessage(
            ConversationRow conversation,
            String senderType,
            Long senderId,
            String messageType,
            String content,
            Long resourceId,
            String clientMessageId
    ) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into customer_service_message
                            (conversation_id, consultation_no, sender_type, sender_id, message_type,
                             content, resource_id, client_message_id, created_at)
                        values
                            (:conversationId, :consultationNo, :senderType, :senderId, :messageType,
                             :content, :resourceId, :clientMessageId, :createdAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("conversationId", conversation.id())
                        .addValue("consultationNo", conversation.consultationNo())
                        .addValue("senderType", senderType)
                        .addValue("senderId", senderId)
                        .addValue("messageType", messageType)
                        .addValue("content", content)
                        .addValue("resourceId", resourceId)
                        .addValue("clientMessageId", clientMessageId)
                        .addValue("createdAt", now),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        return messages(conversation.id(), key.longValue() - 1).stream()
                .filter(message -> message.messageId().equals(key.longValue()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT));
    }

    private Optional<MessageResponse> findClientMessage(
            Long conversationId,
            String senderType,
            Long senderId,
            String clientMessageId
    ) {
        return jdbcClient.sql(messageSelect() + """
                        where m.conversation_id = :conversationId
                          and m.sender_type = :senderType
                          and m.sender_id = :senderId
                          and m.client_message_id = :clientMessageId
                        """)
                .param("conversationId", conversationId)
                .param("senderType", senderType)
                .param("senderId", senderId)
                .param("clientMessageId", clientMessageId)
                .query(this::mapMessage)
                .optional();
    }

    private void touchForAppNotification(Long conversationId, LocalDateTime at) {
        jdbcClient.sql("""
                        update customer_service_conversation
                        set last_message_at = :at,
                            app_unread_count = app_unread_count + 1,
                            admin_unread_count = 0,
                            updated_at = :at
                        where id = :conversationId
                        """)
                .param("at", at)
                .param("conversationId", conversationId)
                .update();
    }

    private void touchForAdminNotification(Long conversationId, LocalDateTime at) {
        jdbcClient.sql("""
                        update customer_service_conversation
                        set last_message_at = :at,
                            admin_unread_count = admin_unread_count + 1,
                            updated_at = :at
                        where id = :conversationId
                        """)
                .param("at", at)
                .param("conversationId", conversationId)
                .update();
    }

    private void touchForRecipient(Long conversationId, String senderType, LocalDateTime at) {
        if ("ADMIN".equals(senderType)) {
            touchForAppNotification(conversationId, at);
        } else {
            touchForAdminNotification(conversationId, at);
        }
    }

    private void recordAssignment(
            Long conversationId,
            String action,
            Long fromAdminUserId,
            Long toAdminUserId,
            String operatorType,
            Long operatorId
    ) {
        jdbcClient.sql("""
                        insert into customer_service_assignment_log
                            (conversation_id, action, from_admin_user_id, to_admin_user_id,
                             operator_type, operator_id, created_at)
                        values
                            (:conversationId, :action, :fromAdminUserId, :toAdminUserId,
                             :operatorType, :operatorId, :createdAt)
                        """)
                .param("conversationId", conversationId)
                .param("action", action)
                .param("fromAdminUserId", fromAdminUserId)
                .param("toAdminUserId", toAdminUserId)
                .param("operatorType", operatorType)
                .param("operatorId", operatorId)
                .param("createdAt", LocalDateTime.now())
                .update();
    }

    private void requireAssigned(ConversationRow conversation, Long adminUserId) {
        if (!"ACTIVE".equals(conversation.status())
                || conversation.assignedAdminUserId() == null
                || !conversation.assignedAdminUserId().equals(adminUserId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_ASSIGNMENT_REQUIRED);
        }
    }

    private void requireCustomerServiceAgent(Long adminUserId) {
        long count = jdbcClient.sql("""
                        select count(*)
                        from admin_user u
                        join admin_user_role ur on ur.user_id = u.id
                        join admin_role r on r.id = ur.role_id
                        where u.id = :adminUserId
                          and u.status = 'ENABLED'
                          and r.enabled = true
                          and r.code = 'R_CUSTOMER_SERVICE'
                        """)
                .param("adminUserId", adminUserId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    private void requireOnline(Long adminUserId) {
        if (!realtimeSessionHub.isAdminOnline(adminUserId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_AGENT_NOT_AVAILABLE);
        }
    }

    private void requireCanReceive(Long adminUserId) {
        jdbcClient.sql("""
                        select admin_user_id
                        from customer_service_agent_state
                        where admin_user_id = :adminUserId
                        for update
                        """)
                .param("adminUserId", adminUserId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_AGENT_NOT_AVAILABLE));
        AgentStateResponse state = agentState(adminUserId);
        if (!state.online() || !"AVAILABLE".equals(state.workStatus())) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_AGENT_NOT_AVAILABLE);
        }
        if (state.activeConversationCount() >= state.maxActiveConversations()) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_AGENT_CAPACITY_REACHED);
        }
    }

    private AgentStateResponse agentState(Long adminUserId) {
        AgentSnapshot snapshot = jdbcClient.sql("""
                        select u.id,
                               coalesce(state.work_status, 'BUSY') as manual_work_status,
                               coalesce(state.max_active_conversations, 5) as max_active_conversations,
                               (select count(*)
                                from customer_service_conversation conversation
                                where conversation.status = 'ACTIVE'
                                  and conversation.assigned_admin_user_id = u.id)
                                   as active_conversation_count
                        from admin_user u
                        left join customer_service_agent_state state on state.admin_user_id = u.id
                        where u.id = :adminUserId and u.status = 'ENABLED'
                        """)
                .param("adminUserId", adminUserId)
                .query((rs, rowNum) -> new AgentSnapshot(
                        rs.getLong("id"),
                        rs.getString("manual_work_status"),
                        rs.getInt("active_conversation_count"),
                        rs.getInt("max_active_conversations")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));
        boolean online = realtimeSessionHub.isAdminOnline(adminUserId);
        String effectiveWorkStatus = online ? snapshot.manualWorkStatus() : "OFFLINE";
        boolean canReceive = online
                && "AVAILABLE".equals(snapshot.manualWorkStatus())
                && snapshot.activeConversationCount() < snapshot.maxActiveConversations();
        return new AgentStateResponse(
                snapshot.adminUserId(),
                online,
                effectiveWorkStatus,
                snapshot.activeConversationCount(),
                snapshot.maxActiveConversations(),
                canReceive
        );
    }

    private TransferRequestResponse requireTransferRequest(Long requestId) {
        return jdbcClient.sql(transferRequestSelect() + " where request.id = :requestId")
                .param("requestId", requestId)
                .query(this::mapTransferRequest)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SERVICE_TRANSFER_UNAVAILABLE));
    }

    private long insertResolvedTransferRecord(
            Long conversationId,
            Long fromAdminUserId,
            Long toAdminUserId,
            String reasonCode,
            String reasonNote,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into customer_service_transfer_request
                            (conversation_id, from_admin_user_id, to_admin_user_id,
                             status, reason_code, reason_note, pending_key,
                             expires_at, resolved_at, created_at, updated_at)
                        values
                            (:conversationId, :fromAdminUserId, :toAdminUserId,
                             'ACCEPTED', :reasonCode, :reasonNote, null,
                             :now, :now, :now, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("conversationId", conversationId)
                        .addValue("fromAdminUserId", fromAdminUserId)
                        .addValue("toAdminUserId", toAdminUserId)
                        .addValue("reasonCode", reasonCode)
                        .addValue("reasonNote", reasonNote)
                        .addValue("now", now),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        return key.longValue();
    }

    private TransferRequestResponse requirePendingTransferRequest(Long requestId, Long targetAdminUserId) {
        TransferRequestResponse request = requireTransferRequest(requestId);
        if (!"PENDING".equals(request.status()) || !targetAdminUserId.equals(request.toAdminUserId())) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_TRANSFER_UNAVAILABLE);
        }
        return request;
    }

    private void cancelPendingTransferRequests(Long conversationId, LocalDateTime now) {
        List<TransferRequestResponse> pending = jdbcClient.sql(transferRequestSelect() + """
                        where request.conversation_id = :conversationId
                          and request.status = 'PENDING'
                        order by request.id
                        """)
                .param("conversationId", conversationId)
                .query(this::mapTransferRequest)
                .list();
        for (TransferRequestResponse request : pending) {
            int updated = jdbcClient.sql("""
                            update customer_service_transfer_request
                            set status = 'CANCELLED', pending_key = null,
                                resolved_at = :now, updated_at = :now
                            where id = :requestId and status = 'PENDING'
                            """)
                    .param("now", now)
                    .param("requestId", request.requestId())
                    .update();
            if (updated == 1) {
                publishTransfer(requireTransferRequest(request.requestId()), "CANCELLED");
            }
        }
    }

    private String adminDisplayName(Long adminUserId) {
        return jdbcClient.sql("""
                        select display_name
                        from admin_user
                        where id = :adminUserId and status = 'ENABLED'
                        """)
                .param("adminUserId", adminUserId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));
    }

    private Long requirePrincipal(AuthenticatedPrincipal principal, TokenKind kind) {
        if (principal == null || principal.kind() != kind || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private String normalizeStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return "";
        }
        String status = rawStatus.trim().toUpperCase();
        if (!CONVERSATION_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return status;
    }

    private String normalizeAgentWorkStatus(String rawWorkStatus) {
        String workStatus = StringUtils.hasText(rawWorkStatus) ? rawWorkStatus.trim().toUpperCase() : "";
        if (!AGENT_WORK_STATUSES.contains(workStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return workStatus;
    }

    private String normalizeTransferReason(String rawReasonCode) {
        String reasonCode = StringUtils.hasText(rawReasonCode) ? rawReasonCode.trim().toUpperCase() : "";
        if (reasonCode.isEmpty() || reasonCode.length() > 40) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return reasonCode;
    }

    private String normalizeTransferNote(String rawReasonNote) {
        if (!StringUtils.hasText(rawReasonNote)) {
            return null;
        }
        String reasonNote = rawReasonNote.trim();
        if (reasonNote.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return reasonNote;
    }

    private String normalizeMessage(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty() || normalized.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private void publish(Long conversationId, Long appUserId, String changeType, Long messageId) {
        eventPublisher.publishEvent(new CustomerServiceChangedEvent(
                conversationId, appUserId, changeType, messageId
        ));
    }

    private void publishTransfer(TransferRequestResponse request, String changeType) {
        eventPublisher.publishEvent(new CustomerServiceTransferChangedEvent(
                request.requestId(),
                request.conversationId(),
                request.fromAdminUserId(),
                request.toAdminUserId(),
                changeType
        ));
    }

    private ConsultationContextResponse contextResponse(String type, Long resourceId, Long appUserId) {
        String normalizedType = StringUtils.hasText(type) ? type : "GENERAL";
        if ("ORDER".equals(normalizedType) && resourceId != null) {
            LinkedOrderResponse order = jdbcClient.sql(orderSelect() + """
                            from shop_order o
                            where o.id = :resourceId and o.user_id = :appUserId
                            """)
                    .param("resourceId", resourceId)
                    .param("appUserId", appUserId)
                    .query(this::mapLinkedOrder)
                    .optional()
                    .orElse(null);
            return new ConsultationContextResponse(normalizedType, resourceId, order, null);
        }
        if ("PRODUCT".equals(normalizedType) && resourceId != null) {
            LinkedProductResponse product = jdbcClient.sql(productSelect() + " where p.id = :resourceId")
                    .param("resourceId", resourceId)
                    .query(this::mapLinkedProduct)
                    .optional()
                    .orElse(null);
            return new ConsultationContextResponse(normalizedType, resourceId, null, product);
        }
        return new ConsultationContextResponse("GENERAL", null, null, null);
    }

    private String messageSelect() {
        return """
                select m.id, m.conversation_id, m.consultation_no, m.sender_type, m.sender_id,
                       case
                         when m.sender_type = 'APP_USER' then coalesce(app.nickname, '用户')
                         when m.sender_type = 'ADMIN' then coalesce(admin.display_name, '客服')
                         else '系统'
                       end as sender_name,
                       m.message_type, m.content, m.resource_id, m.client_message_id, m.created_at,
                       card_order.id as card_order_id,
                       card_order.order_no as card_order_no,
                       card_order.status as card_order_status,
                       card_order.payable_amount_cent as card_order_amount,
                       card_order.created_at as card_order_created_at,
                       (select item.product_title from order_item item
                        where item.order_id = card_order.id order by item.id limit 1)
                           as card_order_product_title,
                       (select coalesce(item.display_image, item.sku_image, item.main_image)
                        from order_item item
                        where item.order_id = card_order.id order by item.id limit 1)
                           as card_order_product_image,
                       (select coalesce(sum(item.quantity), 0) from order_item item
                        where item.order_id = card_order.id) as card_order_item_count,
                       card_product.id as card_product_id,
                       card_product.title as card_product_title,
                       card_product.main_image as card_product_image,
                       card_product.status as card_product_status,
                       (select min(sku.price_cent) from product_sku sku
                        where sku.spu_id = card_product.id
                          and sku.deleted_at is null and sku.status = 'ENABLED')
                           as card_product_min_price,
                       (select max(sku.price_cent) from product_sku sku
                        where sku.spu_id = card_product.id
                          and sku.deleted_at is null and sku.status = 'ENABLED')
                           as card_product_max_price,
                       image_asset.id as image_asset_id,
                       image_asset.original_filename as image_original_filename,
                       image_asset.content_type as image_content_type,
                       image_asset.width as image_width,
                       image_asset.height as image_height
                from customer_service_message m
                left join app_user app
                  on m.sender_type = 'APP_USER' and app.id = m.sender_id
                left join admin_user admin
                  on m.sender_type = 'ADMIN' and admin.id = m.sender_id
                left join shop_order card_order
                  on m.message_type = 'ORDER_CARD' and card_order.id = m.resource_id
                left join product_spu card_product
                  on m.message_type = 'PRODUCT_CARD' and card_product.id = m.resource_id
                left join storage_asset image_asset
                  on m.message_type = 'IMAGE' and image_asset.id = m.resource_id
                """;
    }

    private String transferRequestSelect() {
        return """
                select request.id, request.conversation_id,
                       conversation.app_user_id, app.nickname as user_nickname,
                       (select case message.message_type
                                  when 'IMAGE' then '[图片]'
                                  when 'ORDER_CARD' then '[订单]'
                                  when 'PRODUCT_CARD' then '[商品]'
                                  else message.content
                                end
                        from customer_service_message message
                        where message.conversation_id = request.conversation_id
                        order by message.id desc limit 1) as last_message_preview,
                       conversation.context_type, conversation.context_id,
                       request.from_admin_user_id, from_admin.display_name as from_admin_display_name,
                       request.to_admin_user_id, to_admin.display_name as to_admin_display_name,
                       request.status, request.reason_code, request.reason_note,
                       request.expires_at, request.resolved_at,
                       request.created_at, request.updated_at
                from customer_service_transfer_request request
                join customer_service_conversation conversation on conversation.id = request.conversation_id
                join app_user app on app.id = conversation.app_user_id
                join admin_user from_admin on from_admin.id = request.from_admin_user_id
                join admin_user to_admin on to_admin.id = request.to_admin_user_id
                """;
    }

    private String orderSelect() {
        return """
                select o.id as order_id, o.order_no, o.status, o.payable_amount_cent,
                       (select item.product_title from order_item item
                        where item.order_id = o.id order by item.id limit 1) as primary_product_title,
                       (select coalesce(item.display_image, item.sku_image, item.main_image)
                        from order_item item
                        where item.order_id = o.id order by item.id limit 1) as primary_product_image,
                       (select coalesce(sum(item.quantity), 0) from order_item item
                        where item.order_id = o.id) as item_count,
                       o.created_at
                """;
    }

    private String productSelect() {
        return """
                select p.id as product_id, p.title as product_title,
                       p.main_image as product_image, p.status as product_status,
                       (select min(sku.price_cent) from product_sku sku
                        where sku.spu_id = p.id
                          and sku.deleted_at is null and sku.status = 'ENABLED') as min_price_cent,
                       (select max(sku.price_cent) from product_sku sku
                        where sku.spu_id = p.id
                          and sku.deleted_at is null and sku.status = 'ENABLED') as max_price_cent
                from product_spu p
                """;
    }

    private String conversationRowSelect() {
        return """
                select id, app_user_id, status, assigned_admin_user_id, last_message_at,
                       app_unread_count, admin_unread_count, claimed_at, closed_at,
                       created_at, updated_at, consultation_no, context_type, context_id
                from customer_service_conversation
                """;
    }

    private String summarySelect() {
        return """
                select c.id, c.app_user_id, app.nickname as user_nickname, c.status,
                       c.assigned_admin_user_id, admin.display_name as assigned_admin_display_name,
                       (select case m.message_type
                                  when 'IMAGE' then '[图片]'
                                  when 'ORDER_CARD' then '[订单]'
                                  when 'PRODUCT_CARD' then '[商品]'
                                  else m.content
                                end
                        from customer_service_message m
                        where m.conversation_id = c.id
                        order by m.id desc
                        limit 1) as last_message_preview,
                       c.last_message_at, c.app_unread_count, c.admin_unread_count,
                       c.claimed_at, c.closed_at, c.created_at, c.updated_at,
                       c.consultation_no, c.context_type, c.context_id
                from customer_service_conversation c
                join app_user app on app.id = c.app_user_id
                left join admin_user admin on admin.id = c.assigned_admin_user_id
                """;
    }

    private ConversationRow mapConversationRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationRow(
                rs.getLong("id"),
                rs.getLong("app_user_id"),
                rs.getString("status"),
                nullableLong(rs, "assigned_admin_user_id"),
                localDateTime(rs, "last_message_at"),
                rs.getInt("app_unread_count"),
                rs.getInt("admin_unread_count"),
                localDateTime(rs, "claimed_at"),
                localDateTime(rs, "closed_at"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getInt("consultation_no"),
                rs.getString("context_type"),
                nullableLong(rs, "context_id")
        );
    }

    private ConversationSummaryResponse mapConversationSummary(ResultSet rs, int rowNum) throws SQLException {
        long appUserId = rs.getLong("app_user_id");
        return new ConversationSummaryResponse(
                rs.getLong("id"),
                appUserId,
                rs.getString("user_nickname"),
                rs.getString("status"),
                nullableLong(rs, "assigned_admin_user_id"),
                rs.getString("assigned_admin_display_name"),
                rs.getString("last_message_preview"),
                localDateTime(rs, "last_message_at"),
                rs.getInt("app_unread_count"),
                rs.getInt("admin_unread_count"),
                localDateTime(rs, "claimed_at"),
                localDateTime(rs, "closed_at"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getInt("consultation_no"),
                contextResponse(
                        rs.getString("context_type"),
                        nullableLong(rs, "context_id"),
                        appUserId
                )
        );
    }

    private MessageResponse mapMessage(ResultSet rs, int rowNum) throws SQLException {
        LinkedOrderResponse order = nullableLong(rs, "card_order_id") == null
                ? null
                : mapOrderCard(rs);
        LinkedProductResponse product = nullableLong(rs, "card_product_id") == null
                ? null
                : mapProductCard(rs);
        ImageMessageResponse image = nullableLong(rs, "image_asset_id") == null
                ? null
                : new ImageMessageResponse(
                        rs.getString("image_original_filename"),
                        rs.getString("image_content_type"),
                        rs.getObject("image_width", Integer.class),
                        rs.getObject("image_height", Integer.class)
                );
        return new MessageResponse(
                rs.getLong("id"),
                rs.getLong("conversation_id"),
                rs.getInt("consultation_no"),
                rs.getString("sender_type"),
                nullableLong(rs, "sender_id"),
                rs.getString("sender_name"),
                rs.getString("message_type"),
                rs.getString("content"),
                nullableLong(rs, "resource_id"),
                order,
                product,
                image,
                rs.getString("client_message_id"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private LinkedOrderResponse mapLinkedOrder(ResultSet rs, int rowNum) throws SQLException {
        return new LinkedOrderResponse(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("payable_amount_cent"),
                rs.getString("primary_product_title"),
                rs.getString("primary_product_image"),
                rs.getInt("item_count"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private LinkedProductResponse mapLinkedProduct(ResultSet rs, int rowNum) throws SQLException {
        return new LinkedProductResponse(
                rs.getLong("product_id"),
                rs.getString("product_title"),
                rs.getString("product_image"),
                nullableLong(rs, "min_price_cent"),
                nullableLong(rs, "max_price_cent"),
                rs.getString("product_status")
        );
    }

    private TransferRequestResponse mapTransferRequest(ResultSet rs, int rowNum) throws SQLException {
        return new TransferRequestResponse(
                rs.getLong("id"),
                rs.getLong("conversation_id"),
                rs.getLong("app_user_id"),
                rs.getString("user_nickname"),
                rs.getString("last_message_preview"),
                contextResponse(
                        rs.getString("context_type"),
                        nullableLong(rs, "context_id"),
                        rs.getLong("app_user_id")
                ),
                rs.getLong("from_admin_user_id"),
                rs.getString("from_admin_display_name"),
                rs.getLong("to_admin_user_id"),
                rs.getString("to_admin_display_name"),
                rs.getString("status"),
                rs.getString("reason_code"),
                rs.getString("reason_note"),
                rs.getTimestamp("expires_at").toLocalDateTime(),
                localDateTime(rs, "resolved_at"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private LinkedOrderResponse mapOrderCard(ResultSet rs) throws SQLException {
        return new LinkedOrderResponse(
                rs.getLong("card_order_id"),
                rs.getString("card_order_no"),
                rs.getString("card_order_status"),
                rs.getLong("card_order_amount"),
                rs.getString("card_order_product_title"),
                rs.getString("card_order_product_image"),
                rs.getInt("card_order_item_count"),
                rs.getTimestamp("card_order_created_at").toLocalDateTime()
        );
    }

    private LinkedProductResponse mapProductCard(ResultSet rs) throws SQLException {
        return new LinkedProductResponse(
                rs.getLong("card_product_id"),
                rs.getString("card_product_title"),
                rs.getString("card_product_image"),
                nullableLong(rs, "card_product_min_price"),
                nullableLong(rs, "card_product_max_price"),
                rs.getString("card_product_status")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private record ConversationRow(
            Long id,
            Long appUserId,
            String status,
            Long assignedAdminUserId,
            LocalDateTime lastMessageAt,
            int appUnreadCount,
            int adminUnreadCount,
            LocalDateTime claimedAt,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            int consultationNo,
            String contextType,
            Long contextId
    ) {
    }

    private record ContextRequest(String type, Long resourceId) {
    }

    private record AgentSnapshot(
            Long adminUserId,
            String manualWorkStatus,
            int activeConversationCount,
            int maxActiveConversations
    ) {
    }

    private record ImageReference(Long conversationId, Long assetId) {
    }
}
