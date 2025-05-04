package me.martingeltl.bitcoin.api;

import me.martingeltl.bitcoin.BitcoinPrice;
import org.bukkit.Bukkit;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * Service für die Kommunikation mit der CoinGecko API
 */
public class CoinGeckoService {

    private final BitcoinPrice plugin;
    
    public CoinGeckoService(BitcoinPrice plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Ruft den aktuellen Bitcoin-Preis asynchron ab
     * @return CompletableFuture mit dem Bitcoin-Preis als JSONObject
     */
    public CompletableFuture<JSONObject> fetchBitcoinPrice() {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL(plugin.getConfigManager().getApiUrl());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "BitcoinPrice-Minecraft-Plugin/1.1");
                connection.setConnectTimeout(plugin.getConfigManager().getApiTimeout());
                connection.setReadTimeout(plugin.getConfigManager().getApiTimeout());
                
                int responseCode = connection.getResponseCode();
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        
                        try {
                            JSONObject jsonResponse = new JSONObject(response.toString());
                            future.complete(jsonResponse);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Fehler beim Parsen der JSON-Antwort: " + e.getMessage());
                            plugin.getLogger().warning("Erhaltene Antwort: " + response.toString());
                            future.completeExceptionally(new IOException("JSON-Parsing-Fehler: " + e.getMessage()));
                        }
                    }
                } else if (responseCode == 429) {
                    plugin.getLogger().warning("Fehler beim Abrufen des Bitcoin-Preises: Rate Limit erreicht (429)");
                    future.completeExceptionally(new IOException("Rate Limit erreicht. Bitte versuche es später erneut."));
                } else {
                    plugin.getLogger().warning("Fehler beim Abrufen des Bitcoin-Preises. Statuscode: " + responseCode);
                    future.completeExceptionally(new IOException("HTTP-Fehler: " + responseCode));
                }
                
                connection.disconnect();
                
            } catch (IOException e) {
                plugin.getLogger().warning("Fehler beim Abrufen des Bitcoin-Preises: " + e.getMessage());
                future.completeExceptionally(e);
            } catch (Exception e) {
                plugin.getLogger().warning("Unerwarteter Fehler beim Abrufen des Bitcoin-Preises: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Extrahiert den Bitcoin-Preis in der angegebenen Währung aus der JSON-Antwort
     * @param response Die JSON-Antwort von der CoinGecko API
     * @param currency Die Währung (eur oder usd)
     * @return Der Bitcoin-Preis als String, formatiert mit 2 Nachkommastellen
     */
    public String extractPrice(JSONObject response, String currency) {
        try {
            if (response.has("bitcoin")) {
                JSONObject bitcoin = response.getJSONObject("bitcoin");
                
                if (bitcoin.has(currency.toLowerCase())) {
                    double price = bitcoin.getDouble(currency.toLowerCase());
                    return String.format("%,.2f", price);
                }
            }
            
            return "N/A";
        } catch (Exception e) {
            plugin.getLogger().warning("Fehler beim Extrahieren des Bitcoin-Preises: " + e.getMessage());
            return "N/A";
        }
    }
} 