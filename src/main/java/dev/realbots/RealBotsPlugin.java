package dev.realbots;

import dev.realbots.bot.BotManager;
import dev.realbots.command.BotCommand;
import dev.realbots.gui.GuiManager;
import dev.realbots.listener.BotListener;
import dev.realbots.nms.NmsBridge;
import dev.realbots.util.NameStore;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RealBotsPlugin extends JavaPlugin {

    private BotManager bots;
    private NameStore names;

    public NameStore names() {
        return names;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        names = new NameStore(this);
        names.reload();

        NmsBridge nms = null;
        try {
            nms = new NmsBridge();
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            getLogger().severe("Bot engine could not start on this server version (" + Bukkit.getMinecraftVersion() + "): " + root);
            getLogger().severe("RealBots supports Paper 1.20.5 and newer (Mojang-mapped servers).");
        }

        bots = new BotManager(this, nms);
        if (nms == null) bots.markEngineFailed();

        GuiManager gui = new GuiManager(this, bots);
        BotCommand command = new BotCommand(this, bots, gui);
        PluginCommand pc = getCommand("realbots");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(new BotListener(this, bots), this);

        if (nms != null) {
            bots.start();
            // wait until all worlds are loaded before bringing saved bots back
            Bukkit.getScheduler().runTaskLater(this, bots::restore, 40L);
        }
        getLogger().info("RealBots enabled" + (nms == null ? " (engine unavailable)" : "") + ".");
    }

    @Override
    public void onDisable() {
        if (bots != null) bots.shutdown();
    }
}
