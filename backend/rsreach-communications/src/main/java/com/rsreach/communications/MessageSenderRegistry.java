package com.rsreach.communications;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageSenderRegistry {

    private final Map<String, MessageSender> byChannel = new HashMap<>();

    public MessageSenderRegistry(List<MessageSender> senders) {
        for (MessageSender sender : senders) {
            byChannel.put(sender.channel(), sender);
        }
        byChannel.putIfAbsent("INTERNAL_TEST", new MessageSender() {
            @Override
            public String channel() {
                return "INTERNAL_TEST";
            }

            @Override
            public SendResult send(
                    java.util.UUID tenantId,
                    java.util.UUID workspaceId,
                    CommunicationModels.ConversationView conversation,
                    CommunicationModels.MessageView message
            ) {
                return SendResult.ok("internal-" + message.id());
            }
        });
    }

    public MessageSender resolve(String channel) {
        MessageSender sender = byChannel.get(channel);
        if (sender != null) {
            return sender;
        }
        return new MessageSender() {
            @Override
            public String channel() {
                return channel;
            }

            @Override
            public SendResult send(
                    java.util.UUID tenantId,
                    java.util.UUID workspaceId,
                    CommunicationModels.ConversationView conversation,
                    CommunicationModels.MessageView message
            ) {
                return SendResult.fail("NO_ADAPTER", "No sender for channel " + channel);
            }
        };
    }
}
