package dev.realbots.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.realbots.RealBotsPlugin;
import dev.realbots.bot.Bot;
import dev.realbots.bot.BotManager;
import dev.realbots.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Chest-style GUIs: bot list (paged) and a per-bot control panel. */
public final class GuiManager implements Listener {

    private enum Type { MAIN, BOT }

    private static final class Holder implements InventoryHolder {
        final Type type;
        final int page;
        final String botName;
        Inventory inv;

        Holder(Type type, int page, String botName) {
            this.type = type;
            this.page = page;
            this.botName = botName;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int PER_PAGE = 45;

    private final RealBotsPlugin plugin;
    private final BotManager bots;
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();

    public GuiManager(RealBotsPlugin plugin, BotManager bots) {
        this.plugin = plugin;
        this.bots = bots;
    }

    // ------------------------------------------------------------------ main menu

    public void openMain(Player viewer, int page) {
        List<Bot> list = bots.all().stream().toList();
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(Type.MAIN, page, null);
        Inventory inv = Bukkit.createInventory(h, 54, Text.mm("<gradient:#4ade80:#22d3ee><bold>RealBots</bold></gradient> <gray>(" + list.size() + ")"));
        h.inv = inv;

        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            inv.setItem(i, botHead(list.get(from + i)));
        }

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);

        if (page > 0) inv.setItem(45, named(Material.ARROW, "<yellow>Previous page"));
        if (page < pages - 1) inv.setItem(53, named(Material.ARROW, "<yellow>Next page"));
        inv.setItem(47, named(Material.EMERALD, "<green><bold>Spawn bot",
                "<gray>Type a name in chat.", "<gray>Spawns at your position."));
        inv.setItem(48, named(Material.PLAYER_HEAD, "<aqua><bold>Spawn 5 random bots",
                "<gray>Uses names from the names/ folder,", "<gray>then random names. At your position."));
        inv.setItem(49, named(Material.PAPER, "<white>Page " + (page + 1) + "/" + pages,
                "<gray>Bots online: <white>" + list.size()));
        inv.setItem(51, named(Material.TNT, "<red><bold>Remove ALL bots", "<gray>Click to disconnect everyone."));
        inv.setItem(52, named(Material.COMMAND_BLOCK, "<gold>Reload config"));

        viewer.openInventory(inv);
    }

    private ItemStack botHead(Bot bot) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        PlayerProfile profile = Bukkit.createProfile(bot.uuid, bot.name);
        if (bot.skin != null) profile.setProperty(bot.skin);
        meta.setPlayerProfile(profile);
        meta.displayName(Text.item("<green><bold>" + bot.name));
        Player p = bot.player();
        String where = p == null ? "<red>offline" :
                "<gray>" + p.getWorld().getName() + " " + p.getLocation().getBlockX() + ", "
                        + p.getLocation().getBlockY() + ", " + p.getLocation().getBlockZ();
        meta.lore(Text.lore(where, "<gray>Skin: <white>" + (bot.skinName == null ? "default" : bot.skinName), "",
                "<yellow>Click to manage"));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ bot menu

    public void openBot(Player viewer, Bot bot) {
        Holder h = new Holder(Type.BOT, 0, bot.name);
        Inventory inv = Bukkit.createInventory(h, 36, Text.mm("<green><bold>" + bot.name));
        h.inv = inv;

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = 0; s < 36; s++) inv.setItem(s, filler);

        inv.setItem(4, botHead(bot));

        inv.setItem(10, named(Material.ENDER_PEARL, "<light_purple>Teleport to bot"));
        inv.setItem(11, named(Material.ENDER_EYE, "<light_purple>Bring bot to me"));
        inv.setItem(12, toggle(Material.COMPASS, "Look at players", bot.lookAt));
        inv.setItem(13, toggle(Material.SLIME_BALL, "Hop / jump", bot.jump));
        inv.setItem(14, toggle(Material.LEATHER_BOOTS, "Sneak", bot.sneak));
        inv.setItem(15, toggle(Material.IRON_SWORD, "Swing arm", bot.swing));
        inv.setItem(16, toggle(Material.WRITABLE_BOOK, "Random chat", bot.chat));

        inv.setItem(19, named(Material.CHEST, "<gold>Open inventory", "<gray>View and edit the bot's items."));
        Player bp = bot.player();
        String gm = bp == null ? "?" : bp.getGameMode().name().toLowerCase();
        inv.setItem(20, named(Material.GRASS_BLOCK, "<gold>Game mode: <white>" + gm, "<gray>Click to cycle."));
        inv.setItem(21, named(Material.NAME_TAG, "<aqua>Make bot say something"));
        inv.setItem(22, named(Material.COMMAND_BLOCK, "<aqua>Make bot run a command"));
        inv.setItem(23, named(Material.LEATHER_CHESTPLATE, "<aqua>Change skin", "<gray>Respawns the bot in place."));
        inv.setItem(24, toggle(Material.GLASS, "Invisible", bot.invisible));
        inv.setItem(25, named(Material.BARRIER, "<red><bold>Remove bot"));

