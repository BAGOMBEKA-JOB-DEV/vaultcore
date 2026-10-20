package com.vaultcore.ledger;

import com.vaultcore.common.PageResult;
import com.vaultcore.security.Actor;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    public LedgerService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Reconciliation reconcile(Actor actor, int page, int size) {
        actor.requireAuditAccess();
        PageResult.validate(page, size);
        var rows = jdbc.query("""
                SELECT a.id, a.balance, coalesce(sum(e.amount), 0) AS ledger_balance
                FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id ORDER BY a.id LIMIT ? OFFSET ?
                """, (row, number) -> new BalanceCheck(row.getObject("id", UUID.class), row.getBigDecimal("balance"),
                        row.getBigDecimal("ledger_balance"), row.getBigDecimal("balance").compareTo(row.getBigDecimal("ledger_balance")) == 0),
                size, (long) page * size);
        Long mismatches = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                    GROUP BY a.id HAVING a.balance <> coalesce(sum(e.amount), 0)
                ) mismatches
                """, Long.class);
        Long total = jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class);
        BigDecimal net = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entries", BigDecimal.class);
        return new Reconciliation(mismatches == 0 && net.signum() == 0, mismatches, net,
                new PageResult<>(rows, page, size, total));
    }

    public record BalanceCheck(UUID accountId, BigDecimal balance, BigDecimal ledgerBalance, boolean matches) { }
    public record Reconciliation(boolean balanced, long mismatchedAccounts, BigDecimal ledgerNet,
            PageResult<BalanceCheck> accounts) { }
}
