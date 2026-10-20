package com.vaultcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vaultcore.security.Actor;
import com.vaultcore.security.DevAuthentication;
import com.vaultcore.transactions.MoneyService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=dev", "debug=false", "logging.level.root=WARN",
        "logging.level.org.springframework=WARN", "logging.structured.format.console="})
class DatabaseIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String external = System.getenv("VAULTCORE_TEST_DB_URL");
        if (external != null) {
            properties.add("spring.datasource.url", () -> external);
            properties.add("spring.datasource.username", () -> System.getenv("VAULTCORE_TEST_DB_USER"));
            properties.add("spring.datasource.password", () -> System.getenv("VAULTCORE_TEST_DB_PASSWORD"));
        } else {
            postgres = new PostgreSQLContainer("postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24")
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(192L * 1024 * 1024));
            postgres.start();
            properties.add("spring.datasource.url", postgres::getJdbcUrl);
            properties.add("spring.datasource.username", postgres::getUsername);
            properties.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @AfterAll static void cleanup() {
        HTTP.close();
        if (postgres != null) { postgres.stop(); }
    }

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder encoder;
    @Autowired private PlatformTransactionManager manager;
    private String alice;
    private String bob;
    private String teller;
    private String auditor;

    @BeforeEach void reset() throws Exception {
        jdbc.execute("TRUNCATE ledger_entries, idempotency_records, bank_transactions, accounts CASCADE");
        jdbc.update("INSERT INTO accounts (id, owner_subject, currency, account_type) VALUES (?, 'SYSTEM:cash', 'UGX', 'CLEARING')",
                MoneyService.CASH_ACCOUNT);
        alice = token("alice", "CUSTOMER");
        bob = token("bob", "CUSTOMER");
        teller = token("teller", "TELLER");
        auditor = token("auditor", "AUDITOR");
    }

    @Test void migrationHealthAndAuthentication() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(2);
        assertThat(get("/actuator/health/liveness", null).status()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).status()).isEqualTo(200);
        assertThat(get("/api/v1/status", null).status()).isEqualTo(401);
        assertThat(get("/api/v1/status", "invalid").status()).isEqualTo(401);
        assertThat(get("/api/v1/status", alice).status()).isEqualTo(200);
        assertThat(get("/api/v1/unknown-route", alice).status()).isEqualTo(404);
        assertThat(send("/api/v1/status", alice, Map.of(), null).status()).isEqualTo(405);
    }

    @Test void ownershipPaginationAndCashPermissions() throws Exception {
        String id = account(alice);
        assertThat(get("/api/v1/accounts/" + id, bob).status()).isEqualTo(403);
        assertThat(get("/api/v1/accounts/" + id, teller).status()).isEqualTo(200);
        assertThat(get("/api/v1/accounts", bob).json().path("total").asLong()).isZero();
        assertThat(get("/api/v1/accounts?size=1", alice).json().path("items").size()).isEqualTo(1);
        assertThat(get("/api/v1/accounts?size=101", alice).status()).isEqualTo(400);
        assertThat(get("/api/v1/accounts?page=-1", alice).status()).isEqualTo(400);
        assertThat(cash(id, "deposits", "10", "customer", alice).status()).isEqualTo(403);
        assertThat(get("/api/v1/ledger/reconciliation", alice).status()).isEqualTo(403);
        assertThat(get("/api/v1/accounts/" + MoneyService.CASH_ACCOUNT, auditor).status()).isEqualTo(404);
    }

    @Test void moneyMovementAndHistoryReconcile() throws Exception {
        String from = fundedAccount(alice, "100");
        String to = account(bob);
        assertThat(cash(from, "withdrawals", "10", "cash-out", teller).status()).isEqualTo(201);
        Reply transfer = transfer(from, to, "25", "move", alice);
        assertThat(transfer.status()).isEqualTo(201);
        balance(from, "65.00");
        balance(to, "25.00");
        assertThat(get("/api/v1/accounts/" + from + "/transactions?size=2", alice).json().path("total").asLong()).isEqualTo(3);
        assertThat(get("/api/v1/accounts/" + from + "/transactions", bob).status()).isEqualTo(403);
        String path = "/api/v1/transactions/" + transfer.json().path("id").asText();
        assertThat(get(path, bob).status()).isEqualTo(200);
        assertThat(get(path, token("stranger", "CUSTOMER")).status()).isEqualTo(403);
        reconciled();
    }

    @Test void replaysCanonicalAmountsAndRejectsChangedPayload() throws Exception {
        String from = fundedAccount(alice, "100");
        String to = account(bob);
        Reply first = transfer(from, to, "10", "same", alice);
        Reply replay = transfer(from, to, "10.00", "same", alice);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.replayed()).isTrue();
        assertThat(transfer(from, to, "11", "same", alice).json().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        balance(from, "90.00");
        balance(to, "10.00");
        var other = new MoneyService(jdbc, manager).execute(MoneyService.Operation.TRANSFER,
                UUID.fromString(from), UUID.fromString(to), new BigDecimal("10"), "UGX", "same",
                new Actor("alice", Set.of("ROLE_CUSTOMER")));
        assertThat(other.replayed()).isTrue();
        assertThat(other.transaction().id().toString()).isEqualTo(first.json().path("id").asText());
    }

    @Test void keysAreScopedToActorAndOperation() throws Exception {
        String id = account(alice);
        assertThat(cash(id, "deposits", "20", "scoped", teller).status()).isEqualTo(201);
        assertThat(cash(id, "withdrawals", "1", "scoped", teller).status()).isEqualTo(201);
        assertThat(cash(id, "deposits", "20", "scoped", token("second-teller", "TELLER")).status()).isEqualTo(201);
        balance(id, "39.00");
    }

    @Test void concurrentDuplicateTransfersMoveMoneyOnce() throws Exception {
        String from = fundedAccount(alice, "100");
        String to = account(bob);
        List<Callable<Reply>> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) { work.add(() -> transfer(from, to, "20", "duplicate", alice)); }
        List<Reply> results = parallel(work);
        assertThat(results).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(201));
        assertThat(results.stream().map(reply -> reply.json().path("id").asText()).distinct().count()).isEqualTo(1);
        balance(from, "80.00");
        balance(to, "20.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE operation='TRANSFER'", Long.class)).isEqualTo(1);
        reconciled();
    }

    @Test void competingWithdrawalsCannotOverdraw() throws Exception {
        String id = fundedAccount(alice, "100");
        List<Callable<Reply>> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "withdraw-" + i;
            work.add(() -> cash(id, "withdrawals", "30", key, teller));
        }
        List<Reply> results = parallel(work);
        assertThat(results.stream().filter(reply -> reply.status() == 201).count()).isEqualTo(3);
        assertThat(results.stream().filter(reply -> reply.status() == 409).count()).isEqualTo(5);
        balance(id, "10.00");
        reconciled();
    }

    @Test void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        String first = fundedAccount(alice, "100");
        String second = fundedAccount(bob, "100");
        List<Callable<Reply>> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "opposing-" + i;
            work.add(() -> transfer(first, second, "1", key, alice));
            work.add(() -> transfer(second, first, "1", key, bob));
        }
        assertThat(parallel(work)).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(201));
        balance(first, "100.00");
        balance(second, "100.00");
        reconciled();
    }

    @Test void validatesAmountsHeadersIdentifiersAndOwnership() throws Exception {
        String from = fundedAccount(alice, "10");
        String to = account(bob);
        assertThat(transfer(from, to, "11", "too-much", alice).status()).isEqualTo(409);
        assertThat(transfer(from, to, "1", "steal", bob).status()).isEqualTo(403);
        assertThat(transfer(from, from, "1", "self", alice).status()).isEqualTo(400);
        for (String amount : List.of("0", "-1", "0.001", "100000000000000000.00")) {
            assertThat(cash(from, "deposits", amount, "bad-" + UUID.randomUUID(), teller).status()).isEqualTo(400);
        }
        String path = "/api/v1/accounts/" + from + "/deposits";
        assertThat(send(path, teller, Map.of("amount", "1", "currency", "USD"), "usd").status()).isEqualTo(400);
        assertThat(send(path, teller, Map.of("amount", "1", "currency", "UGX"), null).status()).isEqualTo(400);
        assertThat(cash(from, "deposits", "1", "contains space", teller).status()).isEqualTo(400);
        assertThat(get("/api/v1/accounts/not-a-uuid", alice).status()).isEqualTo(400);
        assertThat(cash(UUID.randomUUID().toString(), "deposits", "1", "missing", teller).status()).isEqualTo(404);
        balance(from, "10.00");
    }

    @Test void frozenAccountsRejectMovement() throws Exception {
        String id = fundedAccount(alice, "10");
        jdbc.update("UPDATE accounts SET status='FROZEN' WHERE id=?", UUID.fromString(id));
        assertThat(cash(id, "withdrawals", "1", "frozen", teller).status()).isEqualTo(409);
        balance(id, "10.00");
    }

    @Test void failureAfterDebitRollsBackBalancesAndKey() throws Exception {
        String from = fundedAccount(alice, "100");
        String to = account(bob);
        jdbc.execute("""
                CREATE FUNCTION test_fail_credit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = '%s'::uuid THEN RAISE EXCEPTION 'injected test failure'; END IF;
                    RETURN NEW;
                END; $$
                """.formatted(to));
        jdbc.execute("CREATE TRIGGER test_credit_failure BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION test_fail_credit()");
        try {
            Reply failed = transfer(from, to, "20", "recoverable", alice);
            assertThat(failed.status()).isEqualTo(500);
            assertThat(failed.body()).doesNotContain("injected test failure", "UPDATE accounts");
            balance(from, "100.00");
            balance(to, "0.00");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key='recoverable'", Long.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_credit_failure ON accounts");
            jdbc.execute("DROP FUNCTION test_fail_credit()");
        }
        assertThat(transfer(from, to, "20", "recoverable", alice).status()).isEqualTo(201);
        reconciled();
    }

    @Test void databaseRejectsUnbalancedAndMutablePostings() throws Exception {
        String id = fundedAccount(alice, "10");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount=1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bank_transactions")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance=-1 WHERE id=?", UUID.fromString(id)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO bank_transactions (id, operation, source_account_id, destination_account_id, amount, currency, actor_subject)
                VALUES (?, 'DEPOSIT', ?, ?, 1, 'UGX', 'test')
                """, UUID.randomUUID(), MoneyService.CASH_ACCOUNT, UUID.fromString(id))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO idempotency_records (actor_subject, operation, idempotency_key, request_hash)
                VALUES ('test', 'DEPOSIT', 'unfinished', 'hash')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        reconciled();
    }

    @Test void auditDetectsProjectionDrift() throws Exception {
        String id = fundedAccount(alice, "10");
        jdbc.update("UPDATE accounts SET balance=11 WHERE id=?", UUID.fromString(id));
        JsonNode report = get("/api/v1/ledger/reconciliation", auditor).json();
        assertThat(report.path("balanced").asBoolean()).isFalse();
        assertThat(report.path("mismatchedAccounts").asLong()).isEqualTo(1);
    }

    @Test void rejectsExpiredWrongIssuerAndWrongAudienceTokens() throws Exception {
        for (String variant : List.of("expired", "issuer", "audience")) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder().subject("alice")
                    .issuer(variant.equals("issuer") ? "http://wrong.local" : DevAuthentication.ISSUER)
                    .audience(List.of(variant.equals("audience") ? "another-api" : "vaultcore-api"))
                    .issuedAt(now.minusSeconds(7200))
                    .expiresAt(variant.equals("expired") ? now.minusSeconds(3600) : now.plusSeconds(600)).build();
            assertThat(get("/api/v1/status", encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue()).status()).isEqualTo(401);
        }
    }

    @Test void balanceLimitsRemainAtomic() throws Exception {
        String id = account(alice);
        assertThat(cash(id, "deposits", "99999999999999999.99", "maximum", teller).status()).isEqualTo(201);
        assertThat(cash(id, "deposits", "0.01", "overflow", teller).status()).isEqualTo(409);
        balance(id, "99999999999999999.99");
        reconciled();
    }

    private String token(String subject, String role) throws Exception {
        Reply reply = send("/dev/token", null, Map.of("subject", subject, "roles", List.of(role)), null);
        assertThat(reply.status()).isEqualTo(200);
        return reply.json().path("accessToken").asText();
    }

    private String account(String owner) throws Exception {
        Reply reply = send("/api/v1/accounts", owner, Map.of(), null);
        assertThat(reply.status()).isEqualTo(201);
        return reply.json().path("id").asText();
    }

    private String fundedAccount(String owner, String amount) throws Exception {
        String id = account(owner);
        assertThat(cash(id, "deposits", amount, UUID.randomUUID().toString(), teller).status()).isEqualTo(201);
        return id;
    }

    private Reply cash(String id, String action, String amount, String key, String token) throws Exception {
        return send("/api/v1/accounts/" + id + "/" + action, token, Map.of("amount", amount, "currency", "UGX"), key);
    }

    private Reply transfer(String from, String to, String amount, String key, String token) throws Exception {
        return send("/api/v1/transfers", token, Map.of("sourceAccountId", from, "destinationAccountId", to,
                "amount", amount, "currency", "UGX"), key);
    }

    private Reply get(String path, String token) throws Exception { return send(path, token, null, null); }

    private Reply send(String path, String token, Map<String, ?> body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (key != null) { request.header("Idempotency-Key", key); }
        if (body == null) { request.GET(); }
        else { request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))); }
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        return new Reply(response.statusCode(), response.body(), response.headers().firstValue("Idempotency-Replayed").orElse("false"));
    }

    private void balance(String id, String expected) {
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?", BigDecimal.class, UUID.fromString(id)))
                .isEqualByComparingTo(expected);
    }

    private void reconciled() throws Exception {
        Reply report = get("/api/v1/ledger/reconciliation", auditor);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.json().path("balanced").asBoolean()).isTrue();
        assertThat(report.json().path("ledgerNet").decimalValue()).isEqualByComparingTo("0");
    }

    private List<Reply> parallel(List<Callable<Reply>> work) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Reply> replies = new ArrayList<>();
            for (var future : pool.invokeAll(work)) { replies.add(future.get()); }
            return replies;
        }
    }

    private record Reply(int status, String body, String replayHeader) {
        JsonNode json() { return JSON.readTree(body); }
        boolean replayed() { return Boolean.parseBoolean(replayHeader); }
    }
}
