package org.swim.dungeonTrials.model;

import org.bukkit.Material;

import java.util.random.RandomGenerator;

public record RewardEntry(
        Material material,
        int minAmount,
        int maxAmount,
        double chance
) {
    public RewardEntry {
        if (material == null) throw new IllegalArgumentException("material required");
        if (minAmount < 1) minAmount = 1;
        if (maxAmount < minAmount) maxAmount = minAmount;
        if (chance < 0.0) chance = 0.0;
        if (chance > 1.0) chance = 1.0;
    }

    public int rollAmount(RandomGenerator rng) {
        if (minAmount == maxAmount) return minAmount;
        return minAmount + rng.nextInt(maxAmount - minAmount + 1);
    }

    public boolean rollChance(RandomGenerator rng) {
        return rng.nextDouble() <= chance;
    }
}
