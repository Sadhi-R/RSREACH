package com.rsreach.api.web;

import com.rsreach.common.RsReachConstants;
import com.rsreach.identity.customer.CustomerAuthService;
import com.rsreach.identity.customer.CustomerUserRepository;
import com.rsreach.security.auth.RsReachPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;
    private final CustomerUserRepository customerUserRepository;

    public CustomerAuthController(
            CustomerAuthService customerAuthService,
            CustomerUserRepository customerUserRepository
    ) {
        this.customerAuthService = customerAuthService;
        this.customerUserRepository = customerUserRepository;
    }

    @PostMapping("/auth/register")
    public CustomerAuthService.AuthTokens register(@Valid @RequestBody RegisterRequest request) {
        return customerAuthService.register(request.email(), request.displayName(), request.password());
    }

    @PostMapping("/auth/login")
    public CustomerAuthService.AuthTokens login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        return customerAuthService.login(
                request.email(),
                request.password(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr()
        );
    }

    @PostMapping("/auth/refresh")
    public CustomerAuthService.AuthTokens refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest
    ) {
        return customerAuthService.refresh(
                request.refreshToken(),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr()
        );
    }

    @PostMapping("/auth/logout")
    public Map<String, String> logout(@RequestBody RefreshRequest request) {
        customerAuthService.logout(request.refreshToken());
        return Map.of("status", "OK");
    }

    @PostMapping("/auth/password-reset/request")
    public Map<String, String> requestReset(@Valid @RequestBody EmailRequest request) {
        return customerAuthService.requestPasswordReset(request.email());
    }

    @PostMapping("/auth/password-reset/confirm")
    public Map<String, String> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        customerAuthService.confirmPasswordReset(request.token(), request.newPassword());
        return Map.of("status", "OK");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal RsReachPrincipal principal) {
        var user = customerUserRepository.findById(principal.getId()).orElseThrow();
        return Map.of(
                "id", user.id(),
                "email", user.email(),
                "displayName", user.displayName(),
                "permissions", principal.getPermissions()
        );
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank String displayName,
            @NotBlank @Size(min = 8) String password
    ) {
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record EmailRequest(@Email @NotBlank String email) {
    }

    public record PasswordResetConfirmRequest(@NotBlank String token, @NotBlank @Size(min = 8) String newPassword) {
    }
}
