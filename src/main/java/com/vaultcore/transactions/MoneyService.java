package com.vaultcore.transactions;

import com.vaultcore.common.ApiException;
import com.vaultcore.security.Actor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MoneyService {
    public static final UUID CASH_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MoneyService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(10);
    }

    public PostingResult execute(Operation operation, UUID source, UUID destination, BigDecimal amount,
            String currency, String key, Actor actor) {
        BigDecimal value = Money.normalize(amount, currency);
        if (source == null || destination == null || source.equals(destination)) {
            throw ApiException.badRequest("Source and destination must be different accounts");
        }
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.badRequest("Idempotency-Key must contain 1..128 letters, digits, dots, colons, underscores or hyphens");
        }
        if (operation != Operation.TRANSFER) { actor.requireCashAccess(); }
        if (operation == Operation.TRANSFER && (CASH_ACCOUNT.equals(source) || CASH_ACCOUNT.equals(destination))) {
            throw ApiException.badRequest("Transfers require two customer accounts");
        }
        String fingerprint = hash(operation + "|" + source + "|" + destination + "|" + value.toPlainString() + "|" + currency);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                // Every attempt enters a new database transaction, including the eventual commit.
                return transactions.execute(status -> post(operation, source, destination, value, key, fingerprint, actor));
            } catch (TransientDataAccessException exception) {
                if (attempt == 2) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RETRY_LATER",
                            "Transaction is busy; retry with the same idempotency key");
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private PostingResult post(Operation operation, UUID source, UUID destination, BigDecimal amount,
            String key, String fingerprint, Actor actor) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        int claimed = jdbc.update("""
                INSERT INTO idempotency_records (actor_subject, operation, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, actor.subject(), operation.name(), key, fingerprint);
        if (claimed == 0) {
            var existing = jdbc.queryForMap("""
                    SELECT request_hash, transaction_id FROM idempotency_records
                    WHERE actor_subject = ? AND operation = ? AND idempotency_key = ?
                    """, actor.subject(), operation.name(), key);
            if (!fingerprint.equals(existing.get("request_hash"))) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "This key was used with different request details");
            }
            return new PostingResult(find((UUID) existing.get("transaction_id")), true);
        }

        // Lock rows in SQL UUID order, independent of transfer direction and Java UUID ordering.
        List<LockedAccount> locked = jdbc.query("""
                SELECT id, owner_subject, balance, currency, account_type, status FROM accounts
                WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """, (row, index) -> new LockedAccount(row.getObject("id", UUID.class),
                        row.getString("owner_subject"), row.getBigDecimal("balance"), row.getString("currency"),
                        row.getString("account_type"), row.getString("status")), source, destination);
        if (locked.size() != 2) { throw ApiException.notFound(); }
        var accounts = new HashMap<UUID, LockedAccount>();
        locked.forEach(account -> accounts.put(account.id(), account));
        LockedAccount from = accounts.get(source);
        LockedAccount to = accounts.get(destination);
        if (operation == Operation.TRANSFER && !actor.subject().equals(from.owner()) && !actor.hasRole("ADMIN")) {
            throw ApiException.forbidden();
        }
        for (LockedAccount account : locked) {
            if (!"ACTIVE".equals(account.status()) || !Money.CURRENCY.equals(account.currency())) {
                throw ApiException.conflict("ACCOUNT_UNAVAILABLE", "Account must be active and use UGX");
            }
        }
        if ("CUSTOMER".equals(from.type()) && from.balance().compareTo(amount) < 0) {
            throw ApiException.conflict("INSUFFICIENT_FUNDS", "Insufficient funds");
        }
        if (from.balance().subtract(amount).abs().compareTo(Money.MAX) > 0
                || to.balance().add(amount).abs().compareTo(Money.MAX) > 0) {
            throw ApiException.conflict("BALANCE_LIMIT", "Account balance limit exceeded");
        }

        UUID transactionId = UUID.randomUUID();
        jdbc.update("UPDATE accounts SET balance = balance - ?, version = version + 1 WHERE id = ?", amount, source);
        jdbc.update("UPDATE accounts SET balance = balance + ?, version = version + 1 WHERE id = ?", amount, destination);
        jdbc.update("""
                INSERT INTO bank_transactions
                (id, operation, source_account_id, destination_account_id, amount, currency, actor_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, transactionId, operation.name(), source, destination, amount, Money.CURRENCY, actor.subject());
        jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, amount) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), transactionId, source, amount.negate());
        jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, amount) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), transactionId, destination, amount);
        jdbc.update("""
                UPDATE idempotency_records SET transaction_id = ?
                WHERE actor_subject = ? AND operation = ? AND idempotency_key = ?
                """, transactionId, actor.subject(), operation.name(), key);
        // Read back the persisted timestamp/scale so first responses and replays are identical.
        return new PostingResult(find(transactionId), false);
    }

    private TransactionView find(UUID id) {
        return jdbc.queryForObject("SELECT * FROM bank_transactions WHERE id = ?", TransactionView::map, id);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Operation { DEPOSIT, WITHDRAWAL, TRANSFER }
    public record PostingResult(TransactionView transaction, boolean replayed) { }
    private record LockedAccount(UUID id, String owner, BigDecimal balance, String currency, String type, String status) { }
}
