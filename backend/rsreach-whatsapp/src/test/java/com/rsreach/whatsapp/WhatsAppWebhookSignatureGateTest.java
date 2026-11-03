package com.rsreach.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsreach.communications.ConversationService;
import com.rsreach.communications.MessageRepository;
import com.rsreach.communications.WebhookAccountResolver;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.persistence.webhook.ProviderWebhookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookSignatureGateTest {

    @Mock private ProviderWebhookRepository webhookRepository;
    @Mock private ConversationService conversationService;
    @Mock private MessageRepository messageRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private WebhookAccountResolver accountResolver;

    @Test
    void mockSignatureRejectedWhenDisabledAndHandshakeStaysTokenBased() {
        WhatsAppWebhookService service = new WhatsAppWebhookService(
                webhookRepository,
                conversationService,
                messageRepository,
                outboxRepository,
                jdbcTemplate,
                accountResolver,
                "",
                "phase6-verify-token",
                false
        );
        assertThat(service.verifySignature("mock-valid", "{\"text\":\"hi\"}")).isFalse();
        assertThat(service.verifyChallenge("subscribe", "phase6-verify-token", "challenge-ok")).isTrue();
        assertThat(service.verifyChallenge("subscribe", "other-token", "challenge-ok")).isFalse();
        assertThat(service.verifyChallenge("denied", "phase6-verify-token", "challenge-ok")).isFalse();
    }
}
