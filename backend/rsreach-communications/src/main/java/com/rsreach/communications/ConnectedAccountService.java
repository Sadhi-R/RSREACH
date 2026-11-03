package com.rsreach.communications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.audit.AuditService;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.secrets.EncryptedSecret;
import com.rsreach.persistence.secrets.IntegrationSecretRepository;
import com.rsreach.persistence.secrets.SecretEncryptionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectedAccountService {

    private static final Set<String> PROVIDERS = Set.of(
            "whatsapp", "gmail", "microsoft_graph", "sendgrid", "ses", "mailgun", "mock"
    );
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectedAccountRepository accountRepository;
    private final SecretEncryptionService secretEncryptionService;
    private final IntegrationSecretRepository secretRepository;
    private final AuditService auditService;
    private final EntitlementService entitlementService;
    private final String keyAlias;
    private final int keyVersion;

    public ConnectedAccountService(
            ConnectedAccountRepository accountRepository,
            SecretEncryptionService secretEncryptionService,
            IntegrationSecretRepository secretRepository,
            AuditService auditService,
            EntitlementService entitlementService,
            @Value("${rsreach.secrets.key-alias:rsreach-master}") String keyAlias,
            @Value("${rsreach.secrets.key-version:1}") int keyVersion
    ) {
        this.accountRepository = accountRepository;
        this.secretEncryptionService = secretEncryptionService;
        this.secretRepository = secretRepository;
        this.auditService = auditService;
        this.entitlementService = entitlementService;
        this.keyAlias = keyAlias;
        this.keyVersion = keyVersion;
    }

    @Transactional(readOnly = true)
    public List<CommunicationModels.ConnectedAccountView> list(UUID tenantId, UUID workspaceId) {
        return accountRepository.list(tenantId, workspaceId);
    }

    @Transactional(readOnly = true)
    public CommunicationModels.ConnectedAccountView get(UUID tenantId, UUID workspaceId, UUID id) {
        return accountRepository.findById(tenantId, workspaceId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Connected account not found"));
    }

    @Transactional
    public CommunicationModels.ConnectedAccountView connect(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            CommunicationModels.CreateConnectedAccountCommand command
    ) {
        if (command == null || command.provider() == null || command.provider().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "provider is required");
        }
        String provider = command.provider().trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported provider");
        }
        String ownership = command.ownershipMode() == null || command.ownershipMode().isBlank()
                ? "CUSTOMER"
                : command.ownershipMode().trim().toUpperCase(Locale.ROOT);
        if (!ownership.equals("CUSTOMER") && !ownership.equals("PLATFORM")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "ownershipMode must be CUSTOMER or PLATFORM");
        }
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? provider
                : command.displayName().trim();
        UUID id = UUID.randomUUID();
        // Entitlement: check CONNECTED_ACCOUNTS limit before inserting
        BillingModels.CheckResult accountCheck = entitlementService.check(tenantId, BillingModels.METER_CONNECTED_ACCOUNTS, 1);
        if (!accountCheck.allowed()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Entitlement limit exceeded: CONNECTED_ACCOUNTS – " + accountCheck.reason());
        }
        String secretName = null;
        if (command.accessToken() != null && !command.accessToken().isBlank()) {
            secretName = "connected-account-" + id;
            EncryptedSecret encrypted = secretEncryptionService.encrypt(
                    keyAlias, keyVersion, command.accessToken().getBytes(StandardCharsets.UTF_8));
            secretRepository.saveSecret(tenantId, workspaceId, secretName, encrypted);
        }
        String metadataJson = "{}";
        try {
            metadataJson = MAPPER.writeValueAsString(
                    command.metadata() == null ? Map.of() : command.metadata());
        } catch (Exception ignored) {
            metadataJson = "{}";
        }
        accountRepository.insert(
                id, tenantId, workspaceId, provider, ownership, displayName,
                command.externalAccountRef(), secretName, metadataJson, actorId
        );
        // Entitlement: record CONNECTED_ACCOUNTS usage after successful insert
        entitlementService.consume(
                tenantId, workspaceId,
                BillingModels.METER_CONNECTED_ACCOUNTS, 1L,
                "CONNECTED_ACCOUNT:" + id,
                "connected_account", id.toString()
        );
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "integration.connected", "connected_account", id.toString(),
                "{\"provider\":\"" + provider + "\"}"
        );
        return get(tenantId, workspaceId, id);
    }

    @Transactional
    public void disconnect(UUID tenantId, UUID workspaceId, UUID actorId, UUID id) {
        get(tenantId, workspaceId, id);
        int updated = accountRepository.disconnect(tenantId, workspaceId, id);
        if (updated == 0) {
            return;
        }
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "integration.disconnected", "connected_account", id.toString(), "{}"
        );
    }

    @Transactional(readOnly = true)
    public String decryptAccessToken(UUID tenantId, UUID workspaceId, UUID accountId) {
        String secretName = accountRepository.findSecretName(tenantId, workspaceId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Connected account secret not found"));
        EncryptedSecret secret = secretRepository.findSecret(tenantId, workspaceId, secretName)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Secret material not found"));
        return new String(secretEncryptionService.decrypt(secret), StandardCharsets.UTF_8);
    }

    /**
     * Re-encrypts a refreshed OAuth access token in place. Never returns the plaintext token.
     */
    @Transactional
    public void refreshAccessToken(UUID tenantId, UUID workspaceId, UUID accountId, String newAccessToken) {
        if (newAccessToken == null || newAccessToken.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "newAccessToken is required");
        }
        String secretName = accountRepository.findSecretName(tenantId, workspaceId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Connected account secret not found"));
        EncryptedSecret encrypted = secretEncryptionService.encrypt(
                keyAlias, keyVersion, newAccessToken.getBytes(StandardCharsets.UTF_8));
        secretRepository.saveSecret(tenantId, workspaceId, secretName, encrypted);
    }

    @Transactional(readOnly = true)
    public String resolveExternalRef(UUID tenantId, UUID workspaceId, UUID accountId) {
        return get(tenantId, workspaceId, accountId).externalAccountRef();
    }

    /**
     * Stores a refresh token under a sibling secret name. Never returns plaintext.
     */
    @Transactional
    public void storeRefreshToken(UUID tenantId, UUID workspaceId, UUID accountId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "refreshToken is required");
        }
        get(tenantId, workspaceId, accountId);
        String secretName = "connected-account-refresh-" + accountId;
        EncryptedSecret encrypted = secretEncryptionService.encrypt(
                keyAlias, keyVersion, refreshToken.getBytes(StandardCharsets.UTF_8));
        secretRepository.saveSecret(tenantId, workspaceId, secretName, encrypted);
    }

    @Transactional(readOnly = true)
    public Optional<String> decryptRefreshToken(UUID tenantId, UUID workspaceId, UUID accountId) {
        String secretName = "connected-account-refresh-" + accountId;
        return secretRepository.findSecret(tenantId, workspaceId, secretName)
                .map(secret -> new String(secretEncryptionService.decrypt(secret), StandardCharsets.UTF_8));
    }

    /**
     * OAuth start URL. Live authorize URLs require operator client ids; otherwise mock.
     */
    public Map<String, String> oauthStart(String provider, UUID accountId, String clientId, String redirectUri) {
        String p = provider == null ? "gmail" : provider.trim().toLowerCase(Locale.ROOT);
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            return Map.of(
                    "provider", p,
                    "authorizationUrl",
                    "https://oauth.mock.rsreach.local/" + p + "/authorize?state=" + accountId,
                    "mode", "mock"
            );
        }
        String authBase = "gmail".equals(p)
                ? "https://accounts.google.com/o/oauth2/v2/auth"
                : "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
        String scope = "gmail".equals(p)
                ? "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send"
                : "https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/Mail.Send offline_access";
        String url = authBase
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&redirect_uri=" + redirectUri
                + "&scope=" + scope.replace(" ", "%20")
                + "&access_type=offline"
                + "&state=" + accountId;
        return Map.of("provider", p, "authorizationUrl", url, "mode", "live");
    }
}
