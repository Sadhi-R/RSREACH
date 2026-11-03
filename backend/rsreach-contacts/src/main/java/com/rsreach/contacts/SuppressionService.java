package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SuppressionService {

    private final ConsentRepository consentRepository;
    private final AuditService auditService;

    public SuppressionService(ConsentRepository consentRepository, AuditService auditService) {
        this.consentRepository = consentRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ConsentModels.SuppressionPage list(
            UUID tenantId,
            UUID workspaceId,
            boolean activeOnly,
            int page,
            int size
    ) {
        return consentRepository.listSuppressions(tenantId, workspaceId, activeOnly, page, size);
    }

    @Transactional
    public ConsentModels.SuppressionView create(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            ConsentModels.SuppressionCreateCommand command
    ) {
        String channel = requireChannel(command.channelType());
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "reason is required");
        }
        String reason = command.reason().trim();
        if (reason.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "reason is too long");
        }
        String raw = command.value() == null ? "" : command.value().trim();
        String normalized = normalize(channel, raw);
        if (consentRepository.findActiveSuppression(tenantId, workspaceId, channel, normalized).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "Active suppression already exists for this address");
        }
        try {
            UUID id = consentRepository.insertSuppression(
                    tenantId, workspaceId, channel, raw, normalized, reason, actorId);
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    "suppression.created", "contact_suppression", id.toString(),
                    "{\"channel\":\"" + channel + "\"}"
            );
            return consentRepository.findSuppressionById(tenantId, workspaceId, id)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Suppression create failed"));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Active suppression already exists for this address");
        }
    }

    @Transactional
    public ConsentModels.SuppressionView release(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID suppressionId
    ) {
        consentRepository.releaseSuppression(tenantId, workspaceId, suppressionId, actorId);
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "suppression.released", "contact_suppression", suppressionId.toString(), "{}"
        );
        return consentRepository.findSuppressionById(tenantId, workspaceId, suppressionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Suppression not found"));
    }

    private static String requireChannel(String channel) {
        if (channel == null || !ConsentModels.CHANNELS.contains(channel.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channelType must be EMAIL or WHATSAPP");
        }
        return channel.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalize(String channel, String raw) {
        return switch (channel) {
            case "EMAIL" -> ContactIdentifierNormalizer.normalizeEmail(raw);
            case "WHATSAPP" -> ContactIdentifierNormalizer.normalizePhone(raw);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported channel");
        };
    }
}
