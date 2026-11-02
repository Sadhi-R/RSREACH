package com.rsreach.api.web;

import com.rsreach.common.RsReachConstants;
import com.rsreach.identity.staff.StaffAuthService;
import com.rsreach.identity.staff.StaffUserRepository;
import com.rsreach.security.auth.RsReachPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/platform")
public class StaffAuthController {

    private final StaffAuthService staffAuthService;
    private final StaffUserRepository staffUserRepository;

    public StaffAuthController(StaffAuthService staffAuthService, StaffUserRepository staffUserRepository) {
        this.staffAuthService = staffAuthService;
        this.staffUserRepository = staffUserRepository;
    }

    @PostMapping("/auth/login")
    public Object login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return staffAuthService.login(
                request.email(),
                request.password(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr()
        );
    }

    @PostMapping("/auth/mfa/verify")
    public StaffAuthService.AuthTokens verifyMfa(
            @Valid @RequestBody MfaVerifyRequest request,
            HttpServletRequest httpRequest
    ) {
        return staffAuthService.verifyMfa(
                request.mfaToken(),
                request.code(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr()
        );
    }

    @PostMapping("/auth/refresh")
    public StaffAuthService.AuthTokens refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest
    ) {
        return staffAuthService.refresh(
                request.refreshToken(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr()
        );
    }

    @PostMapping("/auth/logout")
    public Map<String, String> logout(@RequestBody RefreshRequest request) {
        staffAuthService.logout(request.refreshToken());
        return Map.of("status", "OK");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal RsReachPrincipal principal) {
        var user = staffUserRepository.findById(principal.getId()).orElseThrow();
        return Map.of(
                "id", user.id(),
                "email", user.email(),
                "displayName", user.displayName(),
                "mfaVerified", principal.isMfaVerified(),
                "permissions", principal.getPermissions()
        );
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record MfaVerifyRequest(@NotBlank String mfaToken, @NotBlank String code) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
