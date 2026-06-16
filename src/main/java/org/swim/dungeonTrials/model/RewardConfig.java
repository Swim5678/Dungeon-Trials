package org.swim.dungeonTrials.model;

import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Map;

public record RewardConfig(
        boolean enabled,
        double xpMultiplier,
        Map<EntityType, List<RewardEntry>> perKillDrops,
        Map<Integer, List<RewardEntry>> levelClearBonus,
        boolean infiniteBonusEnabled,
        List<RewardEntry> infiniteBonus
) {

    public static final RewardConfig EMPTY = new RewardConfig(
            false, 1.0, Map.of(), Map.of(), false, List.of()
    );

    public List<RewardEntry> perKillFor(EntityType type) {
        return perKillDrops.getOrDefault(type, List.of());
    }

    public List<RewardEntry> levelClearFor(int level) {
        return levelClearBonus.getOrDefault(level, List.of());
    }
}
