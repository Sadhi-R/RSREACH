package com.rsreach.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rsreach.audit.AuditService;
import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private ContactShareService contactShareService;
    @Mock
    private AuditService auditService;
    @Mock
    private DuplicateDetectionService duplicateDetectionService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ContactService contactService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Stub entitlement check to always allow (not the focus of this unit test).
        // lenient: archive/restore and early-fail create paths never call check.
        lenient().when(entitlementService.check(any(), any(), any(Long.class)))
                .thenReturn(new BillingModels.CheckResult(true, null));
        contactService = new ContactService(
                contactRepository, contactShareService, auditService, duplicateDetectionService,
                entitlementService, eventPublisher);
    }

    @Test
    void createNormalizesChannelsAndRecordsAuditWithoutRawIdentifiers() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.listFieldDefs(tenantId, workspaceId, true)).thenReturn(List.of());
        when(contactRepository.activeChannelExists(any(), any(), any(), any(), isNull())).thenReturn(false);
        when(contactRepository.insertContact(any(), any(), any(), any(), any(), any(), any())).thenReturn(contactId);
        when(contactRepository.findById(tenantId, workspaceId, contactId)).thenReturn(Optional.of(sampleView(contactId)));

        ContactModels.ContactView created = contactService.create(
                tenantId,
                workspaceId,
                actorId,
                new ContactModels.ContactCreateCommand(
                        " Ada ",
                        List.of(new ContactModels.ChannelInput("EMAIL", "Ada@Example.COM", true, null)),
                        Map.of()
                )
        );

        assertThat(created.id()).isEqualTo(contactId);
        ArgumentCaptor<List<ContactRepository.PreparedChannel>> channelsCaptor = ArgumentCaptor.forClass(List.class);
        verify(contactRepository).replaceChannels(eq(tenantId), eq(workspaceId), eq(contactId), channelsCaptor.capture());
        assertThat(channelsCaptor.getValue()).hasSize(1);
        assertThat(channelsCaptor.getValue().get(0).valueNormalized()).isEqualTo("ada@example.com");
        verify(auditService).record(
                eq("customer"), eq(actorId), eq(tenantId), eq(workspaceId),
                eq("contact.created"), eq("contact"), eq(contactId.toString()),
                eq("{\"channelCount\":1}")
        );
    }

    @Test
    void createRejectsDuplicateIdentifierInWorkspace() {
        when(contactRepository.activeChannelExists(any(), any(), eq("EMAIL"), eq("dup@example.com"), isNull()))
                .thenReturn(true);

        assertThatThrownBy(() -> contactService.create(
                tenantId,
                workspaceId,
                actorId,
                new ContactModels.ContactCreateCommand(
                        "Dup",
                        List.of(new ContactModels.ChannelInput("EMAIL", "dup@example.com", true, null)),
                        Map.of()
                )
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(contactRepository, never()).insertContact(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void archiveAndRestoreEmitAuditEvents() {
        UUID contactId = UUID.randomUUID();
        when(contactShareService.resolveContactAccess(tenantId, workspaceId, contactId))
                .thenReturn(new ContactShareModels.Access(ContactShareModels.AccessLevel.HOME, workspaceId));
        when(contactRepository.findById(tenantId, workspaceId, contactId))
                .thenReturn(Optional.of(sampleView(contactId, "ARCHIVED")))
                .thenReturn(Optional.of(sampleView(contactId, "ARCHIVED")))
                .thenReturn(Optional.of(sampleView(contactId, "ACTIVE")));
        when(contactRepository.listChannels(tenantId, workspaceId, contactId)).thenReturn(List.of());
        when(contactRepository.isMergeLoser(tenantId, workspaceId, contactId)).thenReturn(false);

        contactService.archive(tenantId, workspaceId, actorId, contactId);
        verify(auditService).record(any(), any(), any(), any(), eq("contact.archived"), any(), any(), any());

        contactService.restore(tenantId, workspaceId, actorId, contactId);
        verify(auditService).record(any(), any(), any(), any(), eq("contact.restored"), any(), any(), any());
    }

    private ContactModels.ContactView sampleView(UUID contactId) {
        return sampleView(contactId, "ACTIVE");
    }

    private ContactModels.ContactView sampleView(UUID contactId, String status) {
        return new ContactModels.ContactView(
                contactId, tenantId, workspaceId, "Ada", status,
                "ada@example.com", null, Map.of(), List.of(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), null, "HOME"
        );
    }
}
