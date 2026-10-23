package dev.realbots.command;

import dev.realbots.RealBotsPlugin;
import dev.realbots.bot.Bot;
import dev.realbots.bot.BotManager;
import dev.realbots.gui.GuiManager;
import dev.realbots.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class BotCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of(
            "gui", "spawn", "spawnmany", "joinlist", "names", "remove", "removeall", "list",
            "tp", "bring", "say", "cmd", "toggle", "setspawn", "clearspawn", "reload");
    private static final List<String> FEATURES = List.of("lookat", "jump", "sneak", "swing", "chat", "invisible");

    private final RealBotsPlugin plugin;
    private final BotManager bots;
    private final GuiManager gui;

    public BotCommand(RealBotsPlugin plugin, BotManager bots, GuiManager gui) {
        this.plugin = plugin;
        this.bots = bots;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("realbots.use")) {
            Text.send(plugin, s, "no-permission");
            return true;
        }
        String sub = a.length == 0 ? "gui" : a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("realbots.admin");

        switch (sub) {
            case "gui" -> {
                if (!(s instanceof Player p)) { Text.send(plugin, s, "players-only"); return true; }
                gui.openMain(p, 0);
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder();
                for (Bot b : bots.all()) sb.append(sb.length() == 0 ? "" : ", ").append(b.name);
                s.sendMessage(Text.mm("<gray>Bots (" + bots.count() + "): <white>"
                        + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(sb.toString())));
            }
            default -> {
                if (!admin) { Text.send(plugin, s, "no-permission"); return true; }
                admin(s, sub, a);
            }
        }
        return true;
    }

    private void admin(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "spawn" -> {
                if (a.length < 2) { s.sendMessage(Text.mm("<gray>/rb spawn <name> [skin]")); return; }
                Location where = locationOf(s);
                if (where == null) { Text.send(plugin, s, "players-only"); return; }
                String name = a[1];
                bots.spawn(name, a.length > 2 ? a[2] : null, where, err -> report(s, err, name));
            }
            case "spawnmany" -> {
                if (a.length < 2) { s.sendMessage(Text.mm("<gray>/rb spawnmany <count> [prefix]")); return; }
                Location where = locationOf(s);
                if (where == null) { Text.send(plugin, s, "players-only"); return; }
                int n;
                try { n = Math.max(1, Math.min(50, Integer.parseInt(a[1]))); }
                catch (NumberFormatException e) { s.sendMessage(Text.mm("<red>Not a number.")); return; }
                String prefix = a.length > 2 ? a[2] : plugin.getConfig().getString("names.random-prefix", "Bot_");
                // custom names from the names/ folder first, then generated ones
                spawnList(s, plugin.names().pick(bots, n, prefix), where);
            }
            case "joinlist" -> {
                Location where = locationOf(s);
                if (where == null) { Text.send(plugin, s, "players-only"); return; }
                List<String> list;
                if (a.length > 1) {
                    list = plugin.names().fromFile(a[1]);
                    if (list == null) {
                        s.sendMessage(Text.mm("<red>No names file called <white>" + a[1]
                                + "</white>. Files: <white>" + String.join(", ", plugin.names().fileNames())));
                        return;
                    }
                } else {
                    list = plugin.names().all();
                }
                if (list.isEmpty()) {
                    s.sendMessage(Text.mm("<red>No names found. Add names to <white>plugins/RealBots/names/*.txt</white> and run <white>/rb reload</white>."));
                    return;
                }
                spawnList(s, list, where);
            }
            case "names" -> {
                s.sendMessage(Text.mm("<gray>Name files: <white>" + String.join(", ", plugin.names().fileNames())
                        + " <gray>(" + plugin.names().all().size() + " names)"));
            }
            case "remove" -> {
                if (a.length < 2) { s.sendMessage(Text.mm("<gray>/rb remove <name>")); return; }
                if (bots.remove(a[1])) Text.send(plugin, s, "removed", "name", a[1]);
                else Text.send(plugin, s, "not-found", "name", a[1]);
            }
            case "removeall" -> Text.send(plugin, s, "removed-all", "count", String.valueOf(bots.removeAll()));
            case "tp", "bring" -> {
                if (!(s instanceof Player p)) { Text.send(plugin, s, "players-only"); return; }
                Bot b = a.length > 1 ? bots.get(a[1]) : null;
                Player bp = b == null ? null : b.player();
                if (bp == null) { Text.send(plugin, s, "not-found", "name", a.length > 1 ? a[1] : "?"); return; }
                if (sub.equals("tp")) p.teleport(bp.getLocation());
                else { bp.teleport(p.getLocation()); b.velocityY = 0; }
                Text.send(plugin, s, "teleported");
            }
            case "say", "cmd" -> {
                if (a.length < 3) { s.sendMessage(Text.mm("<gray>/rb " + sub + " <name> <text...>")); return; }
                Bot b = bots.get(a[1]);
                Player bp = b == null ? null : b.player();
                if (bp == null) { Text.send(plugin, s, "not-found", "name", a[1]); return; }
                String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                if (sub.equals("say")) bp.chat(text);
                else bp.performCommand(text.startsWith("/") ? text.substring(1) : text);
                Text.send(plugin, s, "said", "name", b.name, "text", text);
            }
            case "toggle" -> {
                if (a.length < 3) { s.sendMessage(Text.mm("<gray>/rb toggle <name> <lookat|jump|sneak|swing|chat|invisible>")); return; }
                Bot b = bots.get(a[1]);
                if (b == null) { Text.send(plugin, s, "not-found", "name", a[1]); return; }
                boolean state;
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "lookat" -> state = b.lookAt = !b.lookAt;
                    case "jump" -> state = b.jump = !b.jump;
                    case "sneak" -> state = b.sneak = !b.sneak;
                    case "swing" -> state = b.swing = !b.swing;
                    case "chat" -> state = b.chat = !b.chat;
                    case "invisible" -> {
                        state = b.invisible = !b.invisible;
                        Player bp = b.player();
                        if (bp != null) bots.applyVisibility(b, bp);
                    }
                    default -> { s.sendMessage(Text.mm("<red>Unknown feature.")); return; }
                }
                bots.save();
                Text.send(plugin, s, "toggled", "name", b.name, "feature", a[2], "state", state ? "ON" : "OFF");
            }
            case "setspawn" -> {
                if (!(s instanceof Player p)) { Text.send(plugin, s, "players-only"); return; }
                bots.setSpawnPoint(p.getLocation());
                Text.send(plugin, s, "spawn-set");
            }
            case "clearspawn" -> {
                bots.clearSpawnPoint();
                Text.send(plugin, s, "spawn-cleared");
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.names().reload();
                Text.send(plugin, s, "reloaded");
            }
            default -> s.sendMessage(Text.mm("<gray>/rb <" + String.join("|", SUB) + ">"));
        }
    }

    /** Spawns the given names one after another (a few ticks apart) at {@code where}. */
    private void spawnList(CommandSender s, List<String> names, Location where) {
        int i = 0;
        for (String name : names) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> bots.spawn(name, null, where, err -> { if (err != null) report(s, err, name); }), i * 3L);
            i++;
        }
        s.sendMessage(Text.mm("<green>Spawning " + names.size() + " bots..."));
    }

    private Location locationOf(CommandSender s) {
        if (s instanceof Player p) return p.getLocation();
        World w = Bukkit.getWorlds().get(0);
        return w.getSpawnLocation();
    }

    private void report(CommandSender s, String[] err, String name) {
        if (err == null) Text.send(plugin, s, "spawned", "name", name);
        else Text.send(plugin, s, err[0], GuiManager.errArgs(err, name));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : SUB) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && List.of("remove", "tp", "bring", "say", "cmd", "toggle").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (Bot b : bots.all()) if (b.name.toLowerCase().startsWith(a[1].toLowerCase())) out.add(b.name);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("joinlist")) {
            for (String f : plugin.names().fileNames()) if (f.startsWith(a[1].toLowerCase())) out.add(f);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("toggle")) {
            for (String f : FEATURES) if (f.startsWith(a[2].toLowerCase())) out.add(f);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("spawn")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[2].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}
