package org.bitcoinprice.presentation;

import org.bitcoinprice.api.CoinGeckoService.ServiceStatus;
import org.bitcoinprice.api.CoinGeckoService.FailureReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Only structured, credential-free provider state is displayed; reading it never requests prices. */
public final class ServiceDiagnostics {
    private ServiceDiagnostics() { }

    public static List<String> lines(ServiceStatus status, Language language) {
        List<String> lines = new ArrayList<>();
        lines.add(language.text("diagnostics.cache", language.text("diagnostics.cache." + status.cacheState().name().toLowerCase(Locale.ROOT))));
        String failure = language.text("diagnostics.failure." + status.lastFailure().name().toLowerCase(Locale.ROOT));
        if (status.httpStatus() != null) failure += " (HTTP " + status.httpStatus() + ")";
        lines.add(language.text("diagnostics.failure", failure));
        if (status.lastFailure() != FailureReason.NONE) {
            lines.add(language.text("diagnostics.retry", status.consecutiveFailures(), status.retryAfterSeconds()));
        }
        if (status.refreshCooldownSeconds() > 0)
            lines.add(language.text("diagnostics.refresh", status.refreshCooldownSeconds()));
        return List.copyOf(lines);
    }
}
