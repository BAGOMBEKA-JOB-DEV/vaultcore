package com.vaultcore.accounts;

import com.vaultcore.common.PageResult;
import com.vaultcore.security.Actor;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping
    ResponseEntity<AccountService.AccountView> create(Authentication authentication) {
        var account = accounts.create(Actor.from(authentication));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/{id}")
    AccountService.AccountView get(@PathVariable UUID id, Authentication authentication) {
        return accounts.get(id, Actor.from(authentication));
    }

    @GetMapping
    PageResult<AccountService.AccountView> list(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return accounts.list(Actor.from(authentication), page, size);
    }
}
