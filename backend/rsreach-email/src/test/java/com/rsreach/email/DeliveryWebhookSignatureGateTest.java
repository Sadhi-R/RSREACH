package com.rsreach.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsreach.communications.ConversationService;
import com.rsreach.communications.MessageRepository;
import com.rsreach.communications.WebhookAccountResolver;
import com.rsreach.contacts.SuppressionService;
import com.rsreach.persistence.webhook.ProviderWebhookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeliveryWebhookSignatureGateTest {

    @Mock private ProviderWebhookRepository webhookRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ConversationService conversationService;
    @Mock private SuppressionService suppressionService;
    @Mock private WebhookAccountResolver accountResolver;

    @Test
    void mockSignatureRejectedWhenDisabled() {
        DeliveryWebhookService service = new DeliveryWebhookService(
                webhookRepository,
                messageRepository,
                conversationService,
                suppressionService,
                accountResolver,
                false,
                ""
        );
        assertThat(service.verifySignature("sendgrid", "mock-valid", "{\"event\":\"delivered\"}")).isFalse();
        assertThat(service.verifySignature("ses", "sha256=anything", "{}")).isFalse();
    }
}
