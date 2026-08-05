package com.teknologiia.dmarc.security;

import com.teknologiia.dmarc.model.User;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal, carrying the tenant it belongs to.
 *
 * <p>Services scope every query by {@link #getOrganizationId()}. Holding it on the
 * principal means the boundary is decided once, at authentication, instead of being
 * re-derived — and forgotten — at each call site.
 */
@Getter
public class AuthenticatedUser implements UserDetails {

    private final Long userId;
    private final Long organizationId;
    private final String username;
    private final String password;
    private final boolean enabled;
    private final Collection<? extends org.springframework.security.core.GrantedAuthority> authorities;

    public AuthenticatedUser(User user) {
        this.userId = user.getId();
        this.organizationId = user.getOrganization().getId();
        this.username = user.getUsername();
        this.password = user.getHashedPassword();
        // An account awaiting email verification is stored as inactive, which makes
        // Spring Security refuse the login rather than each endpoint having to check.
        this.enabled = user.isActive();
        this.authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase()));
    }

    @Override public Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override public String getPassword() {
        return password;
    }

    @Override public String getUsername() {
        return username;
    }

    @Override public boolean isAccountNonExpired() {
        return true;
    }

    @Override public boolean isAccountNonLocked() {
        return true;
    }

    @Override public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override public boolean isEnabled() {
        return enabled;
    }
}
