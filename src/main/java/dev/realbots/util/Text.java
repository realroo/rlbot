package dev.realbots.util;

import dev.realbots.RealBotsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() { }

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** MiniMessage without the default italic that item names and lore get. */
    public static Component item(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(item(l));
        return out;
    }

    /** Sends configured message {@code key}; pairs are placeholder, value, placeholder, value... */
    public static void send(RealBotsPlugin plugin, CommandSender to, String key, String... pairs) {
        String raw = plugin.getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace("{" + pairs[i] + "}", MM.escapeTags(pairs[i + 1]));
        }
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        to.sendMessage(mm(prefix + raw));
    }
}
