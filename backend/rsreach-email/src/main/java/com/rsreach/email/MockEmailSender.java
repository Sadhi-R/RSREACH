package com.rsreach.email;

import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.MessageSender;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
@ConditionalOnProperty(prefix = "rsreach.email", name = "delivery-live-enabled", havingValue = "false", matchIfMissing = true)
public class MockEmailSender implements MessageSender {

    @Override
    public String channel() {
        return CommunicationModels.CHANNEL_EMAIL;
    }

    @Override
    public SendResult send(
            UUID tenantId,
            UUID workspaceId,
            CommunicationModels.ConversationView conversation,
            CommunicationModels.MessageView message
    ) {
        return SendResult.ok("email-mock-" + message.id());
    }
}
