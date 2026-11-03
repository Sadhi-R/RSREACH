package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class ConsentEvaluationServiceTest {

    @Mock
    private ConsentRepository consentRepository;
    @Mock
    private ContactRepository contactRepository;

    private ConsentEvaluationService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID contactId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConsentEvaluationService(consentRepository, contactRepository);
    }

    @Test
    void missingAddressFailsClosedWithoutPrimaryFallback() {
        ConsentModels.EligibilityResult result = service.evaluate(
                tenantId, workspaceId, contactId, "EMAIL", "MARKETING", null);
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("ADDRESS_REQUIRED");
    }

    @Test
    void missingConsentFailsClosed() {
        when(contactRepository.findById(tenantId, workspaceId, contactId)).thenReturn(Optional.of(contact()));
        when(contactRepository.hasActiveChannelValue(
                eq(tenantId), eq(workspaceId), eq(contactId), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(true);
        when(consentRepository.consentStatusOrUnknown(any(), any(), any(), eq("EMAIL"), eq("MARKETING")))
                .thenReturn("UNKNOWN");
        when(consentRepository.findActiveSuppression(any(), any(), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(Optional.empty());

        ConsentModels.EligibilityResult result = service.evaluate(
                tenantId, workspaceId, contactId, "EMAIL", "MARKETING", "ada@example.com");
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("CONSENT_UNKNOWN");
        assertThat(result.evaluatedAddress()).isEqualTo("ada@example.com");
    }

    @Test
    void optedInWithoutSuppressionIsEligibleForExactAddress() {
        when(contactRepository.findById(tenantId, workspaceId, contactId)).thenReturn(Optional.of(contact()));
        when(contactRepository.hasActiveChannelValue(
                eq(tenantId), eq(workspaceId), eq(contactId), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(true);
        when(consentRepository.consentStatusOrUnknown(any(), any(), any(), eq("EMAIL"), eq("MARKETING")))
                .thenReturn("OPTED_IN");
        when(consentRepository.findActiveSuppression(any(), any(), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(Optional.empty());

        ConsentModels.EligibilityResult result = service.evaluate(
                tenantId, workspaceId, contactId, "EMAIL", "MARKETING", "Ada@Example.com");
        assertThat(result.eligible()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("ELIGIBLE");
        assertThat(result.evaluatedAddress()).isEqualTo("ada@example.com");
    }

    @Test
    void secondaryAddressDoesNotInheritEligibilityFromPrimary() {
        when(contactRepository.findById(tenantId, workspaceId, contactId)).thenReturn(Optional.of(contact()));
        when(contactRepository.hasActiveChannelValue(
                eq(tenantId), eq(workspaceId), eq(contactId), eq("EMAIL"), eq("other@example.com")))
                .thenReturn(false);

        ConsentModels.EligibilityResult result = service.evaluate(
                tenantId, workspaceId, contactId, "EMAIL", "MARKETING", "other@example.com");
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("ADDRESS_NOT_ON_CONTACT");
    }

    @Test
    void suppressionBlocksEvenWhenOptedIn() {
        when(contactRepository.findById(tenantId, workspaceId, contactId)).thenReturn(Optional.of(contact()));
        when(contactRepository.hasActiveChannelValue(
                eq(tenantId), eq(workspaceId), eq(contactId), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(true);
        when(consentRepository.consentStatusOrUnknown(any(), any(), any(), eq("EMAIL"), eq("MARKETING")))
                .thenReturn("OPTED_IN");
        when(consentRepository.findActiveSuppression(any(), any(), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(Optional.of(new ConsentModels.SuppressionView(
                        UUID.randomUUID(), "EMAIL", "ada@example.com", "ada@example.com",
                        "complaint", Instant.now(), null, UUID.randomUUID(), null
                )));

        ConsentModels.EligibilityResult result = service.evaluate(
                tenantId, workspaceId, contactId, "EMAIL", "MARKETING", "ada@example.com");
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("SUPPRESSED");
        assertThat(result.suppressed()).isTrue();
    }

    @Test
    void suppressionReadFailureFailsClosed() {
        when(contactRepository.findById(tenantId, workspaceId, contactId)).thenReturn(Optional.of(contact()));
        when(contactRepository.hasActiveChannelValue(
                eq(tenantId), eq(workspaceId), eq(contactId), eq("EMAIL"), eq("ada@example.com")))
                .thenReturn(true);
        when(consentRepository.consentStatusOrUnknown(any(), any(), any(), eq("EMAIL"), eq("MARKETING")))
                .thenReturn("OPTED_IN");
        when(consentRepository.findActiveSuppression(any(), any(), eq("EMAIL"), eq("ada@example.com")))
                .thenThrow(new QueryTimeoutException("timeout"));

        ConsentModels.EligibilityResult result = service.evaluate(
                tenantId, workspaceId, contactId, "EMAIL", "MARKETING", "ada@example.com");
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("EVALUATION_ERROR");
    }

    private ContactModels.ContactView contact() {
        return new ContactModels.ContactView(
                contactId, tenantId, workspaceId, "Ada", "ACTIVE",
                "ada@example.com", "+919876543210", Map.of(), List.of(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), null, "HOME"
        );
    }
}
