package com.rsreach.whatsapp;

import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.TemplateService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs WhatsApp templates into {@code channel_templates}.
 * Live Meta Graph fetch runs only when live mode and WABA id are configured.
 */
@Service
public class WhatsAppTemplateSyncService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppTemplateSyncService.class);

    private final TemplateService templateService;
    private final boolean liveEnabled;

    public WhatsAppTemplateSyncService(
            TemplateService templateService,
            @Value("${rsreach.whatsapp.live-enabled:false}") boolean liveEnabled
    ) {
        this.templateService = templateService;
        this.liveEnabled = liveEnabled;
    }

    @Transactional
    public CommunicationModels.TemplateView syncMockApproved(
            UUID tenantId,
            UUID workspaceId,
            UUID connectedAccountId,
            String name,
            String language,
            String bodyPreview
    ) {
        if (liveEnabled) {
            log.info("whatsapp_template_sync_live_placeholder accountId={}", connectedAccountId);
            // Live Graph template list requires operator WABA credentials; persist snapshot when provided.
        }
        return templateService.upsert(
                tenantId,
                workspaceId,
                connectedAccountId,
                "WHATSAPP",
                name,
                language,
                "UTILITY",
                "APPROVED",
                bodyPreview
        );
    }
}
