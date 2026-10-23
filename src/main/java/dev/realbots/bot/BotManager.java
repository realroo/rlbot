package dev.realbots.bot;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.realbots.RealBotsPlugin;
import dev.realbots.nms.NmsBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class BotManager {

    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final RealBotsPlugin plugin;
    private final NmsBridge nms;
    private final Map<String, Bot> bots = new LinkedHashMap<>();          // lower-case name -> bot
    private final Map<UUID, Bot> byUuid = new ConcurrentHashMap<>();
    private final Map<String, Optional<ProfileProperty>> skinCache = new ConcurrentHashMap<>();
    private final File dataFile;
    private BukkitTask ticker;
    private long tickCount = 0;
    private boolean engineFailed = false;

    public BotManager(RealBotsPlugin plugin, NmsBridge nms) {
        this.plugin = plugin;
        this.nms = nms;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------------ lookup

    public List<Bot> all() {
        return new ArrayList<>(bots.values());
    }

    public Bot get(String name) {
        return bots.get(name.toLowerCase());
    }

    public boolean isBot(Player p) {
        return byUuid.containsKey(p.getUniqueId());
    }

    public Bot byPlayer(Player p) {
        return byUuid.get(p.getUniqueId());
    }

    public int count() {
        return bots.size();
    }

    // ------------------------------------------------------------------ spawn / remove

    /**
     * Spawns a bot. Skin lookup happens async, the actual spawn on the main thread.
     * {@code done} receives null on success, or an error message key + detail.
     */
    public void spawn(String name, String skinName, Location where, Consumer<String[]> done) {
        if (!NAME.matcher(name).matches()) { done.accept(new String[]{"invalid-name"}); return; }
        if (bots.containsKey(name.toLowerCase()) || Bukkit.getPlayerExact(name) != null) {
            done.accept(new String[]{"already-exists"});
            return;
        }
        int max = plugin.getConfig().getInt("settings.max-bots", 50);
        if (bots.size() >= max) { done.accept(new String[]{"max-reached", String.valueOf(max)}); return; }

        final String skinToUse = resolveSkinName(name, skinName);
        // a configured spawn point wins over the position the command was run at
        Location fixed = spawnPoint();
        final Location target = fixed != null ? fixed : where;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ProfileProperty skin = skinToUse == null ? null : fetchSkin(skinToUse);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bot bot = new Bot(name);
                bot.skinName = skinToUse;
                bot.skin = skin;
                bot.invisible = plugin.getConfig().getBoolean("settings.invisible-by-default", false);
                done.accept(spawnNow(bot, target));
            });
        });
    }

    /** The fixed spawn point from config.yml, or null when it is disabled or its world is missing. */
    public Location spawnPoint() {
        if (!plugin.getConfig().getBoolean("spawn.enabled", false)) return null;
        World w = Bukkit.getWorld(plugin.getConfig().getString("spawn.world", ""));
        if (w == null) return null;
        return new Location(w,
                plugin.getConfig().getDouble("spawn.x"),
                plugin.getConfig().getDouble("spawn.y"),
                plugin.getConfig().getDouble("spawn.z"),
                (float) plugin.getConfig().getDouble("spawn.yaw"),
                (float) plugin.getConfig().getDouble("spawn.pitch"));
    }

    public void setSpawnPoint(Location l) {
        plugin.getConfig().set("spawn.enabled", true);
        plugin.getConfig().set("spawn.world", l.getWorld().getName());
        plugin.getConfig().set("spawn.x", Math.round(l.getX() * 100.0) / 100.0);
        plugin.getConfig().set("spawn.y", Math.round(l.getY() * 100.0) / 100.0);
        plugin.getConfig().set("spawn.z", Math.round(l.getZ() * 100.0) / 100.0);
        plugin.getConfig().set("spawn.yaw", Math.round(l.getYaw() * 10.0) / 10.0);
        plugin.getConfig().set("spawn.pitch", Math.round(l.getPitch() * 10.0) / 10.0);
        plugin.saveConfig();
    }

    public void clearSpawnPoint() {
        plugin.getConfig().set("spawn.enabled", false);
        plugin.saveConfig();
    }

    /** Applies or removes invisibility for one bot. Main thread only. */
    public void applyVisibility(Bot bot, Player p) {
        if (bot.invisible) {
            if (!p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                        PotionEffect.INFINITE_DURATION, 0, false, false, false));
            }
            if (!p.isInvisible()) p.setInvisible(true);
        } else if (bot.invisibleApplied) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.setInvisible(false);
        }
        bot.invisibleApplied = bot.invisible;
    }

    /** Returns null on success, otherwise {key, detail}. Main thread only. */
    private String[] spawnNow(Bot bot, Location where) {
        if (engineFailed) return new String[]{"nms-error", "engine disabled"};
        if (Bukkit.getPlayerExact(bot.name) != null || bots.containsKey(bot.name.toLowerCase())) {
            return new String[]{"already-exists"};
        }
        bots.put(bot.name.toLowerCase(), bot);
        byUuid.put(bot.uuid, bot);
        try {
            Player p = nms.spawn(bot.name, bot.uuid, bot.skin, where);
            bot.lastKnown = p.getLocation();
            applyVisibility(bot, p);
            save();
            return null;
        } catch (Throwable t) {
            bots.remove(bot.name.toLowerCase());
            byUuid.remove(bot.uuid);
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            plugin.getLogger().warning("Failed to spawn bot " + bot.name + ": " + root);
            if (plugin.getConfig().getBoolean("debug", false)) t.printStackTrace();
            return new String[]{"spawn-failed", String.valueOf(root.getMessage() == null ? root : root.getMessage())};
        }
    }

    public boolean remove(String name) {
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) return false;
        removeBot(bot, true);
        return true;
    }

    public int removeAll() {
        List<Bot> list = all();
        for (Bot b : list) removeBot(b, true);
        return list.size();
    }

    private void removeBot(Bot bot, boolean persist) {
        Player p = bot.player();
        bots.remove(bot.name.toLowerCase());
        if (p != null) {
            try {
                nms.disconnect(p, "Removed");
            } catch (Throwable t) {
                plugin.getLogger().warning("Disconnect failed for " + bot.name + ": " + t);
            }
        }
        // keep the uuid mapped for a moment so the quit event can still recognise the bot
        Bukkit.getScheduler().runTaskLater(plugin, () -> byUuid.remove(bot.uuid), 5L);
        if (persist) save();
    }

    // ------------------------------------------------------------------ skins

    private String resolveSkinName(String botName, String requested) {
        if (requested != null && !requested.isBlank()) return requested;
        if (plugin.getConfig().getBoolean("skins.use-bot-name", true)) return botName;
        List<String> pool = plugin.getConfig().getStringList("skins.random-pool");
        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Blocking Mojang lookup - call off the main thread. */
    public ProfileProperty fetchSkin(String playerName) {
        return skinCache.computeIfAbsent(playerName.toLowerCase(), k -> {
            try {
                PlayerProfile profile = Bukkit.createProfile((UUID) null, playerName);
                profile.complete(true);
                for (ProfileProperty prop : profile.getProperties()) {
                    if ("textures".equals(prop.getName())) return Optional.of(prop);
                }
            } catch (Throwable t) {
                plugin.getLogger().fine("Skin lookup failed for " + playerName + ": " + t);
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** Changes a bot's skin by respawning it at the same spot. */
    public void changeSkin(Bot bot, String skinName, Consumer<String[]> done) {
        Player p = bot.player();
        Location loc = p != null ? p.getLocation() : bot.lastKnown;
        if (loc == null) { done.accept(new String[]{"not-found", bot.name}); return; }
        Bot copy = new Bot(bot.name);
        copy.lookAt = bot.lookAt; copy.jump = bot.jump; copy.sneak = bot.sneak;
        copy.swing = bot.swing; copy.chat = bot.chat; copy.invisible = bot.invisible;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ProfileProperty skin = fetchSkin(skinName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                removeBot(bot, false);
                // wait until the old entity is fully gone before creating the new one
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    copy.skinName = skinName;
                    copy.skin = skin;
                    done.accept(spawnNow(copy, loc));
                }, 6L);
            });
        });
    }

    // ------------------------------------------------------------------ ticking

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() {
        if (ticker != null) ticker.cancel();
    }

    private void tick() {
        tickCount++;
        boolean gravity = plugin.getConfig().getBoolean("settings.gravity", true);
        double lookRange = plugin.getConfig().getDouble("settings.look-range", 8.0);
        long chatEvery = Math.max(1, plugin.getConfig().getLong("settings.chat-interval-seconds", 60)) * 20L;

        for (Bot bot : all()) {
            Player p = bot.player();
            if (p == null || !p.isOnline()) continue;
            try {
                nms.tick(p, tickCount % 10 == 0);
                if (gravity) physics(bot, p);
                if (tickCount % 20 == 0 && (bot.invisible || bot.invisibleApplied)) applyVisibility(bot, p);
                if (bot.sneak != p.isSneaking()) p.setSneaking(bot.sneak);
                if (bot.lookAt && tickCount % 2 == 0) lookAtNearest(p, lookRange);
                if (bot.swing && tickCount % 12 == 0) p.swingMainHand();
                if (bot.chat && (tickCount + bot.name.hashCode()) % chatEvery == 0) randomChat(p);
            } catch (Throwable t) {
                if (tickCount % 200 == 0) {
                    plugin.getLogger().warning("Bot tick error (" + bot.name + "): " + t);
                }
            }
        }
    }

    private void physics(Bot bot, Player p) throws Exception {
        Location loc = p.getLocation();
        if (p.isInWater() || p.isInLava() || p.isFlying() || p.isInsideVehicle()) {
            bot.velocityY = 0;
            return;
        }
        boolean ground = isSolid(loc.clone().subtract(0, 0.05, 0));
        if (ground && bot.velocityY <= 0) {
            bot.velocityY = 0;
            if (bot.jump && --bot.hopCooldown <= 0) {
                bot.velocityY = 0.42;
                bot.hopCooldown = 30 + ThreadLocalRandom.current().nextInt(40);
            } else {
                return;
            }
        }
        bot.velocityY = (bot.velocityY - 0.08) * 0.98;
        double ny = loc.getY() + bot.velocityY;
        if (bot.velocityY < 0) {
            Location feet = loc.clone();
            feet.setY(ny);
            if (isSolid(feet)) {
                ny = Math.floor(ny) + 1.0;
                bot.velocityY = 0;
            }
        } else {
            Location head = loc.clone();
            head.setY(ny + 1.8);
            if (isSolid(head)) {
                ny = loc.getY();
                bot.velocityY = 0;
            }
        }
        nms.setPos(p, loc.getX(), ny, loc.getZ());
    }

    private static boolean isSolid(Location l) {
        return !l.getBlock().isPassable();
    }

    private void lookAtNearest(Player bot, double range) {
        Player nearest = null;
        double best = range * range;
        for (Player other : bot.getWorld().getPlayers()) {
            if (other == bot || byUuid.containsKey(other.getUniqueId())) continue;
            if (other.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double d = other.getLocation().distanceSquared(bot.getLocation());
            if (d < best) { best = d; nearest = other; }
        }
        if (nearest == null) return;
        Location from = bot.getEyeLocation();
        Location to = nearest.getEyeLocation();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        bot.setRotation(yaw, pitch);
    }

    private void randomChat(Player p) {
        List<String> lines = plugin.getConfig().getStringList("settings.chat-messages");
        if (lines.isEmpty()) return;
        p.chat(lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
    }

    // ------------------------------------------------------------------ persistence

    public void save() {
        if (!plugin.getConfig().getBoolean("settings.persist-on-restart", true)) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Bot b : bots.values()) {
            Player p = b.player();
            Location loc = p != null ? p.getLocation() : b.lastKnown;
            if (loc == null || loc.getWorld() == null) continue;
            String base = "bots." + b.name + ".";
            yml.set(base + "skin-name", b.skinName);
            if (b.skin != null) {
                yml.set(base + "skin-value", b.skin.getValue());
                yml.set(base + "skin-signature", b.skin.getSignature());
            }
            yml.set(base + "world", loc.getWorld().getName());
            yml.set(base + "x", loc.getX());
            yml.set(base + "y", loc.getY());
            yml.set(base + "z", loc.getZ());
            yml.set(base + "yaw", loc.getYaw());
            yml.set(base + "pitch", loc.getPitch());
            yml.set(base + "look-at", b.lookAt);
            yml.set(base + "jump", b.jump);
            yml.set(base + "sneak", b.sneak);
            yml.set(base + "swing", b.swing);
            yml.set(base + "chat", b.chat);
            yml.set(base + "invisible", b.invisible);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    public void restore() {
        if (!plugin.getConfig().getBoolean("settings.persist-on-restart", true) || !dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yml.getConfigurationSection("bots");
        if (root == null) return;
        int delay = 0;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            Location loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
            Bot bot = new Bot(name);
            bot.skinName = s.getString("skin-name");
            String value = s.getString("skin-value");
            if (value != null) bot.skin = new ProfileProperty("textures", value, s.getString("skin-signature"));
            bot.lookAt = s.getBoolean("look-at", true);
            bot.jump = s.getBoolean("jump");
            bot.sneak = s.getBoolean("sneak");
            bot.swing = s.getBoolean("swing");
            bot.chat = s.getBoolean("chat");
            bot.invisible = s.getBoolean("invisible");
            // stagger joins so a big restore does not stall one tick
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawnNow(bot, loc), delay);
            delay += 4;
        }
    }

    /** Called on plugin disable: remember everyone, then disconnect them cleanly. */
    public void shutdown() {
        stop();
        save();
        for (Bot b : all()) {
            Player p = b.player();
            if (p == null) continue;
            try { nms.disconnect(p, "Server stopping"); } catch (Throwable ignored) { }
        }
        bots.clear();
        byUuid.clear();
    }

    public void markEngineFailed() {
        engineFailed = true;
    }
}
