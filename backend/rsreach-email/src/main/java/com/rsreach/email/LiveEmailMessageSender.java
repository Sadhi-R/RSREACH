package com.rsreach.email;

import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConnectedAccountService;
import com.rsreach.communications.MessageSender;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "rsreach.email", name = "delivery-live-enabled", havingValue = "true")
public class LiveEmailMessageSender implements MessageSender {

    private final List<DeliveryConnector> connectors;
    private final ConnectedAccountService connectedAccountService;

    public LiveEmailMessageSender(
            List<DeliveryConnector> connectors,
            ConnectedAccountService connectedAccountService
    ) {
        this.connectors = connectors;
        this.connectedAccountService = connectedAccountService;
    }

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
        if (connectors.isEmpty()) {
            return SendResult.fail("NO_DELIVERY_CONNECTOR", "No DeliveryConnector registered");
        }
        if (conversation.connectedAccountId() == null) {
            return SendResult.fail("NO_ACCOUNT", "Email delivery requires a connected account");
        }
        String token = connectedAccountService.decryptAccessToken(
                tenantId, workspaceId, conversation.connectedAccountId());
        DeliveryConnector connector = connectors.get(0);
        DeliveryConnector.DeliveryResult result = connector.send(
                tenantId, workspaceId, conversation, message, token);
        if (result.success()) {
            return SendResult.ok(result.providerMessageId());
        }
        return SendResult.fail(result.errorCode(), result.errorMessage());
    }
}
