package org.bitcoinprice.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiSettingsTest {
    @Test void migratesExistingUrlFlagsAndDoesNotExposeKey() {
        ApiSettings settings = new ApiSettings("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&include_24hr_change=false", 1000, 60, 900, 600, 60, "secret-key");
        assertTrue(settings.url().contains("include_24hr_change=true"));
        assertTrue(settings.url().contains("include_last_updated_at=true"));
        assertTrue(settings.url().contains("vs_currencies=eur,usd,gbp,chf,cad,aud,jpy,cny,inr"));
        assertFalse(settings.toString().contains("secret-key"));
        assertFalse(settings.toString().contains("ids=bitcoin"));
    }
    @Test void upgradesExistingCurrencyListAndPreservesOtherParameters() {
        ApiSettings settings = new ApiSettings("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur,usd&precision=2", 1000, 60, 900, 600, 60, "");
        assertTrue(settings.url().contains("vs_currencies=eur,usd,gbp,chf,cad,aud,jpy,cny,inr"));
        assertTrue(settings.url().contains("ids=bitcoin"));
        assertTrue(settings.url().contains("precision=2"));
    }
    @Test void rejectsCredentialsUnsafeSchemesInvalidTimingsAndHeaderInjection() {
        for (String url : new String[]{"file:///tmp/foo", "https://user:pass@host", "https://host/#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> new ApiSettings(url, 1000, 60, 900, 600, 60, ""));
        }
        assertThrows(IllegalArgumentException.class, () -> new ApiSettings("https://host", 0, 60, 900, 600, 60, ""));
        assertThrows(IllegalArgumentException.class, () -> new ApiSettings("https://host", 1000, 60, 30, 600, 60, ""));
        assertThrows(IllegalArgumentException.class, () -> new ApiSettings("https://host", 1000, 60, 900, 600, 60, "key\nInjected: value"));
    }
}
