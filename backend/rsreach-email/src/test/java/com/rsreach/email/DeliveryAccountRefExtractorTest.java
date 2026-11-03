package com.rsreach.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsreach.communications.WebhookAccountResolutionException;
import org.junit.jupiter.api.Test;

class DeliveryAccountRefExtractorTest {

    @Test
    void sendGridArrayMustAgreeOnAccountRef() {
        String same = """
                [{"event":"delivered","sg_message_id":"msg-1","account_ref":"sg-1"},
                 {"event":"open","unique_args":{"account_ref":"sg-1"}}]
                """;
        assertThat(DeliveryAccountRefExtractor.requireSingleAccountRef("sendgrid", same)).isEqualTo("sg-1");

        String mixed = """
                [{"account_ref":"sg-1"},{"accountRef":"sg-2"}]
                """;
        assertThatThrownBy(() -> DeliveryAccountRefExtractor.requireSingleAccountRef("sendgrid", mixed))
                .isInstanceOf(WebhookAccountResolutionException.class)
                .extracting(ex -> ((WebhookAccountResolutionException) ex).reason())
                .isEqualTo(WebhookAccountResolutionException.Reason.AMBIGUOUS);
    }

    @Test
    void sesUsesMessageTag() {
        String body = """
                {"notificationType":"Delivery","mail":{"messageId":"ses-1","tags":{"account_ref":["ses-acct"]}}}
                """;
        assertThat(DeliveryAccountRefExtractor.requireSingleAccountRef("ses", body)).isEqualTo("ses-acct");
    }

    @Test
    void mailgunUsesUserVariable() {
        String body = """
                {"event-data":{"event":"delivered","user-variables":{"account_ref":"mg-acct"}}}
                """;
        assertThat(DeliveryAccountRefExtractor.requireSingleAccountRef("mailgun", body)).isEqualTo("mg-acct");
    }

    @Test
    void gmailUsesMailboxAddress() {
        assertThat(DeliveryAccountRefExtractor.requireSingleAccountRef(
                "gmail", "{\"emailAddress\":\"owner@example.com\",\"historyId\":\"10\"}"))
                .isEqualTo("owner@example.com");
    }

    @Test
    void graphRequiresOneSubscriptionId() {
        String one = """
                {"value":[{"subscriptionId":"sub-1","resource":"users/a/messages"}]}
                """;
        assertThat(DeliveryAccountRefExtractor.requireSingleAccountRef("microsoft_graph", one)).isEqualTo("sub-1");

        String two = """
                {"value":[{"subscriptionId":"sub-1"},{"subscriptionId":"sub-2"}]}
                """;
        assertThatThrownBy(() -> DeliveryAccountRefExtractor.requireSingleAccountRef("microsoft_graph", two))
                .isInstanceOf(WebhookAccountResolutionException.class)
                .extracting(ex -> ((WebhookAccountResolutionException) ex).reason())
                .isEqualTo(WebhookAccountResolutionException.Reason.AMBIGUOUS);
    }
}
