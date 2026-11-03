package com.rsreach.communications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies MESSAGE_SEND outbox events. Channel adapters may override via MessageSender registry.
 */
@Service
public class MessageSendWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageSendWorker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final MessageSenderRegistry senderRegistry;

    public MessageSendWorker(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            MessageSenderRegistry senderRegistry
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.senderRegistry = senderRegistry;
    }

    @Transactional
    public void process(UUID tenantId, UUID workspaceId, String payloadJson) {
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            UUID messageId = UUID.fromString(node.get("messageId").asText());
            CommunicationModels.MessageView message = messageRepository.findById(tenantId, workspaceId, messageId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Message not found for send"));
            if ("SENT".equals(message.status()) || "DELIVERED".equals(message.status()) || "READ".equals(message.status())) {
                return;
            }
            CommunicationModels.ConversationView conversation = conversationRepository
                    .findById(tenantId, workspaceId, message.conversationId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
            MessageSender.SendResult result = senderRegistry
                    .resolve(conversation.channel())
                    .send(tenantId, workspaceId, conversation, message);
            if (result.success()) {
                messageRepository.updateStatus(
                        tenantId, workspaceId, messageId, "SENT",
                        result.providerMessageId(), result.providerStatus(), null, null);
                messageRepository.insertStatusEvent(
                        UUID.randomUUID(), tenantId, workspaceId, messageId, "SENT", result.providerStatus());
            } else if (isRetryable(result.errorCode())) {
                // Propagate so OutboxPoller marks retry/backoff instead of completing the outbox.
                throw new IllegalStateException("retryable_send_failed code=" + result.errorCode());
            } else {
                messageRepository.updateStatus(
                        tenantId, workspaceId, messageId, "FAILED",
                        null, result.providerStatus(), result.errorCode(), result.errorMessage());
                messageRepository.insertStatusEvent(
                        UUID.randomUUID(), tenantId, workspaceId, messageId, "FAILED", result.providerStatus());
                log.warn("message_send_failed messageId={} code={}", messageId, result.errorCode());
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "MESSAGE_SEND failed: " + ex.getMessage());
        }
    }

    private static boolean isRetryable(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        String c = errorCode.toLowerCase();
        return c.contains("429") || c.contains("503") || c.contains("throttl") || c.contains("rate");
    }
}
