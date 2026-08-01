package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.AutoReplyConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonQuestionRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonQuestionResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonQuestionSummaryResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.OfflineAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyCreateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyGroupResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.SmartAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.SmartReplyRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.SmartReplyResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.WelcomeAutoReplyUpdateRequest;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CustomerServiceReplyService {

    private static final long CONFIG_ID = 1L;
    private static final int MAX_COMMON_QUESTIONS = 20;
    private static final int MAX_SMART_REPLIES = 100;
    private static final int MAX_SMART_QUESTIONS = 20;
    private static final Pattern QUESTION_NOISE = Pattern.compile("[\\p{P}\\p{S}\\s]+");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final RealtimeSessionHub realtimeSessionHub;

    public CustomerServiceReplyService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            RealtimeSessionHub realtimeSessionHub
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.realtimeSessionHub = realtimeSessionHub;
    }

    public AutoReplyConfigResponse autoReplies(Long adminUserId) {
        AutoReplyConfigRow config = configRow();
        return new AutoReplyConfigResponse(
                config.revision(),
                config.openingMessage(),
                welcomeMessage(adminUserId),
                config.offlineMessage(),
                commonQuestions(),
                smartReplies()
        );
    }

    @Transactional
    public AutoReplyConfigResponse updateCommon(
            Long adminUserId,
            CommonAutoReplyUpdateRequest request
    ) {
        List<CommonQuestionRequest> questions = request.commonQuestions();
        if (questions.size() > MAX_COMMON_QUESTIONS) {
            throw invalidConfig();
        }
        LocalDateTime now = LocalDateTime.now();
        claimConfigRevision(
                request.revision(),
                adminUserId,
                now,
                optionalText(request.openingMessage(), 2000),
                null
        );
        Set<Long> existingIds = new LinkedHashSet<>(jdbcClient.sql("""
                        select id
                        from customer_service_common_question
                        order by id
                        for update
                        """)
                .query(Long.class)
                .list());
        Set<Long> retainedIds = new HashSet<>();
        for (int index = 0; index < questions.size(); index++) {
            CommonQuestionRequest question = questions.get(index);
            String normalizedQuestion = requiredText(question.question(), 200);
            String normalizedAnswer = requiredText(question.answer(), 2000);
            int sortOrder = sortOrder(question.sortOrder(), index);
            if (question.questionId() == null) {
                jdbcClient.sql("""
                                insert into customer_service_common_question
                                    (question, answer, enabled, sort_order,
                                     updated_by, created_at, updated_at)
                                values
                                    (:question, :answer, :enabled, :sortOrder,
                                     :adminUserId, :now, :now)
                                """)
                        .param("question", normalizedQuestion)
                        .param("answer", normalizedAnswer)
                        .param("enabled", Boolean.TRUE.equals(question.enabled()))
                        .param("sortOrder", sortOrder)
                        .param("adminUserId", adminUserId)
                        .param("now", now)
                        .update();
                continue;
            }
            if (!existingIds.contains(question.questionId())
                    || !retainedIds.add(question.questionId())) {
                throw invalidConfig();
            }
            jdbcClient.sql("""
                            update customer_service_common_question
                            set question = :question,
                                answer = :answer,
                                enabled = :enabled,
                                sort_order = :sortOrder,
                                updated_by = :adminUserId,
                                updated_at = :now
                            where id = :questionId
                            """)
                    .param("question", normalizedQuestion)
                    .param("answer", normalizedAnswer)
                    .param("enabled", Boolean.TRUE.equals(question.enabled()))
                    .param("sortOrder", sortOrder)
                    .param("adminUserId", adminUserId)
                    .param("now", now)
                    .param("questionId", question.questionId())
                    .update();
        }
        for (Long existingId : existingIds) {
            if (!retainedIds.contains(existingId)) {
                jdbcClient.sql("delete from customer_service_common_question where id = :id")
                        .param("id", existingId)
                        .update();
            }
        }
        return autoReplies(adminUserId);
    }

    @Transactional
    public AutoReplyConfigResponse updateWelcome(
            Long adminUserId,
            WelcomeAutoReplyUpdateRequest request
    ) {
        int updated = jdbcClient.sql("""
                        update customer_service_agent_profile
                        set welcome_message = :content,
                            updated_by = :adminUserId,
                            updated_at = :now
                        where admin_user_id = :adminUserId
                        """)
                .param("content", optionalText(request.content(), 2000))
                .param("adminUserId", adminUserId)
                .param("now", LocalDateTime.now())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        return autoReplies(adminUserId);
    }

    @Transactional
    public AutoReplyConfigResponse updateOffline(
            Long adminUserId,
            OfflineAutoReplyUpdateRequest request
    ) {
        claimConfigRevision(
                request.revision(),
                adminUserId,
                LocalDateTime.now(),
                null,
                optionalText(request.content(), 2000)
        );
        return autoReplies(adminUserId);
    }

    @Transactional
    public AutoReplyConfigResponse updateSmart(
            Long adminUserId,
            SmartAutoReplyUpdateRequest request
    ) {
        List<SmartReplyRequest> replies = request.smartReplies();
        if (replies.size() > MAX_SMART_REPLIES) {
            throw invalidConfig();
        }
        LocalDateTime now = LocalDateTime.now();
        claimConfigRevision(request.revision(), adminUserId, now, null, null);
        Set<Long> existingIds = new LinkedHashSet<>(jdbcClient.sql("""
                        select id
                        from customer_service_smart_reply_group
                        order by id
                        for update
                        """)
                .query(Long.class)
                .list());
        Set<Long> retainedIds = new HashSet<>();
        Set<String> allNormalizedQuestions = new HashSet<>();
        for (int index = 0; index < replies.size(); index++) {
            SmartReplyRequest reply = replies.get(index);
            List<QuestionInput> questions = normalizeSmartQuestions(
                    reply.questions(), allNormalizedQuestions);
            boolean enabled = Boolean.TRUE.equals(reply.enabled());
            String replyContent = optionalText(reply.reply(), 2000);
            if (enabled && (questions.isEmpty() || replyContent.isEmpty())) {
                throw invalidConfig();
            }
            String name = StringUtils.hasText(reply.name())
                    ? requiredText(reply.name(), 64)
                    : "第" + (index + 1) + "组";
            int requestedSortOrder = sortOrder(reply.sortOrder(), index);
            Long replyId = reply.replyId();
            if (replyId == null) {
                replyId = insertSmartReplyGroup(
                        name, replyContent, enabled, requestedSortOrder, adminUserId, now);
            } else {
                if (!existingIds.contains(replyId) || !retainedIds.add(replyId)) {
                    throw invalidConfig();
                }
                jdbcClient.sql("""
                                update customer_service_smart_reply_group
                                set name = :name,
                                    reply_content = :replyContent,
                                    enabled = :enabled,
                                    sort_order = :sortOrder,
                                    updated_by = :adminUserId,
                                    updated_at = :now
                                where id = :replyId
                                """)
                        .param("name", name)
                        .param("replyContent", replyContent)
                        .param("enabled", enabled)
                        .param("sortOrder", requestedSortOrder)
                        .param("adminUserId", adminUserId)
                        .param("now", now)
                        .param("replyId", replyId)
                        .update();
            }
            replaceSmartQuestions(replyId, questions, now);
        }
        for (Long existingId : existingIds) {
            if (!retainedIds.contains(existingId)) {
                jdbcClient.sql("""
                                delete from customer_service_smart_reply_question
                                where reply_group_id = :replyId
                                """)
                        .param("replyId", existingId)
                        .update();
                jdbcClient.sql("delete from customer_service_smart_reply_group where id = :replyId")
                        .param("replyId", existingId)
                        .update();
            }
        }
        return autoReplies(adminUserId);
    }

    public List<CommonQuestionSummaryResponse> enabledCommonQuestions() {
        return jdbcClient.sql("""
                        select id, question
                        from customer_service_common_question
                        where enabled = true
                        order by sort_order, id
                        """)
                .query((rs, rowNum) -> new CommonQuestionSummaryResponse(
                        rs.getLong("id"),
                        rs.getString("question")
                ))
                .list();
    }

    public QuickReplyConfigResponse quickReplies() {
        List<QuickReplyGroupRow> groupRows = jdbcClient.sql("""
                        select id, name, sort_order
                        from customer_service_quick_reply_group
                        order by sort_order, id
                        """)
                .query((rs, rowNum) -> new QuickReplyGroupRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("sort_order")
                ))
                .list();
        Map<Long, List<QuickReplyResponse>> repliesByGroupId = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select id, group_id, content, sort_order
                        from customer_service_quick_reply
                        order by group_id, sort_order, id
                        """)
                .query((rs, rowNum) -> new QuickReplyItemRow(
                        rs.getLong("id"),
                        rs.getLong("group_id"),
                        rs.getString("content"),
                        rs.getInt("sort_order")
                ))
                .list()
                .forEach(row -> repliesByGroupId
                        .computeIfAbsent(row.groupId(), ignored -> new ArrayList<>())
                        .add(new QuickReplyResponse(
                                row.replyId(), row.content(), row.sortOrder())));
        return new QuickReplyConfigResponse(groupRows.stream()
                .map(group -> new QuickReplyGroupResponse(
                        group.groupId(),
                        group.name(),
                        group.sortOrder(),
                        List.copyOf(repliesByGroupId.getOrDefault(group.groupId(), List.of()))
                ))
                .toList());
    }

    @Transactional
    public QuickReplyResponse createQuickReply(
            Long adminUserId,
            QuickReplyCreateRequest request
    ) {
        requireQuickReplyGroup(request.groupId());
        int nextSortOrder = jdbcClient.sql("""
                        select coalesce(max(sort_order), -1) + 1
                        from customer_service_quick_reply
                        where group_id = :groupId
                        """)
                .param("groupId", request.groupId())
                .query(Integer.class)
                .single();
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into customer_service_quick_reply
                            (group_id, content, sort_order, created_by, updated_by,
                             created_at, updated_at)
                        values
                            (:groupId, :content, :sortOrder, :adminUserId, :adminUserId,
                             :now, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("groupId", request.groupId())
                        .addValue("content", requiredText(request.content(), 2000))
                        .addValue("sortOrder", nextSortOrder)
                        .addValue("adminUserId", adminUserId)
                        .addValue("now", now),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw invalidConfig();
        }
        return requireQuickReply(key.longValue());
    }

    @Transactional
    public QuickReplyResponse updateQuickReply(
            Long adminUserId,
            Long replyId,
            QuickReplyUpdateRequest request
    ) {
        QuickReplyResponse existing = requireQuickReply(replyId);
        int updated = jdbcClient.sql("""
                        update customer_service_quick_reply
                        set content = :content,
                            sort_order = :sortOrder,
                            updated_by = :adminUserId,
                            updated_at = :now
                        where id = :replyId
                        """)
                .param("content", requiredText(request.content(), 2000))
                .param("sortOrder", sortOrder(request.sortOrder(), existing.sortOrder()))
                .param("adminUserId", adminUserId)
                .param("now", LocalDateTime.now())
                .param("replyId", replyId)
                .update();
        if (updated != 1) {
            throw invalidConfig();
        }
        return requireQuickReply(replyId);
    }

    @Transactional
    public void deleteQuickReply(Long replyId) {
        int deleted = jdbcClient.sql("delete from customer_service_quick_reply where id = :replyId")
                .param("replyId", replyId)
                .update();
        if (deleted != 1) {
            throw invalidConfig();
        }
    }

    public AutomationMessage openingMessage(Long conversationId, int consultationNo) {
        String content = configRow().openingMessage();
        return content.isEmpty()
                ? null
                : insertAutomationMessage(conversationId, consultationNo, "OPENING", content);
    }

    public AutomationMessage welcomeMessage(
            Long conversationId,
            int consultationNo,
            Long adminUserId,
            Long assignmentMessageId
    ) {
        String content = welcomeMessage(adminUserId);
        return content.isEmpty()
                ? null
                : insertAutomationMessage(
                        conversationId,
                        consultationNo,
                        "WELCOME:" + assignmentMessageId,
                        content
                );
    }

    public AutomationMessage replyToUserText(
            Long conversationId,
            int consultationNo,
            Long appUserId,
            Long sourceMessageId,
            String content
    ) {
        String normalized = normalizeQuestion(content);
        if (!normalized.isEmpty()) {
            String matchedReply = matchCommonQuestion(normalized);
            if (matchedReply == null) {
                matchedReply = matchSmartReply(normalized);
            }
            if (StringUtils.hasText(matchedReply)) {
                return insertAutomationMessage(
                        conversationId,
                        consultationNo,
                        "USER_REPLY:" + sourceMessageId,
                        matchedReply
                );
            }
        }
        return replyToOfflineUserMessage(
                conversationId, consultationNo, appUserId, sourceMessageId);
    }

    public AutomationMessage replyToOfflineUserMessage(
            Long conversationId,
            int consultationNo,
            Long appUserId,
            Long sourceMessageId
    ) {
        String offlineMessage = configRow().offlineMessage();
        if (offlineMessage.isEmpty() || !allAgentsOffline() || !claimOfflineReply(appUserId)) {
            return null;
        }
        return insertAutomationMessage(
                conversationId,
                consultationNo,
                "OFFLINE:" + sourceMessageId,
                offlineMessage
        );
    }

    private List<CommonQuestionResponse> commonQuestions() {
        return jdbcClient.sql("""
                        select id, question, answer, enabled, sort_order
                        from customer_service_common_question
                        order by sort_order, id
                        """)
                .query((rs, rowNum) -> new CommonQuestionResponse(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getBoolean("enabled"),
                        rs.getInt("sort_order")
                ))
                .list();
    }

    private List<SmartReplyResponse> smartReplies() {
        List<SmartReplyGroupRow> groupRows = jdbcClient.sql("""
                        select id, name, reply_content, enabled, sort_order
                        from customer_service_smart_reply_group
                        order by sort_order, id
                        """)
                .query((rs, rowNum) -> new SmartReplyGroupRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("reply_content"),
                        rs.getBoolean("enabled"),
                        rs.getInt("sort_order")
                ))
                .list();
        Map<Long, List<String>> questionsByReplyId = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select reply_group_id, question
                        from customer_service_smart_reply_question
                        order by reply_group_id, sort_order, id
                        """)
                .query((rs, rowNum) -> new SmartReplyQuestionRow(
                        rs.getLong("reply_group_id"),
                        rs.getString("question"),
                        ""
                ))
                .list()
                .forEach(row -> questionsByReplyId
                        .computeIfAbsent(row.replyId(), ignored -> new ArrayList<>())
                        .add(row.question()));
        return groupRows.stream()
                .map(group -> new SmartReplyResponse(
                        group.replyId(),
                        group.name(),
                        List.copyOf(questionsByReplyId.getOrDefault(group.replyId(), List.of())),
                        group.reply(),
                        group.enabled(),
                        group.sortOrder()
                ))
                .toList();
    }

    private Long insertSmartReplyGroup(
            String name,
            String replyContent,
            boolean enabled,
            int sortOrder,
            Long adminUserId,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into customer_service_smart_reply_group
                            (name, reply_content, enabled, sort_order,
                             updated_by, created_at, updated_at)
                        values
                            (:name, :replyContent, :enabled, :sortOrder,
                             :adminUserId, :now, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("name", name)
                        .addValue("replyContent", replyContent)
                        .addValue("enabled", enabled)
                        .addValue("sortOrder", sortOrder)
                        .addValue("adminUserId", adminUserId)
                        .addValue("now", now),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw invalidConfig();
        }
        return key.longValue();
    }

    private void replaceSmartQuestions(
            Long replyId,
            List<QuestionInput> questions,
            LocalDateTime now
    ) {
        jdbcClient.sql("""
                        delete from customer_service_smart_reply_question
                        where reply_group_id = :replyId
                        """)
                .param("replyId", replyId)
                .update();
        for (int index = 0; index < questions.size(); index++) {
            QuestionInput question = questions.get(index);
            jdbcClient.sql("""
                            insert into customer_service_smart_reply_question
                                (reply_group_id, question, normalized_question,
                                 sort_order, created_at)
                            values
                                (:replyId, :question, :normalizedQuestion,
                                 :sortOrder, :now)
                            """)
                    .param("replyId", replyId)
                    .param("question", question.question())
                    .param("normalizedQuestion", question.normalizedQuestion())
                    .param("sortOrder", index)
                    .param("now", now)
                    .update();
        }
    }

    private List<QuestionInput> normalizeSmartQuestions(
            List<String> questions,
            Set<String> allNormalizedQuestions
    ) {
        if (questions.size() > MAX_SMART_QUESTIONS) {
            throw invalidConfig();
        }
        List<QuestionInput> result = new ArrayList<>();
        for (String rawQuestion : questions) {
            String question = requiredText(rawQuestion, 200);
            String normalized = normalizeQuestion(question);
            if (normalized.isEmpty() || !allNormalizedQuestions.add(normalized)) {
                throw invalidConfig();
            }
            result.add(new QuestionInput(question, normalized));
        }
        return List.copyOf(result);
    }

    private String matchCommonQuestion(String normalizedInput) {
        return jdbcClient.sql("""
                        select question, answer
                        from customer_service_common_question
                        where enabled = true
                        order by sort_order, id
                        """)
                .query((rs, rowNum) -> new CommonQuestionMatchRow(
                        rs.getString("question"),
                        rs.getString("answer")
                ))
                .list()
                .stream()
                .filter(row -> normalizedInput.equals(normalizeQuestion(row.question())))
                .map(CommonQuestionMatchRow::answer)
                .findFirst()
                .orElse(null);
    }

    private String matchSmartReply(String normalizedInput) {
        List<SmartReplyQuestionRow> rows = jdbcClient.sql("""
                        select question.reply_group_id,
                               question.question,
                               question.normalized_question,
                               reply.reply_content
                        from customer_service_smart_reply_question question
                        join customer_service_smart_reply_group reply
                          on reply.id = question.reply_group_id
                        where reply.enabled = true
                          and reply.reply_content <> ''
                        order by reply.sort_order, reply.id,
                                 question.sort_order, question.id
                        """)
                .query((rs, rowNum) -> new SmartReplyQuestionRow(
                        rs.getLong("reply_group_id"),
                        rs.getString("question"),
                        rs.getString("normalized_question"),
                        rs.getString("reply_content")
                ))
                .list();
        for (SmartReplyQuestionRow row : rows) {
            if (normalizedInput.equals(row.normalizedQuestion())) {
                return row.reply();
            }
        }
        SmartReplyQuestionRow best = null;
        for (SmartReplyQuestionRow row : rows) {
            if (!normalizedInput.contains(row.normalizedQuestion())) {
                continue;
            }
            if (best == null
                    || row.normalizedQuestion().length() > best.normalizedQuestion().length()) {
                best = row;
            }
        }
        return best == null ? null : best.reply();
    }

    private boolean allAgentsOffline() {
        List<Long> potentiallyOnlineAgentIds = jdbcClient.sql("""
                        select distinct admin.id
                        from admin_user admin
                        join admin_user_role user_role on user_role.user_id = admin.id
                        join admin_role role_item on role_item.id = user_role.role_id
                        join customer_service_agent_state state
                          on state.admin_user_id = admin.id
                        where admin.status = 'ENABLED'
                          and role_item.enabled = true
                          and role_item.code = 'R_CUSTOMER_SERVICE'
                          and state.work_status <> 'OFFLINE'
                        order by admin.id
                        """)
                .query(Long.class)
                .list();
        return potentiallyOnlineAgentIds.stream()
                .noneMatch(realtimeSessionHub::isAdminOnline);
    }

    private boolean claimOfflineReply(Long appUserId) {
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcClient.sql("""
                            insert into customer_service_offline_reply_state
                                (app_user_id, last_replied_at, updated_at)
                            select :appUserId, null, :now
                            where not exists (
                                select 1
                                from customer_service_offline_reply_state
                                where app_user_id = :appUserId
                            )
                            """)
                    .param("appUserId", appUserId)
                    .param("now", now)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // A concurrent message created the per-user throttle row first.
        }
        LocalDateTime lastRepliedAt = jdbcClient.sql("""
                        select last_replied_at
                        from customer_service_offline_reply_state
                        where app_user_id = :appUserId
                        for update
                        """)
                .param("appUserId", appUserId)
                .query((rs, rowNum) -> new OfflineReplyStateRow(
                        rs.getTimestamp("last_replied_at") == null
                                ? null
                                : rs.getTimestamp("last_replied_at").toLocalDateTime()
                ))
                .single()
                .lastRepliedAt();
        if (lastRepliedAt != null && lastRepliedAt.isAfter(now.minusHours(1))) {
            return false;
        }
        return jdbcClient.sql("""
                        update customer_service_offline_reply_state
                        set last_replied_at = :now,
                            updated_at = :now
                        where app_user_id = :appUserId
                        """)
                .param("now", now)
                .param("appUserId", appUserId)
                .update() == 1;
    }

    private AutomationMessage insertAutomationMessage(
            Long conversationId,
            int consultationNo,
            String automationKey,
            String content
    ) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into customer_service_message
                                (conversation_id, consultation_no, sender_type, sender_id,
                                 message_type, content, resource_id, client_message_id,
                                 automation_key, created_at)
                            values
                                (:conversationId, :consultationNo, 'BOT', null,
                                 'AUTO_REPLY', :content, null, null,
                                 :automationKey, :createdAt)
                            """,
                    new MapSqlParameterSource()
                            .addValue("conversationId", conversationId)
                            .addValue("consultationNo", consultationNo)
                            .addValue("content", content)
                            .addValue("automationKey", automationKey)
                            .addValue("createdAt", now),
                    keyHolder,
                    new String[]{"id"});
        } catch (DuplicateKeyException ignored) {
            return null;
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
        return new AutomationMessage(key.longValue(), now);
    }

    private AutoReplyConfigRow configRow() {
        return jdbcClient.sql("""
                        select revision, opening_message, offline_message
                        from customer_service_auto_reply_config
                        where id = 1
                        """)
                .query((rs, rowNum) -> new AutoReplyConfigRow(
                        rs.getLong("revision"),
                        rs.getString("opening_message"),
                        rs.getString("offline_message")
                ))
                .optional()
                .orElseThrow(this::invalidConfig);
    }

    private void claimConfigRevision(
            Long expectedRevision,
            Long adminUserId,
            LocalDateTime now,
            String openingMessage,
            String offlineMessage
    ) {
        String openingAssignment = openingMessage == null
                ? ""
                : "opening_message = :openingMessage,\n";
        String offlineAssignment = offlineMessage == null
                ? ""
                : "offline_message = :offlineMessage,\n";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        update customer_service_auto_reply_config
                        set
                        """ + openingAssignment + offlineAssignment + """
                            revision = revision + 1,
                            updated_by = :adminUserId,
                            updated_at = :now
                        where id = 1
                          and revision = :expectedRevision
                        """)
                .param("adminUserId", adminUserId)
                .param("now", now)
                .param("expectedRevision", expectedRevision);
        if (openingMessage != null) {
            statement = statement.param("openingMessage", openingMessage);
        }
        if (offlineMessage != null) {
            statement = statement.param("offlineMessage", offlineMessage);
        }
        if (statement.update() != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_STATE_CONFLICT);
        }
    }

    private String welcomeMessage(Long adminUserId) {
        if (adminUserId == null) {
            return "";
        }
        return jdbcClient.sql("""
                        select welcome_message
                        from customer_service_agent_profile
                        where admin_user_id = :adminUserId
                        """)
                .param("adminUserId", adminUserId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private void requireQuickReplyGroup(Long groupId) {
        long count = jdbcClient.sql("""
                        select count(*)
                        from customer_service_quick_reply_group
                        where id = :groupId
                        """)
                .param("groupId", groupId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw invalidConfig();
        }
    }

    private QuickReplyResponse requireQuickReply(Long replyId) {
        return jdbcClient.sql("""
                        select id, content, sort_order
                        from customer_service_quick_reply
                        where id = :replyId
                        """)
                .param("replyId", replyId)
                .query((rs, rowNum) -> new QuickReplyResponse(
                        rs.getLong("id"),
                        rs.getString("content"),
                        rs.getInt("sort_order")
                ))
                .optional()
                .orElseThrow(this::invalidConfig);
    }

    private String requiredText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw invalidConfig();
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw invalidConfig();
        }
        return normalized;
    }

    private int sortOrder(Integer value, int fallback) {
        int normalized = value == null ? fallback : value;
        if (normalized < 0) {
            throw invalidConfig();
        }
        return normalized;
    }

    private String normalizeQuestion(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return QUESTION_NOISE.matcher(normalized).replaceAll("");
    }

    private BusinessException invalidConfig() {
        return new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
    }

    public record AutomationMessage(Long messageId, LocalDateTime createdAt) {
    }

    private record AutoReplyConfigRow(long revision, String openingMessage, String offlineMessage) {
    }

    private record OfflineReplyStateRow(LocalDateTime lastRepliedAt) {
    }

    private record CommonQuestionMatchRow(String question, String answer) {
    }

    private record SmartReplyGroupRow(
            Long replyId,
            String name,
            String reply,
            boolean enabled,
            int sortOrder
    ) {
    }

    private record SmartReplyQuestionRow(
            Long replyId,
            String question,
            String normalizedQuestion,
            String reply
    ) {
        private SmartReplyQuestionRow(Long replyId, String question, String normalizedQuestion) {
            this(replyId, question, normalizedQuestion, "");
        }
    }

    private record QuestionInput(String question, String normalizedQuestion) {
    }

    private record QuickReplyGroupRow(Long groupId, String name, int sortOrder) {
    }

    private record QuickReplyItemRow(Long replyId, Long groupId, String content, int sortOrder) {
    }
}
