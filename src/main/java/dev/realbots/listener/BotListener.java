package dev.realbots.listener;

import dev.realbots.RealBotsPlugin;
import dev.realbots.bot.Bot;
import dev.realbots.bot.BotManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BotListener implements Listener {

    private final RealBotsPlugin plugin;
    private final BotManager bots;

    public BotListener(RealBotsPlugin plugin, BotManager bots) {
        this.plugin = plugin;
        this.bots = bots;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        if (bots.isBot(e.getPlayer()) && plugin.getConfig().getBoolean("settings.hide-join-message", false)) {
            e.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!bots.isBot(p)) return;
        if (plugin.getConfig().getBoolean("settings.hide-quit-message", false)) {
            e.quitMessage(null);
        }
        Bot b = bots.byPlayer(p);
        if (b != null) b.lastKnown = p.getLocation();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Bot b = bots.byPlayer(p);
        if (b == null || !plugin.getConfig().getBoolean("settings.auto-respawn", true)) return;
        b.velocityY = 0;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        }, 5L);
    }
}
