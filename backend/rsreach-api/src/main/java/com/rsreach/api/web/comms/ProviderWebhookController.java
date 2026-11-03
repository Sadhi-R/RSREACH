package com.rsreach.api.web.comms;

import com.rsreach.common.RsReachConstants;
import com.rsreach.communications.WebhookAccountResolutionException;
import com.rsreach.email.DeliveryWebhookService;
import com.rsreach.persistence.webhook.WebhookIngestResult;
import com.rsreach.security.config.WebhookSecurityProperties;
import com.rsreach.whatsapp.WhatsAppWebhookService;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/webhooks")
public class ProviderWebhookController {

    private final WhatsAppWebhookService whatsAppWebhookService;
    private final DeliveryWebhookService deliveryWebhookService;
    private final WebhookSecurityProperties webhookSecurityProperties;

    public ProviderWebhookController(
            WhatsAppWebhookService whatsAppWebhookService,
            DeliveryWebhookService deliveryWebhookService,
            WebhookSecurityProperties webhookSecurityProperties
    ) {
        this.whatsAppWebhookService = whatsAppWebhookService;
        this.deliveryWebhookService = deliveryWebhookService;
        this.webhookSecurityProperties = webhookSecurityProperties;
    }

    @GetMapping("/whatsapp")
    public ResponseEntity<String> verifyWhatsApp(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge
    ) {
        if (whatsAppWebhookService.verifyChallenge(mode, token, challenge)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("verification_failed");
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> ingest(
            @PathVariable String provider,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody
    ) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        try {
            if ("whatsapp".equals(normalized) || "mock".equals(normalized)) {
                if (!whatsAppWebhookService.verifySignature(signature, rawBody)) {
                    return invalidSignature(true);
                }
                WebhookIngestResult result = whatsAppWebhookService.ingestAuthenticated(normalized, rawBody);
                return accepted(result);
            }
            if (deliveryWebhookService.supports(normalized)) {
                if (!deliveryWebhookService.verifySignature(normalized, signature, rawBody)) {
                    return invalidSignature(false);
                }
                WebhookIngestResult result = deliveryWebhookService.ingestAuthenticated(normalized, rawBody);
                return accepted(result);
            }
            return ResponseEntity.badRequest().body(Map.of("accepted", false, "reason", "unsupported_provider"));
        } catch (WebhookAccountResolutionException ex) {
            return unresolved(ex);
        }
    }

    private ResponseEntity<Map<String, Object>> invalidSignature(boolean includeMockFlag) {
        if (includeMockFlag) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "accepted", false,
                    "reason", "invalid_signature",
                    "allowMockSignatures", webhookSecurityProperties.isAllowMockSignatures()
            ));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "accepted", false,
                "reason", "invalid_signature"
        ));
    }

    private static ResponseEntity<Map<String, Object>> accepted(WebhookIngestResult result) {
        return ResponseEntity.accepted().body(Map.of(
                "accepted", true,
                "inserted", result.inserted(),
                "eventId", result.eventId().toString(),
                "signatureValid", true
        ));
    }

    private static ResponseEntity<Map<String, Object>> unresolved(WebhookAccountResolutionException ex) {
        return switch (ex.reason()) {
            case MISSING_IDENTIFIER -> ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "missing_account_ref"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "accepted", false,
                    "reason", "account_not_found"
            ));
            case AMBIGUOUS -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "accepted", false,
                    "reason", "ambiguous_account"
            ));
        };
    }
}
