package org.swim.dungeonTrials.util;

import org.bukkit.Bukkit;

import java.util.UUID;

public final class PlayerResolver {

    private PlayerResolver() {
    }

    public static String resolveName(String uuidStr) {
        if (uuidStr == null) return "?";
        try {
            UUID uuid = UUID.fromString(uuidStr);
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null && !name.isBlank()) return name;
        } catch (IllegalArgumentException ignored) {
        }
        return uuidStr.substring(0, Math.min(8, uuidStr.length()));
    }
}
