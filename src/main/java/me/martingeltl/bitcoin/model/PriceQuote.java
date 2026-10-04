package me.martingeltl.bitcoin.model;

import java.util.Objects;

public record PriceQuote(PriceSnapshot snapshot, boolean stale) {
    public PriceQuote { Objects.requireNonNull(snapshot, "snapshot"); }
}
