package dev.realbots.util;

import dev.realbots.RealBotsPlugin;
import dev.realbots.bot.BotManager;
import dev.realbots.gui.GuiManager;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom bot names. Put .txt files in plugins/RealBots/names/ - one name per line,
 * lines starting with # are comments.
 */
public final class NameStore {

    private final RealBotsPlugin plugin;
    private final File dir;
    private final Map<String, List<String>> files = new LinkedHashMap<>();   // file name (no .txt) -> names

    public NameStore(RealBotsPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "names");
    }

    public void reload() {
        files.clear();
        if (!dir.exists()) dir.mkdirs();
        File[] txt = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (txt == null || txt.length == 0) {
            writeExample();
            txt = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (txt == null) return;
        }
        java.util.Arrays.sort(txt);
        for (File f : txt) {
            List<String> names = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String n = line.trim();
                    if (n.isEmpty() || n.startsWith("#")) continue;
                    if (!BotManager.NAME.matcher(n).matches()) {
                        plugin.getLogger().warning("names/" + f.getName() + ": skipped invalid name '" + n
                                + "' (3-16 characters: letters, numbers, underscore)");
                        continue;
                    }
                    boolean dup = false;
                    for (String x : names) if (x.equalsIgnoreCase(n)) { dup = true; break; }
                    if (!dup) names.add(n);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read names/" + f.getName() + ": " + e.getMessage());
                continue;
            }
            String key = f.getName().substring(0, f.getName().length() - 4);
            files.put(key.toLowerCase(Locale.ROOT), names);
        }
        plugin.getLogger().info("Loaded " + all().size() + " custom bot names from " + files.size() + " file(s).");
    }

    private void writeExample() {
        File example = new File(dir, "example.txt");
        String text = "# Custom bot names - one name per line.\n"
                + "# 3-16 characters: letters, numbers and underscore only.\n"
                + "# Lines starting with # are ignored. Delete the # to use a name.\n"
                + "#\n"
                + "# Steve_01\n"
                + "# Alex_02\n"
                + "# Herobrine_X\n";
        try {
            Files.writeString(example.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create names/example.txt: " + e.getMessage());
        }
    }

    /** All names from every file, without duplicates. */
    public List<String> all() {
        List<String> out = new ArrayList<>();
        for (List<String> list : files.values()) {
            for (String n : list) {
                boolean dup = false;
                for (String x : out) if (x.equalsIgnoreCase(n)) { dup = true; break; }
                if (!dup) out.add(n);
            }
        }
        return out;
    }

    /** Names from one file (without .txt), or null if there is no such file. */
    public List<String> fromFile(String file) {
        return files.get(file.toLowerCase(Locale.ROOT).replaceAll("\\.txt$", ""));
    }

    public List<String> fileNames() {
        return new ArrayList<>(files.keySet());
    }

    /**
     * Picks {@code count} names: custom names that are not in use first (random order),
     * then generated random names (prefix + letters) for whatever is left.
     */
    public List<String> pick(BotManager bots, int count, String fallbackPrefix) {
        List<String> free = new ArrayList<>();
        for (String n : all()) {
            if (bots.get(n) == null && Bukkit.getPlayerExact(n) == null) free.add(n);
        }
        Collections.shuffle(free);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count && i < free.size(); i++) out.add(free.get(i));
        while (out.size() < count) out.add(GuiManager.randomName(fallbackPrefix));
        return out;
    }
}
