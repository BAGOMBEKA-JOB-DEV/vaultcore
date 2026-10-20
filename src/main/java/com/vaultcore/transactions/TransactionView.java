package com.vaultcore.transactions;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public record TransactionView(UUID id, String operation, UUID sourceAccountId,
        UUID destinationAccountId, BigDecimal amount, String currency, Instant createdAt) {
    static TransactionView map(ResultSet row, int rowNumber) throws SQLException {
        return new TransactionView(row.getObject("id", UUID.class), row.getString("operation"),
                row.getObject("source_account_id", UUID.class), row.getObject("destination_account_id", UUID.class),
                row.getBigDecimal("amount"), row.getString("currency"), row.getTimestamp("created_at").toInstant());
    }
}
