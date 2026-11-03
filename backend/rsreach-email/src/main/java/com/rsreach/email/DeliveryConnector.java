package com.rsreach.email;

import com.rsreach.communications.CommunicationModels;
import java.util.UUID;

/**
 * Outbound email delivery port (SendGrid / SES / Mailgun). Separate from mailbox sync.
 */
public interface DeliveryConnector {

    String provider();

    DeliveryResult send(
            UUID tenantId,
            UUID workspaceId,
            CommunicationModels.ConversationView conversation,
            CommunicationModels.MessageView message,
            String accessToken
    );

    record DeliveryResult(boolean success, String providerMessageId, String providerStatus, String errorCode, String errorMessage) {
        public static DeliveryResult ok(String providerMessageId) {
            return new DeliveryResult(true, providerMessageId, "accepted", null, null);
        }

        public static DeliveryResult fail(String code, String message) {
            return new DeliveryResult(false, null, "failed", code, message);
        }
    }
}
