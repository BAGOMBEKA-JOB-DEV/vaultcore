package com.vaultcore.transactions;

import com.vaultcore.common.PageResult;
import com.vaultcore.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {
    private final MoneyService money;
    private final TransactionQueries queries;
    public TransactionController(MoneyService money, TransactionQueries queries) {
        this.money = money;
        this.queries = queries;
    }

    @PostMapping("/accounts/{id}/deposits")
    ResponseEntity<TransactionView> deposit(@PathVariable UUID id, @Valid @RequestBody CashRequest request,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return response(money.execute(MoneyService.Operation.DEPOSIT, MoneyService.CASH_ACCOUNT, id,
                request.amount(), request.currency(), key, Actor.from(authentication)));
    }

    @PostMapping("/accounts/{id}/withdrawals")
    ResponseEntity<TransactionView> withdraw(@PathVariable UUID id, @Valid @RequestBody CashRequest request,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return response(money.execute(MoneyService.Operation.WITHDRAWAL, id, MoneyService.CASH_ACCOUNT,
                request.amount(), request.currency(), key, Actor.from(authentication)));
    }

    @PostMapping("/transfers")
    ResponseEntity<TransactionView> transfer(@Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return response(money.execute(MoneyService.Operation.TRANSFER, request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.currency(), key, Actor.from(authentication)));
    }

    @GetMapping("/transactions/{id}")
    TransactionView get(@PathVariable UUID id, Authentication authentication) {
        return queries.get(id, Actor.from(authentication));
    }

    @GetMapping("/accounts/{id}/transactions")
    PageResult<TransactionView> history(@PathVariable UUID id, Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.history(id, Actor.from(authentication), page, size);
    }

    private ResponseEntity<TransactionView> response(MoneyService.PostingResult result) {
        return ResponseEntity.created(URI.create("/api/v1/transactions/" + result.transaction().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.transaction());
    }

    public record CashRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "UGX") String currency) { }
    public record TransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "UGX") String currency) { }
}
