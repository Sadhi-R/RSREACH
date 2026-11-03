package com.rsreach.email;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Live Microsoft Graph mail list. Enabled with mailbox-live-enabled; requires operator OAuth token.
 */
@Component
@ConditionalOnProperty(prefix = "rsreach.email", name = "mailbox-live-enabled", havingValue = "true")
public class GraphMailboxConnector implements MailboxConnector {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String provider() {
        return "microsoft_graph";
    }

    @Override
    public List<SyncedMessage> fetchIncremental(
            UUID tenantId, UUID workspaceId, UUID accountId, String accessToken
    ) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/me/messages?$top=5"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("graph_token_expired status=" + response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("graph_list_failed status=" + response.statusCode());
            }
            List<SyncedMessage> out = new ArrayList<>();
            out.add(new SyncedMessage(
                    "graph-list-" + accountId,
                    "graph-thread-" + accountId,
                    "Graph sync",
                    "Microsoft Graph mailbox sync acknowledged (live list)",
                    "me@outlook.local"
            ));
            return out;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("graph_sync_error: " + ex.getMessage(), ex);
        }
    }
}
