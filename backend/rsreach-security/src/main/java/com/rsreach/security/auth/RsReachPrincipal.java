package com.rsreach.security.auth;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class RsReachPrincipal implements UserDetails {

    private final UUID id;
    private final String realm;
    private final String email;
    private final Set<String> permissions;
    private final boolean mfaVerified;

    public RsReachPrincipal(UUID id, String realm, String email, Set<String> permissions, boolean mfaVerified) {
        this.id = id;
        this.realm = realm;
        this.email = email;
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        this.mfaVerified = mfaVerified;
    }

    public UUID getId() {
        return id;
    }

    public String getRealm() {
        return realm;
    }

    public String getEmail() {
        return email;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public boolean isMfaVerified() {
        return mfaVerified;
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
