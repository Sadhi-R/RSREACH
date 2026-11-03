package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent dynamic group membership materialization.
 * Replaces {@code source=DYNAMIC} members only; preserves MANUAL memberships.
 */
@Service
public class ContactGroupMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ContactGroupMaterializer.class);
    private static final int BATCH_SIZE = 200;

    private final ContactGroupRepository groupRepository;
    private final ContactRepository contactRepository;

    public ContactGroupMaterializer(
            ContactGroupRepository groupRepository,
            ContactRepository contactRepository
    ) {
        this.groupRepository = groupRepository;
        this.contactRepository = contactRepository;
    }

    @Transactional
    public void materialize(UUID tenantId, UUID workspaceId, UUID groupId) {
        if (tenantId == null || workspaceId == null || groupId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tenant and workspace context required for group refresh");
        }
        ContactGroupModels.GroupView group = groupRepository.lockById(tenantId, workspaceId, groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Contact group not found"));
        if (!"DYNAMIC".equals(group.groupType())) {
            return;
        }
        if (!"ACTIVE".equals(group.status())) {
            return;
        }

        groupRepository.markMaterializationRunning(tenantId, workspaceId, groupId);
        try {
            List<ContactModels.CustomFieldDef> defs = contactRepository.listFieldDefs(tenantId, workspaceId, false);
            // Stored filters may reference fields later marked inactive; evaluate against all known defs.
            // Truly unknown keys fail closed and mark the group FAILED.
            GroupFilterModels.FilterDefinition filter = GroupFilterValidator.fromJson(
                    CustomFieldValidator.toJson(group.filterDefinition()),
                    defs
            );
            GroupFilterQueryBuilder.BuiltFilter built = GroupFilterQueryBuilder.build(filter);

            groupRepository.deleteDynamicMembers(tenantId, workspaceId, groupId);

            int offset = 0;
            while (true) {
                List<UUID> batch = groupRepository.listMatchingContactIds(
                        tenantId, workspaceId, built, BATCH_SIZE, offset
                );
                if (batch.isEmpty()) {
                    break;
                }
                groupRepository.insertDynamicMembers(tenantId, workspaceId, groupId, batch);
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
                offset += BATCH_SIZE;
            }
            groupRepository.markMaterializationSuccess(tenantId, workspaceId, groupId);
            log.info(
                    "contact_group_refresh_ok groupId={} tenantId={} workspaceId={}",
                    groupId, tenantId, workspaceId
            );
        } catch (RuntimeException ex) {
            String message = ex instanceof ApiException api ? api.getMessage() : "Materialization failed";
            groupRepository.markMaterializationFailed(tenantId, workspaceId, groupId, message);
            throw ex;
        }
    }
}
