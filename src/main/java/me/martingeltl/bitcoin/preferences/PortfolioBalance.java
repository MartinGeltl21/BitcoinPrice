package me.martingeltl.bitcoin.preferences;

import java.math.BigDecimal;
import java.util.Objects;

/** Virtual balances only; no real currency, accounts or transfers. */
public record PortfolioBalance(BigDecimal cashEur, BigDecimal bitcoin) {
    public PortfolioBalance {
        Objects.requireNonNull(cashEur, "cashEur");
        Objects.requireNonNull(bitcoin, "bitcoin");
        if (cashEur.signum() < 0 || bitcoin.signum() < 0
                || cashEur.stripTrailingZeros().scale() > 2 || bitcoin.stripTrailingZeros().scale() > 8) {
            throw new IllegalArgumentException("Invalid portfolio balances");
        }
    }
}
