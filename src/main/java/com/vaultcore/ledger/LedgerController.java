package com.vaultcore.ledger;

import com.vaultcore.security.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LedgerController {
    private final LedgerService ledger;
    public LedgerController(LedgerService ledger) { this.ledger = ledger; }

    @GetMapping("/api/v1/ledger/reconciliation")
    LedgerService.Reconciliation reconcile(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ledger.reconcile(Actor.from(authentication), page, size);
    }
}
