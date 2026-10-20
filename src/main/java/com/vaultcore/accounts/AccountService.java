package com.vaultcore.accounts;

import com.vaultcore.common.ApiException;
import com.vaultcore.common.PageResult;
import com.vaultcore.security.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository accounts;
    public AccountService(AccountRepository accounts) { this.accounts = accounts; }

    @Transactional
    public AccountView create(Actor actor) {
        return AccountView.from(accounts.save(new Account(actor.subject())));
    }

    public AccountView get(UUID id, Actor actor) {
        Account account = accounts.findById(id).filter(value -> "CUSTOMER".equals(value.getAccountType()))
                .orElseThrow(ApiException::notFound);
        if (!actor.subject().equals(account.getOwnerSubject()) && !actor.canReadAll()) {
            throw ApiException.forbidden();
        }
        return AccountView.from(account);
    }

    public PageResult<AccountView> list(Actor actor, int page, int size) {
        PageResult.validate(page, size);
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").and(Sort.by("id")));
        Page<Account> result = actor.canReadAll()
                ? accounts.findByAccountType("CUSTOMER", pageable)
                : accounts.findByAccountTypeAndOwnerSubject("CUSTOMER", actor.subject(), pageable);
        return new PageResult<>(result.map(AccountView::from).getContent(), page, size, result.getTotalElements());
    }

    public record AccountView(UUID id, String ownerSubject, String currency, BigDecimal balance,
            String status, Instant createdAt) {
        static AccountView from(Account account) {
            return new AccountView(account.getId(), account.getOwnerSubject(), account.getCurrency(),
                    account.getBalance(), account.getStatus(), account.getCreatedAt());
        }
    }
}
