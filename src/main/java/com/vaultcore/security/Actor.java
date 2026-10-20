package com.vaultcore.security;

import com.vaultcore.common.ApiException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public record Actor(String subject, Set<String> authorities) {
    public static Actor from(Authentication authentication) {
        return new Actor(authentication.getName(), authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet()));
    }

    public boolean hasRole(String role) {
        return authorities.contains("ROLE_" + role);
    }

    public boolean canReadAll() {
        return hasRole("ADMIN") || hasRole("AUDITOR") || hasRole("TELLER");
    }

    public void requireCashAccess() {
        if (!hasRole("TELLER") && !hasRole("ADMIN")) { throw ApiException.forbidden(); }
    }

    public void requireAuditAccess() {
        if (!hasRole("AUDITOR") && !hasRole("ADMIN")) { throw ApiException.forbidden(); }
    }
}
