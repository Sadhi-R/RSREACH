package com.rsreach.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.communications.WebhookAccountResolutionException;
import org.junit.jupiter.api.Test;

class WhatsAppAccountRefExtractorTest {

    @Test
    void readsCloudApiPhoneNumberId() {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"value":{"metadata":{"phone_number_id":"111222333"}},"field":"messages"}]}]}
                """;
        assertThat(WhatsAppAccountRefExtractor.requireSinglePhoneNumberId(body)).isEqualTo("111222333");
    }

    @Test
    void rejectsWabaIdWithoutPhoneNumberId() {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"waba-only","changes":[{"value":{},"field":"messages"}]}]}
                """;
        assertThatThrownBy(() -> WhatsAppAccountRefExtractor.requireSinglePhoneNumberId(body))
                .isInstanceOf(WebhookAccountResolutionException.class)
                .extracting(ex -> ((WebhookAccountResolutionException) ex).reason())
                .isEqualTo(WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
    }

    @Test
    void rejectsTwoPhoneNumberIdsInOneBody() {
        String body = """
                {"entry":[{"changes":[
                  {"value":{"metadata":{"phone_number_id":"111"}}},
                  {"value":{"metadata":{"phone_number_id":"222"}}}
                ]}]}
                """;
        assertThatThrownBy(() -> WhatsAppAccountRefExtractor.requireSinglePhoneNumberId(body))
                .isInstanceOf(WebhookAccountResolutionException.class)
                .extracting(ex -> ((WebhookAccountResolutionException) ex).reason())
                .isEqualTo(WebhookAccountResolutionException.Reason.AMBIGUOUS);
    }
}
