package com.rsreach.communications;

import java.util.UUID;

public interface MessageSender {

    String channel();

    SendResult send(
            UUID tenantId,
            UUID workspaceId,
            CommunicationModels.ConversationView conversation,
            CommunicationModels.MessageView message
    );

    record SendResult(
            boolean success,
            String providerMessageId,
            String providerStatus,
            String errorCode,
            String errorMessage
    ) {
        public static SendResult ok(String providerMessageId) {
            return new SendResult(true, providerMessageId, "accepted", null, null);
        }

        public static SendResult fail(String code, String message) {
            return new SendResult(false, null, "failed", code, message);
        }
    }
}
