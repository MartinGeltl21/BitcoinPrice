package org.bitcoinprice.boards;

import org.bitcoinprice.presentation.MessageException;

import org.bitcoinprice.BitcoinPrice;
import org.bitcoinprice.model.PriceQuote;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent named displays. Never forces chunk loading or removes untagged entities. Main thread only. */
public final class BoardManager implements Listener, AutoCloseable {
    private final BitcoinPrice plugin;
    private final NamespacedKey key;
    private final Path file;
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, Board> removed = new LinkedHashMap<>();
    private boolean writable = true, closed;
    private static final class Board {
        final String name; final UUID world; final double x, y, z; UUID entity; boolean creating;
        Board(String name, UUID world, double x, double y, double z, UUID entity) {
            this.name = name; this.world = world; this.x = x; this.y = y; this.z = z; this.entity = entity;
        }
        int chunkX() { return ((int) Math.floor(x)) >> 4; }
        int chunkZ() { return ((int) Math.floor(z)) >> 4; }
        boolean in(Chunk chunk) { return chunk.getWorld().getUID().equals(world) && chunk.getX() == chunkX() && chunk.getZ() == chunkZ(); }
    }
    public BoardManager(BitcoinPrice plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "price_board");
        this.file = plugin.getDataFolder().toPath().resolve("boards.yml");
    }
    public void load() {
        boards.clear(); removed.clear();
        if (Files.exists(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
                if (yaml.getInt("version", -1) != 1) throw new IllegalArgumentException("Unbekannte boards.yml-Version");
                for (String section : List.of("boards", "removed")) {
                    if (yaml.contains(section) && !yaml.isConfigurationSection(section))
                        throw new IllegalArgumentException("Ungültiger Kurstafelabschnitt");
                }
                read(yaml.getConfigurationSection("boards"), boards);
                read(yaml.getConfigurationSection("removed"), removed);
                if (boards.size() + removed.size() > 1000) throw new IllegalArgumentException("Zu viele Kurstafeln");
            } catch (Exception ex) {
                writable = false; boards.clear(); removed.clear();
                plugin.getLogger().warning("boards.yml ist ungültig und bleibt unverändert. Kurstafeländerungen deaktiviert: " + ex.getClass().getSimpleName());
                return;
            }
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) reconcile(chunk);
        }
    }
    private void read(ConfigurationSection section, Map<String, Board> destination) {
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            validateName(name);
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) throw new IllegalArgumentException("Ungültiger Tafeleintrag");
            UUID world = UUID.fromString(entry.getString("world", ""));
            double x = entry.getDouble("x", Double.NaN), y = entry.getDouble("y", Double.NaN), z = entry.getDouble("z", Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 4096)
                throw new IllegalArgumentException("Ungültige Tafelposition");
            String entity = entry.getString("entity");
            destination.put(name, new Board(name, world, x, y, z, entity == null ? null : UUID.fromString(entity)));
        }
    }
    private static String validateName(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) throw new MessageException("error.board.name");
        return normalized;
    }
    public void create(Player player, String rawName) {
        checkWritable(); String name = validateName(rawName);
        if (boards.containsKey(name)) throw new MessageException("error.board.exists");
        if (boards.size() >= 100) throw new MessageException("error.board.limit");
        Location location = player.getLocation().clone().add(0, 2, 0);
        World world = location.getWorld();
        Board board = new Board(name, location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), null);
        if (!world.isChunkLoaded(board.chunkX(), board.chunkZ()) || !world.getChunkAt(board.chunkX(), board.chunkZ()).isEntitiesLoaded())
            throw new MessageException("error.board.chunk");
        Board previousRemoval = removed.remove(name);
        boards.put(name, board);
        TextDisplay display = null;
        try { display = spawn(board, world); save(); }
        catch (RuntimeException ex) {
            boards.remove(name); if (previousRemoval != null) removed.put(name, previousRemoval);
            if (display != null) display.remove(); throw ex;
        }
        plugin.getApiService().cachedQuote().ifPresent(this::update);
    }
    public boolean remove(String rawName) {
        checkWritable(); String name = validateName(rawName); Board board = boards.remove(name);
        if (board == null) return false;
        removed.put(name, board);
        try { save(); }
        catch (IllegalArgumentException ex) { removed.remove(name); boards.put(name, board); throw ex; }
        reconcileIfLoaded(board); return true;
    }
    public List<String> names() { return boards.keySet().stream().sorted().toList(); }
    public boolean hasActiveBoards() {
        if (closed) return false;
        return boards.values().stream().anyMatch(board -> {
            World world = plugin.getServer().getWorld(board.world);
            return world != null && world.isChunkLoaded(board.chunkX(), board.chunkZ());
        });
    }
    private TextDisplay spawn(Board board, World world) {
        board.creating = true;
        try {
            TextDisplay display = world.spawn(new Location(world, board.x, board.y, board.z), TextDisplay.class, entity -> {
                entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, board.name);
                entity.setPersistent(true); entity.setInvulnerable(true); entity.setGravity(false);
                entity.setBillboard(Display.Billboard.CENTER); entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setLineWidth(300); entity.setViewRange(0.5f); entity.setShadowed(true);
                entity.text(Component.text(plugin.getConfigManager().getLanguage().text("board.waiting")));
            });
            board.entity = display.getUniqueId(); return display;
        } finally { board.creating = false; }
    }
    private void reconcileIfLoaded(Board board) {
        World world = plugin.getServer().getWorld(board.world);
        if (world != null && world.isChunkLoaded(board.chunkX(), board.chunkZ())) reconcile(world.getChunkAt(board.chunkX(), board.chunkZ()));
    }
    private void reconcile(Chunk chunk) {
        if (closed || !writable || !chunk.isLoaded() || !chunk.isEntitiesLoaded()) return;
        boolean changed = false;
        for (Board board : List.copyOf(removed.values())) {
            if (!board.in(chunk)) continue;
            for (Entity entity : chunk.getEntities()) if (owned(entity, board.name)) entity.remove();
            // Orphan cleanup below also covers entities loaded after ChunkLoadEvent or after a crash.
            removed.remove(board.name); changed = true;
        }
        // Only plugin-tagged displays are candidates. Remove moved/orphaned managed entities in loaded chunks.
        for (Entity entity : chunk.getEntities()) {
            String name = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (name == null || !(entity instanceof TextDisplay)) continue;
            Board owner = boards.get(name);
            if (owner == null || !owner.in(chunk)) entity.remove();
        }
        for (Board board : boards.values()) {
            if (!board.in(chunk) || board.creating) continue;
            TextDisplay retained = null;
            Entity preferred = board.entity == null ? null : plugin.getServer().getEntity(board.entity);
            if (preferred instanceof TextDisplay display && owned(display, board.name)
                    && display.getWorld().getUID().equals(board.world)
                    && (((int) Math.floor(display.getLocation().getX())) >> 4) == chunk.getX()
                    && (((int) Math.floor(display.getLocation().getZ())) >> 4) == chunk.getZ()) retained = display;
            for (Entity entity : chunk.getEntities()) if (owned(entity, board.name) && entity instanceof TextDisplay display) {
                if (retained == null) retained = display; else if (!retained.getUniqueId().equals(display.getUniqueId())) display.remove();
            }
            if (retained == null) { retained = spawn(board, chunk.getWorld()); changed = true; }
            else if (!retained.getUniqueId().equals(board.entity)) { board.entity = retained.getUniqueId(); changed = true; }
            Location desired = new Location(chunk.getWorld(), board.x, board.y, board.z);
            if (retained.getLocation().distanceSquared(desired) > 0.001) retained.teleport(desired);
            if (plugin.getApiService().cachedQuote().isEmpty()) retained.text(unavailableText());
        }
        if (changed) saveSafely();
        plugin.getApiService().cachedQuote().ifPresent(this::update);
    }
    private boolean owned(Entity entity, String name) {
        return entity instanceof TextDisplay && name.equals(entity.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }
    public void update(PriceQuote quote) {
        if (closed || !writable) return;
        Component text = plugin.getMessages().quote("board", quote, plugin.getConfigManager().getPriceCurrency(), plugin.getConfigManager().getLocale());
        updateText(text);
    }
    private Component unavailableText() { return Component.text(plugin.getConfigManager().getLanguage().text("board.unavailable")); }
    public void showUnavailable() {
        if (!closed && writable) updateText(unavailableText());
    }
    private void updateText(Component text) {
        for (Board board : boards.values()) {
            World world = plugin.getServer().getWorld(board.world);
            if (world == null || !world.isChunkLoaded(board.chunkX(), board.chunkZ())) continue;
            Entity entity = board.entity == null ? null : plugin.getServer().getEntity(board.entity);
            if (entity instanceof TextDisplay display && owned(display, board.name) && display.isValid()) display.text(text);
        }
    }
    @EventHandler public void onChunkLoad(ChunkLoadEvent event) { reconcile(event.getChunk()); }
    @EventHandler public void onEntitiesLoad(EntitiesLoadEvent event) { reconcile(event.getChunk()); }
    @EventHandler public void onWorldLoad(WorldLoadEvent event) {
        for (Chunk chunk : event.getWorld().getLoadedChunks()) reconcile(chunk);
    }
    private void checkWritable() {
        if (closed || !writable) throw new MessageException("error.board.writable");
    }
    private void save() {
        if (!writable) return;
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("version", 1);
        write(yaml, "boards", boards); write(yaml, "removed", removed);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("boards.yml.tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { throw new MessageException("error.board.save", ex); }
    }
    private static void write(YamlConfiguration yaml, String prefix, Map<String, Board> source) {
        for (Board board : source.values()) {
            String base = prefix + "." + board.name + ".";
            yaml.set(base + "world", board.world.toString()); yaml.set(base + "x", board.x); yaml.set(base + "y", board.y); yaml.set(base + "z", board.z);
            yaml.set(base + "entity", board.entity == null ? null : board.entity.toString());
        }
    }
    private void saveSafely() {
        try { save(); } catch (IllegalArgumentException ex) { plugin.getLogger().warning(ex.getMessage()); }
    }
    @Override public void close() { closed = true; saveSafely(); }
}
