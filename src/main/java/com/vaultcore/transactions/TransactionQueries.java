package com.vaultcore.transactions;

import com.vaultcore.accounts.AccountService;
import com.vaultcore.common.ApiException;
import com.vaultcore.common.PageResult;
import com.vaultcore.security.Actor;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TransactionQueries {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    public TransactionQueries(JdbcTemplate jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public TransactionView get(UUID id, Actor actor) {
        var rows = jdbc.query("SELECT * FROM bank_transactions WHERE id = ?", TransactionView::map, id);
        if (rows.isEmpty()) { throw ApiException.notFound(); }
        TransactionView transaction = rows.getFirst();
        if (!actor.canReadAll()) {
            Long owned = jdbc.queryForObject("""
                    SELECT count(*) FROM accounts WHERE id IN (?, ?) AND account_type = 'CUSTOMER' AND owner_subject = ?
                    """, Long.class, transaction.sourceAccountId(), transaction.destinationAccountId(), actor.subject());
            if (owned == null || owned == 0) { throw ApiException.forbidden(); }
        }
        return transaction;
    }

    public PageResult<TransactionView> history(UUID accountId, Actor actor, int page, int size) {
        PageResult.validate(page, size);
        accounts.get(accountId, actor);
        var entries = jdbc.query("""
                SELECT * FROM bank_transactions WHERE source_account_id = ? OR destination_account_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, TransactionView::map, accountId, accountId, size, (long) page * size);
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM bank_transactions WHERE source_account_id = ? OR destination_account_id = ?
                """, Long.class, accountId, accountId);
        return new PageResult<>(entries, page, size, count == null ? 0 : count);
    }
}