        inv.setItem(31, named(Material.ARROW, "<yellow>Back"));
        viewer.openInventory(inv);
    }

    private ItemStack toggle(Material mat, String label, boolean on) {
        ItemStack it = named(mat, (on ? "<green>" : "<red>") + label,
                "<gray>State: " + (on ? "<green>ON" : "<red>OFF"), "<yellow>Click to toggle");
        if (on) {
            ItemMeta m = it.getItemMeta();
            m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack named(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        if (lore.length > 0) meta.lore(Text.lore(lore));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        if (!viewer.hasPermission("realbots.admin")) {
            viewer.closeInventory();
            Text.send(plugin, viewer, "no-permission");
            return;
        }
        int slot = e.getSlot();
        if (h.type == Type.MAIN) clickMain(viewer, h, slot, e.isRightClick());
        else clickBot(viewer, h, slot);
    }

    private void clickMain(Player viewer, Holder h, int slot, boolean right) {
        if (slot < PER_PAGE) {
            List<Bot> list = bots.all().stream().toList();
            int idx = h.page * PER_PAGE + slot;
            if (idx < list.size()) openBot(viewer, list.get(idx));
            return;
        }
        switch (slot) {
            case 45 -> openMain(viewer, h.page - 1);
            case 53 -> openMain(viewer, h.page + 1);
            case 47 -> {
                viewer.closeInventory();
                Text.send(plugin, viewer, "prompt-name");
                prompt(viewer, text -> spawnAt(viewer, text, null));
            }
            case 48 -> {
                String prefix = plugin.getConfig().getString("names.random-prefix", "Bot_");
                for (String n : plugin.names().pick(bots, 5, prefix)) spawnAt(viewer, n, null);
                Bukkit.getScheduler().runTaskLater(plugin, () -> openMain(viewer, h.page), 20L);
            }
            case 51 -> {
                int n = bots.removeAll();
                Text.send(plugin, viewer, "removed-all", "count", String.valueOf(n));
                openMain(viewer, 0);
            }
            case 52 -> {
                plugin.reloadConfig();
                plugin.names().reload();
                Text.send(plugin, viewer, "reloaded");
            }
            default -> { }
        }
    }

    private void clickBot(Player viewer, Holder h, int slot) {
        Bot bot = bots.get(h.botName);
        if (bot == null) { openMain(viewer, 0); return; }
        Player bp = bot.player();
        switch (slot) {
            case 10 -> { if (bp != null) { viewer.closeInventory(); viewer.teleport(bp.getLocation()); Text.send(plugin, viewer, "teleported"); } }
            case 11 -> { if (bp != null) { bp.teleport(viewer.getLocation()); bot.velocityY = 0; Text.send(plugin, viewer, "teleported"); } }
            case 12 -> { bot.lookAt = !bot.lookAt; bots.save(); openBot(viewer, bot); }
            case 13 -> { bot.jump = !bot.jump; bots.save(); openBot(viewer, bot); }
            case 14 -> { bot.sneak = !bot.sneak; bots.save(); openBot(viewer, bot); }
            case 15 -> { bot.swing = !bot.swing; bots.save(); openBot(viewer, bot); }
            case 16 -> { bot.chat = !bot.chat; bots.save(); openBot(viewer, bot); }
            case 19 -> { if (bp != null) viewer.openInventory(bp.getInventory()); }
            case 20 -> {
                if (bp != null) {
                    GameMode[] modes = {GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR};
                    int i = 0;
                    for (int k = 0; k < modes.length; k++) if (modes[k] == bp.getGameMode()) i = k;
                    bp.setGameMode(modes[(i + 1) % modes.length]);
                    openBot(viewer, bot);
                }
            }
            case 21 -> {
                viewer.closeInventory();
                Text.send(plugin, viewer, "prompt-say", "name", bot.name);
                prompt(viewer, text -> { Player p = bot.player(); if (p != null) p.chat(text); });
            }
            case 22 -> {
                viewer.closeInventory();
                Text.send(plugin, viewer, "prompt-cmd", "name", bot.name);
                prompt(viewer, text -> { Player p = bot.player(); if (p != null) p.performCommand(text.startsWith("/") ? text.substring(1) : text); });
            }
            case 23 -> {
                viewer.closeInventory();
                Text.send(plugin, viewer, "prompt-skin", "name", bot.name);
                prompt(viewer, text -> bots.changeSkin(bot, text, err -> {
                    if (err != null) Text.send(plugin, viewer, err[0], errArgs(err, bot.name));
                }));
            }
            case 24 -> {
                bot.invisible = !bot.invisible;
                if (bp != null) bots.applyVisibility(bot, bp);
                bots.save();
                openBot(viewer, bot);
            }
            case 25 -> {
                bots.remove(bot.name);
                Text.send(plugin, viewer, "removed", "name", bot.name);
                openMain(viewer, 0);
            }
            case 31 -> openMain(viewer, 0);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ helpers

    public void spawnAt(Player viewer, String name, String skin) {
        bots.spawn(name, skin, viewer.getLocation(), err -> {
            if (err == null) Text.send(plugin, viewer, "spawned", "name", name);
            else Text.send(plugin, viewer, err[0], errArgs(err, name));
        });
    }

    /** Maps the {key, detail} result of a failed spawn to message placeholders. */
    public static String[] errArgs(String[] err, String name) {
        String detail = err.length > 1 ? err[1] : "";
        return switch (err[0]) {
            case "max-reached" -> new String[]{"max", detail};
            case "spawn-failed", "nms-error" -> new String[]{"error", detail};
            default -> new String[]{"name", name};
        };
    }

    public static String randomName(String prefix) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(prefix);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        int len = Math.max(1, 16 - prefix.length());
        for (int i = 0; i < Math.min(5, len); i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private void prompt(Player viewer, Consumer<String> action) {
        prompts.put(viewer.getUniqueId(), action);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> action = prompts.remove(e.getPlayer().getUniqueId());
        if (action == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel") || text.isEmpty()) {
                Text.send(plugin, p, "cancelled");
                return;
            }
            action.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }
}
