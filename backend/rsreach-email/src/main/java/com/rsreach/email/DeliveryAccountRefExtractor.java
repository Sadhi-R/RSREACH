package com.rsreach.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.WebhookAccountResolutionException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Provider-specific account ids inside a signed delivery or mailbox webhook.
 * Tenant ids in the body are ignored.
 */
public final class DeliveryAccountRefExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeliveryAccountRefExtractor() {
    }

    public static String requireSingleAccountRef(String provider, String rawBody) {
        JsonNode root = read(rawBody);
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        Set<String> refs = switch (normalized) {
            case "sendgrid" -> sendGrid(root);
            case "ses" -> ses(root);
            case "mailgun" -> mailgun(root);
            case "gmail" -> singleton(text(root, "emailAddress"));
            case "microsoft_graph" -> graph(root);
            default -> new LinkedHashSet<>();
        };
        if (refs.isEmpty()) {
            throw new WebhookAccountResolutionException(
                    WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
        }
        if (refs.size() > 1) {
            throw new WebhookAccountResolutionException(WebhookAccountResolutionException.Reason.AMBIGUOUS);
        }
        return refs.iterator().next();
    }

    private static Set<String> sendGrid(JsonNode root) {
        Set<String> refs = new LinkedHashSet<>();
        if (root.isArray()) {
            for (JsonNode event : root) {
                add(refs, sendGridEvent(event));
            }
            return refs;
        }
        add(refs, sendGridEvent(root));
        return refs;
    }

    private static String sendGridEvent(JsonNode event) {
        String direct = firstText(event, "account_ref", "accountRef");
        if (direct != null) {
            return direct;
        }
        return text(event.path("unique_args"), "account_ref");
    }

    private static Set<String> ses(JsonNode root) {
        Set<String> refs = new LinkedHashSet<>();
        add(refs, firstText(root, "account_ref", "accountRef"));
        JsonNode tagged = root.path("mail").path("tags").path("account_ref");
        if (tagged.isArray() && tagged.size() > 0) {
            add(refs, tagged.get(0).isValueNode() ? tagged.get(0).asText() : null);
        } else if (tagged.isValueNode()) {
            add(refs, tagged.asText());
        }
        return refs;
    }

    private static Set<String> mailgun(JsonNode root) {
        Set<String> refs = new LinkedHashSet<>();
        add(refs, firstText(root, "accountRef"));
        add(refs, text(root.path("event-data").path("user-variables"), "account_ref"));
        return refs;
    }

    private static Set<String> graph(JsonNode root) {
        Set<String> refs = new LinkedHashSet<>();
        JsonNode value = root.path("value");
        if (value.isArray()) {
            for (JsonNode notice : value) {
                add(refs, text(notice, "subscriptionId"));
            }
        }
        return refs;
    }

    private static Set<String> singleton(String value) {
        Set<String> refs = new LinkedHashSet<>();
        add(refs, value);
        return refs;
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

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        if (node == null || node.isMissingNode() || !node.has(key) || node.get(key).isNull()) {
            return null;
        }
        JsonNode value = node.get(key);
        if (!value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static void add(Set<String> refs, String value) {
        if (value != null && !value.isBlank()) {
            refs.add(value.trim());
        }
    }
}
