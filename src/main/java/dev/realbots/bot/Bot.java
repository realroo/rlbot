package dev.realbots.bot;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Data and runtime state of one fake player. */
public final class Bot {

    public final String name;
    public final UUID uuid;

    public String skinName;
    public ProfileProperty skin;          // textures property, may be null

    // behaviours (toggled from the GUI or /rb toggle)
    public boolean lookAt = true;
    public boolean jump = false;
    public boolean sneak = false;
    public boolean swing = false;
    public boolean chat = false;
    public boolean invisible = false;

    // runtime only
    public boolean invisibleApplied = false;
    public double velocityY = 0;
    public int hopCooldown = 20;
    public Location lastKnown;

    public Bot(String name) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Player player() {
        return Bukkit.getPlayer(uuid);
    }

    public boolean isOnline() {
        Player p = player();
        return p != null && p.isOnline();
    }
}
