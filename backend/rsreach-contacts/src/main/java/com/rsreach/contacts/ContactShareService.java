package com.rsreach.contacts;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactShareService {

    private final ContactShareRepository shareRepository;
    private final ContactRepository contactRepository;
    private final ContactGroupRepository contactGroupRepository;
    private final AuditService auditService;

    public ContactShareService(
            ContactShareRepository shareRepository,
            ContactRepository contactRepository,
            ContactGroupRepository contactGroupRepository,
            AuditService auditService
    ) {
        this.shareRepository = shareRepository;
        this.contactRepository = contactRepository;
        this.contactGroupRepository = contactGroupRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ContactShareModels.Access resolveContactAccess(UUID tenantId, UUID workspaceId, UUID contactId) {
        Optional<ContactModels.ContactView> home = contactRepository.findByIdHomeOnly(tenantId, workspaceId, contactId);
        if (home.isPresent()) {
            return new ContactShareModels.Access(ContactShareModels.AccessLevel.HOME, workspaceId);
        }
        Optional<ContactModels.ContactView> any = contactRepository.findByIdAnyHome(tenantId, contactId);
        if (any.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact not found");
        }
        Optional<String> permission = shareRepository.findActiveSharePermission(
                tenantId, workspaceId, ContactShareModels.RESOURCE_CONTACT, contactId);
        if (permission.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Contact not found");
        }
        ContactShareModels.AccessLevel level = ContactShareModels.PERM_WRITE.equalsIgnoreCase(permission.get())
                ? ContactShareModels.AccessLevel.WRITE
                : ContactShareModels.AccessLevel.READ;
        return new ContactShareModels.Access(level, any.get().homeWorkspaceId());
    }

    @Transactional
    public ContactShareModels.ShareView shareContact(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID contactId,
            ContactShareModels.CreateShareCommand command
    ) {
        ContactModels.ContactView contact = contactRepository.findByIdHomeOnly(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        return createShare(
                tenantId, workspaceId, actorId, ContactShareModels.RESOURCE_CONTACT,
                contact.id(), command, "contact.shared"
        );
    }

    @Transactional
    public ContactShareModels.ShareView shareGroup(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID groupId,
            ContactShareModels.CreateShareCommand command
    ) {
        contactGroupRepository.findByIdHomeOnly(tenantId, workspaceId, groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact group not found"));
        return createShare(
                tenantId, workspaceId, actorId, ContactShareModels.RESOURCE_CONTACT_GROUP,
                groupId, command, "contact.group.shared"
        );
    }

    @Transactional(readOnly = true)
    public ContactShareModels.SharePage listContactShares(UUID tenantId, UUID workspaceId, UUID contactId) {
        contactRepository.findByIdHomeOnly(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        return new ContactShareModels.SharePage(
                shareRepository.listForResource(
                        tenantId, workspaceId, ContactShareModels.RESOURCE_CONTACT, contactId)
        );
    }

    @Transactional(readOnly = true)
    public ContactShareModels.SharePage listGroupShares(UUID tenantId, UUID workspaceId, UUID groupId) {
        contactGroupRepository.findByIdHomeOnly(tenantId, workspaceId, groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact group not found"));
        return new ContactShareModels.SharePage(
                shareRepository.listForResource(
                        tenantId, workspaceId, ContactShareModels.RESOURCE_CONTACT_GROUP, groupId)
        );
    }

    @Transactional
    public void revokeContactShare(
            UUID tenantId, UUID workspaceId, UUID actorId, UUID contactId, UUID shareId
    ) {
        contactRepository.findByIdHomeOnly(tenantId, workspaceId, contactId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact not found"));
        revoke(
                tenantId, workspaceId, actorId, contactId,
                ContactShareModels.RESOURCE_CONTACT, shareId, "contact.share_revoked"
        );
    }

    @Transactional
    public void revokeGroupShare(
            UUID tenantId, UUID workspaceId, UUID actorId, UUID groupId, UUID shareId
    ) {
        contactGroupRepository.findByIdHomeOnly(tenantId, workspaceId, groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact group not found"));
        revoke(
                tenantId, workspaceId, actorId, groupId,
                ContactShareModels.RESOURCE_CONTACT_GROUP, shareId, "contact.group.share_revoked"
        );
    }

    private ContactShareModels.ShareView createShare(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            String resourceType,
            UUID resourceId,
            ContactShareModels.CreateShareCommand command,
            String auditAction
    ) {
        if (command == null || command.targetWorkspaceId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "targetWorkspaceId is required");
        }
        UUID target = command.targetWorkspaceId();
        if (target.equals(workspaceId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cannot share to the same workspace");
        }
        if (!shareRepository.workspaceExists(tenantId, target)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Target workspace not found");
        }
        String permission = normalizePermission(command.permission());
        Optional<ContactShareModels.ShareView> existing = shareRepository.findByNaturalKey(
                tenantId, workspaceId, target, resourceType, resourceId);
        if (existing.isPresent() && existing.get().revokedAt() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Active share already exists for this target");
        }
        try {
            UUID shareId;
            if (existing.isPresent()) {
                shareId = existing.get().id();
                shareRepository.reactivate(shareId, permission);
            } else {
                shareId = shareRepository.insertShare(
                        tenantId, workspaceId, target, resourceType, resourceId, permission, actorId);
            }
            auditService.record(
                    RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                    auditAction, resourceType.toLowerCase(Locale.ROOT), resourceId.toString(),
                    "{\"shareId\":\"" + shareId + "\",\"permission\":\"" + permission + "\"}"
            );
            return shareRepository.findById(tenantId, resourceId, resourceType, shareId)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Share create failed"));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Active share already exists for this target");
        }
    }

    private void revoke(
            UUID tenantId,
            UUID workspaceId,
            UUID actorId,
            UUID resourceId,
            String resourceType,
            UUID shareId,
            String auditAction
    ) {
        ContactShareModels.ShareView share = shareRepository.findById(tenantId, resourceId, resourceType, shareId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Share not found"));
        if (!share.sourceWorkspaceId().equals(workspaceId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Share not found");
        }
        if (share.revokedAt() != null) {
            return;
        }
        int updated = shareRepository.revoke(tenantId, shareId);
        if (updated == 0) {
            return;
        }
        auditService.record(
                RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                auditAction, resourceType.toLowerCase(Locale.ROOT), resourceId.toString(),
                "{\"shareId\":\"" + shareId + "\"}"
        );
    }

    private static String normalizePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return ContactShareModels.PERM_READ;
        }
        String normalized = permission.trim().toUpperCase(Locale.ROOT);
        if (!ContactShareModels.PERM_READ.equals(normalized)
                && !ContactShareModels.PERM_WRITE.equals(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "permission must be READ or WRITE");
        }
        return normalized;
    }
}
