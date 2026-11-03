package com.rsreach.communications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledMessageService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final ConversationService conversationService;

    public ScheduledMessageService(JdbcTemplate jdbcTemplate, ConversationService conversationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationService = conversationService;
    }

    @Transactional
    public void fire(UUID tenantId, UUID workspaceId, String payloadJson) {
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            UUID scheduledId = UUID.fromString(node.get("scheduledMessageId").asText());
            List<Row> rows = jdbcTemplate.query(
                    """
                    SELECT conversation_id, payload_json::text AS payload
                    FROM scheduled_messages
                    WHERE id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'FIRED'
                    """,
                    (rs, i) -> new Row(
                            (UUID) rs.getObject("conversation_id"),
                            rs.getString("payload")
                    ),
                    scheduledId, tenantId, workspaceId
            );
            if (rows.isEmpty()) {
                return;
            }
            Row row = rows.get(0);
            if (row.conversationId() == null) {
                log.info("scheduled_message_no_conversation id={}", scheduledId);
                return;
            }
            JsonNode payload = MAPPER.readTree(row.payload() == null ? "{}" : row.payload());
            String body = payload.has("bodyText") ? payload.get("bodyText").asText() : "Scheduled message";
            String purpose = payload.has("purpose") ? payload.get("purpose").asText() : "SERVICE";
            String address = payload.has("destinationAddress") ? payload.get("destinationAddress").asText() : null;
            String template = payload.has("templateName") ? payload.get("templateName").asText() : null;
            conversationService.composeOutbound(
                    tenantId,
                    workspaceId,
                    null,
                    row.conversationId(),
                    new CommunicationModels.ComposeMessageCommand(
                            body, purpose, address, "scheduled:" + scheduledId, template, null
                    )
            );
        } catch (Exception ex) {
            throw new IllegalStateException("SCHEDULED_MESSAGE_FIRE failed: " + ex.getMessage(), ex);
        }
    }

    private record Row(UUID conversationId, String payload) {
    }
}
