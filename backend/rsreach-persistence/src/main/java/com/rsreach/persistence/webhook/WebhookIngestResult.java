package com.rsreach.persistence.webhook;

import java.util.UUID;

public record WebhookIngestResult(UUID eventId, boolean inserted) {
}
