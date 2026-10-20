package com.vaultcore.accounts;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Page<Account> findByAccountTypeAndOwnerSubject(String accountType, String ownerSubject, Pageable pageable);
    Page<Account> findByAccountType(String accountType, Pageable pageable);
}
