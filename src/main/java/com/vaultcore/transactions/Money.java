package com.vaultcore.transactions;

import com.vaultcore.common.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final String CURRENCY = "UGX";
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");
    private Money() { }

    public static BigDecimal normalize(BigDecimal amount, String currency) {
        if (!CURRENCY.equals(currency)) { throw ApiException.badRequest("Only UGX is supported"); }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX) > 0) {
            throw ApiException.badRequest("Amount must be positive and fit NUMERIC(19,2)");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw ApiException.badRequest("Amount must have at most two decimal places");
        }
    }
}
