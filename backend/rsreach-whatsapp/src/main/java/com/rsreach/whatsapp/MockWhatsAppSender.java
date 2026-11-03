package com.rsreach.whatsapp;

import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.MessageSender;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CI/local WhatsApp sender. Disabled when live Cloud API sender is enabled.
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "rsreach.whatsapp", name = "live-enabled", havingValue = "false", matchIfMissing = true)
public class MockWhatsAppSender implements MessageSender {

    @Override
    public String channel() {
        return CommunicationModels.CHANNEL_WHATSAPP;
    }

    @Override
    public SendResult send(
            UUID tenantId,
            UUID workspaceId,
            CommunicationModels.ConversationView conversation,
            CommunicationModels.MessageView message
    ) {
        return SendResult.ok("wa-mock-" + message.id());
    }
}
