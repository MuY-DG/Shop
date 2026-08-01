package org.muybaby.shopserver.customerservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.common.api.JsonStringId;

import java.util.List;

public final class CustomerServiceReplyDtos {

    private CustomerServiceReplyDtos() {
    }

    public record CommonQuestionRequest(
            @Positive Long questionId,
            @NotBlank @Size(max = 200) String question,
            @NotBlank @Size(max = 2000) String answer,
            @NotNull Boolean enabled,
            @Min(0) Integer sortOrder
    ) {
    }

    public record CommonQuestionResponse(
            @JsonStringId Long questionId,
            String question,
            String answer,
            boolean enabled,
            int sortOrder
    ) {
    }

    public record CommonQuestionSummaryResponse(
            @JsonStringId Long questionId,
            String question
    ) {
    }

    public record CommonAutoReplyUpdateRequest(
            @NotNull @Min(0) Long revision,
            @Size(max = 2000) String openingMessage,
            @NotNull @Size(max = 20) List<@NotNull @Valid CommonQuestionRequest> commonQuestions
    ) {
    }

    public record WelcomeAutoReplyUpdateRequest(
            @Size(max = 2000) String content
    ) {
    }

    public record OfflineAutoReplyUpdateRequest(
            @NotNull @Min(0) Long revision,
            @Size(max = 2000) String content
    ) {
    }

    public record SmartReplyRequest(
            @Positive Long replyId,
            @Size(max = 64) String name,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 200) String> questions,
            @Size(max = 2000) String reply,
            @NotNull Boolean enabled,
            @Min(0) Integer sortOrder
    ) {
    }

    public record SmartReplyResponse(
            @JsonStringId Long replyId,
            String name,
            List<String> questions,
            String reply,
            boolean enabled,
            int sortOrder
    ) {
    }

    public record SmartAutoReplyUpdateRequest(
            @NotNull @Min(0) Long revision,
            @NotNull @Size(max = 100) List<@NotNull @Valid SmartReplyRequest> smartReplies
    ) {
    }

    public record AutoReplyConfigResponse(
            long revision,
            String openingMessage,
            String welcomeMessage,
            String offlineMessage,
            List<CommonQuestionResponse> commonQuestions,
            List<SmartReplyResponse> smartReplies
    ) {
    }

    public record QuickReplyCreateRequest(
            @NotNull @Positive Long groupId,
            @NotBlank @Size(max = 2000) String content
    ) {
    }

    public record QuickReplyUpdateRequest(
            @NotBlank @Size(max = 2000) String content,
            @Min(0) Integer sortOrder
    ) {
    }

    public record QuickReplyResponse(
            @JsonStringId Long replyId,
            String content,
            int sortOrder
    ) {
    }

    public record QuickReplyGroupResponse(
            @JsonStringId Long groupId,
            String name,
            int sortOrder,
            List<QuickReplyResponse> replies
    ) {
    }

    public record QuickReplyConfigResponse(
            List<QuickReplyGroupResponse> groups
    ) {
    }
}
