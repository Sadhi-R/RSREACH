package com.rsreach.security.jwt;

import com.rsreach.common.RsReachConstants;
import com.rsreach.security.auth.PrincipalLoader;
import com.rsreach.security.auth.RsReachPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final PrincipalLoader principalLoader;

    public JwtAuthenticationFilter(JwtService jwtService, PrincipalLoader principalLoader) {
        this.jwtService = jwtService;
        this.principalLoader = principalLoader;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            String path = request.getRequestURI();
            try {
                if (path.startsWith("/api/v1/platform/")) {
                    authenticate(token, RsReachConstants.REALM_STAFF);
                } else if (path.startsWith("/api/v1/app/")) {
                    authenticate(token, RsReachConstants.REALM_CUSTOMER);
                }
            } catch (Exception ex) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or expired token\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, String realm) {
        JwtService.ParsedJwt parsed = jwtService.parse(token, realm);
        Optional<RsReachPrincipal> principal = RsReachConstants.REALM_STAFF.equals(realm)
                ? principalLoader.loadStaff(parsed.subjectId(), parsed.mfaVerified())
                : principalLoader.loadCustomer(parsed.subjectId(), parsed.mfaVerified());
        RsReachPrincipal user = principal.orElseThrow(() -> new IllegalArgumentException("Principal not found"));
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
