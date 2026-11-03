package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fail-closed eligibility for a contact + channel + purpose + explicit address.
 * Group membership is intentionally ignored. Primary-address fallback is not used.
 */
@Service
public class ConsentEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ConsentEvaluationService.class);

    private final ConsentRepository consentRepository;
    private final ContactRepository contactRepository;

    public ConsentEvaluationService(
            ConsentRepository consentRepository,
            ContactRepository contactRepository
    ) {
        this.consentRepository = consentRepository;
        this.contactRepository = contactRepository;
    }

    /**
     * Evaluate eligibility for the exact address that a future send path will use.
     *
     * @param address required raw or display address for the intended recipient channel;
     *                never inferred from the contact primary fields
     */
    @Transactional(readOnly = true)
    public ConsentModels.EligibilityResult evaluate(
            UUID tenantId,
            UUID workspaceId,
            UUID contactId,
            String channelType,
            String purpose,
            String address
    ) {
        if (tenantId == null || workspaceId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tenant and workspace context required");
        }
        String channel = requireChannel(channelType);
        String purposeNorm = requirePurpose(purpose);

        if (address == null || address.isBlank()) {
            return new ConsentModels.EligibilityResult(
                    false,
                    "ADDRESS_REQUIRED",
                    "Explicit recipient address is required; primary-address eligibility is not supported",
                    "UNKNOWN",
                    false,
                    null
            );
        }

        ContactModels.ContactView contact;
        try {
            contact = contactRepository.findById(tenantId, workspaceId, contactId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        } catch (ApiException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.warn("Consent evaluation contact lookup failed contactId={} reasonCode=EVALUATION_ERROR", contactId);
            return failClosed("EVALUATION_ERROR", "Eligibility evaluation failed", "UNKNOWN", null);
        }

        String normalized;
        try {
            normalized = normalizeAddress(channel, address);
        } catch (ApiException ex) {
            return new ConsentModels.EligibilityResult(
                    false,
                    "ADDRESS_INVALID",
                    ex.getMessage(),
                    "UNKNOWN",
                    false,
                    null
            );
        }

        String storageChannel = storageChannelType(channel);
        boolean addressOnContact;
        try {
            addressOnContact = contactRepository.hasActiveChannelValue(
                    tenantId, workspaceId, contact.id(), storageChannel, normalized);
        } catch (DataAccessException ex) {
            log.warn("Consent evaluation channel check failed contactId={} reasonCode=EVALUATION_ERROR", contactId);
            return failClosed("EVALUATION_ERROR", "Eligibility evaluation failed", "UNKNOWN", normalized);
        }
        if (!addressOnContact) {
            return new ConsentModels.EligibilityResult(
                    false,
                    "ADDRESS_NOT_ON_CONTACT",
                    "Address is not an active channel on this contact",
                    "UNKNOWN",
                    false,
                    normalized
            );
        }

        String consentStatus;
        try {
            consentStatus = consentRepository.consentStatusOrUnknown(
                    tenantId, workspaceId, contactId, channel, purposeNorm);
        } catch (DataAccessException ex) {
            log.warn("Consent evaluation status lookup failed contactId={} reasonCode=EVALUATION_ERROR", contactId);
            return failClosed("EVALUATION_ERROR", "Eligibility evaluation failed", "UNKNOWN", normalized);
        }

        boolean suppressed;
        try {
            suppressed = consentRepository.findActiveSuppression(
                    tenantId, workspaceId, channel, normalized).isPresent();
        } catch (DataAccessException ex) {
            log.warn("Consent evaluation suppression lookup failed contactId={} reasonCode=EVALUATION_ERROR", contactId);
            return failClosed("EVALUATION_ERROR", "Eligibility evaluation failed", consentStatus, normalized);
        }

        if (suppressed) {
            return new ConsentModels.EligibilityResult(
                    false, "SUPPRESSED", "Address is suppressed for this channel", consentStatus, true, normalized);
        }
        if (!"OPTED_IN".equals(consentStatus)) {
            String code = switch (consentStatus) {
                case "OPTED_OUT" -> "OPTED_OUT";
                case "UNKNOWN" -> "CONSENT_UNKNOWN";
                default -> "CONSENT_MISSING";
            };
            return new ConsentModels.EligibilityResult(
                    false,
                    code,
                    "Contact is not opted in for " + channel + "/" + purposeNorm,
                    consentStatus,
                    false,
                    normalized
            );
        }
        return new ConsentModels.EligibilityResult(
                true, "ELIGIBLE", "Contact is eligible for the specified address", consentStatus, false, normalized);
    }

    private static ConsentModels.EligibilityResult failClosed(
            String reasonCode,
            String message,
            String consentStatus,
            String evaluatedAddress
    ) {
        return new ConsentModels.EligibilityResult(
                false, reasonCode, message, consentStatus, false, evaluatedAddress);
    }

    private static String normalizeAddress(String channel, String address) {
        return switch (channel) {
            case "EMAIL" -> ContactIdentifierNormalizer.normalizeEmail(address);
            case "WHATSAPP" -> ContactIdentifierNormalizer.normalizePhone(address);
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported channel");
        };
    }

    /** Consent WHATSAPP maps to contact channel type PHONE. */
    private static String storageChannelType(String consentChannel) {
        return "WHATSAPP".equals(consentChannel) ? "PHONE" : consentChannel;
    }

    private static String requireChannel(String channel) {
        if (channel == null || !ConsentModels.CHANNELS.contains(channel.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channelType must be EMAIL or WHATSAPP");
        }
        return channel.trim().toUpperCase(Locale.ROOT);
    }

    private static String requirePurpose(String purpose) {
        if (purpose == null || !ConsentModels.PURPOSES.contains(purpose.trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid purpose");
        }
        return purpose.trim().toUpperCase(Locale.ROOT);
    }
}
