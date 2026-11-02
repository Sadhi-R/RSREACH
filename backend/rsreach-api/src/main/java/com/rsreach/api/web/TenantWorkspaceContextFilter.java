package com.rsreach.api.web;

import com.rsreach.common.RsReachConstants;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.TenancyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class TenantWorkspaceContextFilter extends OncePerRequestFilter {

    private final TenancyService tenancyService;

    public TenantWorkspaceContextFilter(TenancyService tenancyService) {
        this.tenancyService = tenancyService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            if (path.startsWith("/api/v1/app/") && !path.startsWith("/api/v1/app/auth/")) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof RsReachPrincipal principal
                        && RsReachConstants.REALM_CUSTOMER.equals(principal.getRealm())) {
                    String tenantHeader = request.getHeader(RsReachConstants.HEADER_TENANT_ID);
                    String workspaceHeader = request.getHeader(RsReachConstants.HEADER_WORKSPACE_ID);
                    if (tenantHeader != null && !tenantHeader.isBlank()) {
                        UUID tenantId = UUID.fromString(tenantHeader);
                        tenancyService.assertTenantMembership(principal.getId(), tenantId);
                        UUID workspaceId = null;
                        if (workspaceHeader != null && !workspaceHeader.isBlank()) {
                            workspaceId = UUID.fromString(workspaceHeader);
                            tenancyService.assertWorkspaceMembership(principal.getId(), tenantId, workspaceId);
                        }
                        TenantContext.set(tenantId, workspaceId);
                    }
                }
            }
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException ex) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"VALIDATION_FAILED\",\"message\":\"Invalid tenant/workspace header\"}");
        } catch (com.rsreach.common.error.ApiException ex) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"" + ex.getMessage() + "\"}");
        } finally {
            TenantContext.clear();
        }
    }
}
