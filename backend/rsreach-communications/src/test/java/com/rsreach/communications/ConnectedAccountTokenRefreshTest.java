package com.rsreach.communications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rsreach.audit.AuditService;
import com.rsreach.billing.EntitlementService;
import com.rsreach.persistence.secrets.EncryptedSecret;
import com.rsreach.persistence.secrets.IntegrationSecretRepository;
import com.rsreach.persistence.secrets.SecretEncryptionService;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectedAccountTokenRefreshTest {

    @Mock private ConnectedAccountRepository accountRepository;
    @Mock private SecretEncryptionService secretEncryptionService;
    @Mock private IntegrationSecretRepository secretRepository;
    @Mock private AuditService auditService;
    @Mock private EntitlementService entitlementService;

    @Test
    void refreshAccessTokenReEncryptsWithoutExposingPlaintext() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findSecretName(tenantId, workspaceId, accountId))
                .thenReturn(Optional.of("connected-account-" + accountId));
        EncryptedSecret encrypted = new EncryptedSecret(
                "alias", 1, "cipher".getBytes(StandardCharsets.UTF_8), "nonce".getBytes(StandardCharsets.UTF_8));
        when(secretEncryptionService.encrypt(anyString(), anyInt(), any())).thenReturn(encrypted);

        ConnectedAccountService service = new ConnectedAccountService(
                accountRepository, secretEncryptionService, secretRepository, auditService,
                entitlementService, "alias", 1);
        service.refreshAccessToken(tenantId, workspaceId, accountId, "new-oauth-token");

        ArgumentCaptor<byte[]> plaintext = ArgumentCaptor.forClass(byte[].class);
        verify(secretEncryptionService).encrypt(eq("alias"), eq(1), plaintext.capture());
        assertThat(new String(plaintext.getValue(), StandardCharsets.UTF_8)).isEqualTo("new-oauth-token");
        verify(secretRepository).saveSecret(eq(tenantId), eq(workspaceId), eq("connected-account-" + accountId), eq(encrypted));
    }
}
