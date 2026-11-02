package com.rsreach.admin;

import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.staffaccess.StaffAccessService;
import com.rsreach.tenancy.TenancyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminTenantService {

    private final TenancyService tenancyService;
    private final StaffAccessService staffAccessService;

    public AdminTenantService(TenancyService tenancyService, StaffAccessService staffAccessService) {
        this.tenancyService = tenancyService;
        this.staffAccessService = staffAccessService;
    }

    public List<Map<String, Object>> listTenants() {
        return tenancyService.listAllTenantsForStaff();
    }

    public void updateStatus(RsReachPrincipal staff, UUID tenantId, String status) {
        tenancyService.updateTenantStatus(staff.getId(), tenantId, status);
    }

    public Map<String, Object> viewTenantWithGrant(RsReachPrincipal staff, UUID tenantId) {
        staffAccessService.requireActiveGrant(staff.getId(), tenantId, null);
        return tenancyService.listAllTenantsForStaff().stream()
                .filter(t -> tenantId.equals(t.get("id")))
                .findFirst()
                .orElseThrow();
    }
}
