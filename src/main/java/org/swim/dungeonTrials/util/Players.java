package org.swim.dungeonTrials.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class Players {

    private Players() {
    }

    public static Player online(UUID uuid) {
        if (uuid == null) return null;
        Player p = Bukkit.getPlayer(uuid);
        return (p != null && p.isOnline()) ? p : null;
    }
}
