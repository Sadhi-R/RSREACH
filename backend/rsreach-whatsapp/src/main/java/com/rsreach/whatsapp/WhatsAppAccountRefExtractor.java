package com.rsreach.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.WebhookAccountResolutionException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the Cloud API phone-number id from a WhatsApp webhook body.
 * The WABA id on {@code entry[].id} is not an account key.
 */
public final class WhatsAppAccountRefExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WhatsAppAccountRefExtractor() {
    }

    public static String requireSinglePhoneNumberId(String rawBody) {
        JsonNode root = read(rawBody);
        Set<String> ids = new LinkedHashSet<>();
        JsonNode entries = root.path("entry");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                JsonNode changes = entry.path("changes");
                if (!changes.isArray()) {
                    continue;
                }
                for (JsonNode change : changes) {
                    add(ids, change.path("value").path("metadata").path("phone_number_id"));
                }
            }
        }
        add(ids, root.path("metadata").path("phone_number_id"));
        if (ids.isEmpty()) {
            throw new WebhookAccountResolutionException(
                    WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
        }
        if (ids.size() > 1) {
            throw new WebhookAccountResolutionException(WebhookAccountResolutionException.Reason.AMBIGUOUS);
        }
        return ids.iterator().next();
    }

    private static JsonNode read(String rawBody) {
        try {
            if (rawBody == null || rawBody.isBlank()) {
                throw new WebhookAccountResolutionException(
                        WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
            }
            return MAPPER.readTree(rawBody);
        } catch (WebhookAccountResolutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WebhookAccountResolutionException(
                    WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
        }
    }

    private static void add(Set<String> ids, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return;
        }
        String value = node.asText();
        if (value != null && !value.isBlank()) {
            ids.add(value.trim());
        }
    }
}
