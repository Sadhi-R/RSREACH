package com.rsreach.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.billing.EntitlementService;
import com.rsreach.communications.ConversationService;
import com.rsreach.contacts.ConsentEvaluationService;
import com.rsreach.contacts.ConsentModels;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that when consent evaluation returns ineligible the SEND action is recorded as
 * SKIPPED (not SUCCEEDED) and composeOutbound is never called.
 *
 * Before the Phase 5 M3 fix, executeSend returned a detail JSON with "skipped":true while
 * actionStatus stayed ACTION_SUCCEEDED. After the fix it throws ApiException so that
 * executeAction's catch block sets actionStatus = ACTION_SKIPPED.
 */
@ExtendWith(MockitoExtension.class)
class AutomationConsentSkipTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private EntitlementService entitlementService;
    @Mock private ConsentEvaluationService consentEvaluationService;
    @Mock private ConversationService conversationService;
    @Mock private OutboxRepository outboxRepository;

    private AutomationEngine engine;

    private static final UUID TENANT_ID    = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID  = UUID.randomUUID();
    private static final UUID VERSION_ID   = UUID.randomUUID();
    private static final UUID CONTACT_ID   = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new AutomationEngine(
                jdbcTemplate, entitlementService, consentEvaluationService,
                conversationService, outboxRepository, new ObjectMapper()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_whatsapp_action_is_skipped_when_consent_ineligible() {
        UUID runId = UUID.randomUUID();
        String actionsJson = "[{\"type\":\"SEND_WHATSAPP\",\"params\":"
                + "{\"body_text\":\"Hello\",\"destination_address\":\"+919876543210\"}}]";

        stubForRun(runId, actionsJson);

        ConsentModels.EligibilityResult ineligible =
                new ConsentModels.EligibilityResult(false, "NO_CONSENT", "Not opted in", "UNKNOWN", false);
        when(consentEvaluationService.evaluate(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq(CONTACT_ID),
                anyString(), anyString(), anyString()))
                .thenReturn(ineligible);

        // Act — must not throw
        engine.processRun(TENANT_ID, WORKSPACE_ID, runId);

        // composeOutbound must never be called — consent gate blocked the send
        verify(conversationService, never()).composeOutbound(any(), any(), any(), any(), any());

        // Consent evaluation must have been called (proves we reached the consent check)
        verify(consentEvaluationService).evaluate(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq(CONTACT_ID),
                anyString(), anyString(), anyString());

        // The action log INSERT must record status = ACTION_SKIPPED.
        // Column layout: id, runId, tenantId, workspaceId, stepIndex, actionType, status, detailJson
        verify(jdbcTemplate).update(
                contains("automation_action_log"),
                any(),    // id
                any(),    // runId
                any(),    // tenantId
                any(),    // workspaceId
                any(),    // stepIndex
                any(),    // actionType
                eq(AutomationModels.ACTION_SKIPPED),
                any()     // detailJson
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_email_action_is_skipped_when_consent_opted_out() {
        UUID runId = UUID.randomUUID();
        String actionsJson = "[{\"type\":\"SEND_EMAIL\",\"params\":"
                + "{\"body_text\":\"Hi\",\"destination_address\":\"user@example.com\"}}]";

        stubForRun(runId, actionsJson);

        ConsentModels.EligibilityResult optedOut =
                new ConsentModels.EligibilityResult(false, "OPTED_OUT", "Opted out", "OPTED_OUT", false);
        when(consentEvaluationService.evaluate(
                eq(TENANT_ID), eq(WORKSPACE_ID), eq(CONTACT_ID),
                anyString(), anyString(), anyString()))
                .thenReturn(optedOut);

        engine.processRun(TENANT_ID, WORKSPACE_ID, runId);

        verify(conversationService, never()).composeOutbound(any(), any(), any(), any(), any());

        verify(jdbcTemplate).update(
                contains("automation_action_log"),
                any(), any(), any(), any(), any(), any(),
                eq(AutomationModels.ACTION_SKIPPED),
                any()
        );
    }

    // -----------------------------------------------------------------------
    // Shared stub helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubForRun(UUID runId, String actionsJson) {
        AutomationEngine.RunRecord run = new AutomationEngine.RunRecord(
                runId, TENANT_ID, WORKSPACE_ID, WORKFLOW_ID, VERSION_ID,
                AutomationModels.RUN_QUEUED, 0, CONTACT_ID, "{}"
        );
        AutomationEngine.VersionRecord version = new AutomationEngine.VersionRecord(
                VERSION_ID, "[]", actionsJson
        );

        // loadRun: query(sql, rowMapper, runId, tenantId, workspaceId)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq(runId), eq(TENANT_ID), eq(WORKSPACE_ID)))
                .thenReturn(List.of(run));

        // entitlement consume is void
        doNothing().when(entitlementService).consume(
                eq(TENANT_ID), eq(WORKSPACE_ID), anyString(), anyLong(), anyString(), anyString(), anyString());

        // isWithinCooldown: query(sql, rowMapper, tenantId, workspaceId, workflowId, contactId)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq(TENANT_ID), eq(WORKSPACE_ID), eq(WORKFLOW_ID), eq(CONTACT_ID)))
                .thenReturn(List.of());

        // loadVersion: query(sql, rowMapper, versionId)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(VERSION_ID)))
                .thenReturn(List.of(version));

        // buildContext contact: query(sql, rowMapper, contactId, tenantId)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq(CONTACT_ID), eq(TENANT_ID)))
                .thenReturn(List.of());
    }
}
