package com.vaultcore.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Balance projection updated atomically with ledger postings by the transaction service. */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "account_type", nullable = false, length = 16)
    private String accountType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Account() {
    }

    public Account(String ownerSubject) {
        this.id = UUID.randomUUID();
        this.ownerSubject = ownerSubject;
        this.currency = "UGX";
        this.balance = new BigDecimal("0.00");
        this.status = "ACTIVE";
        this.accountType = "CUSTOMER";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getStatus() { return status; }
    public String getAccountType() { return accountType; }
    public Instant getCreatedAt() { return createdAt; }
}
