package com.vaultcore.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.vaultcore.common.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test void normalizesWithoutLosingPrecision() {
        assertThat(Money.normalize(new BigDecimal("100.1"), "UGX").toPlainString()).isEqualTo("100.10");
        assertThat(Money.normalize(new BigDecimal("99999999999999999.99"), "UGX")).isEqualByComparingTo(Money.MAX);
    }

    @Test void rejectsRoundingOverflowNonpositiveAndWrongCurrency() {
        for (String value : new String[]{"0", "-1", "0.001", "100000000000000000.00"}) {
            assertThatThrownBy(() -> Money.normalize(new BigDecimal(value), "UGX")).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> Money.normalize(BigDecimal.ONE, "USD")).isInstanceOf(ApiException.class);
    }
}
