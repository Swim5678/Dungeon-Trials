package org.swim.dungeonTrials.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.model.RewardConfig;
import org.swim.dungeonTrials.model.RewardEntry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class RewardService {

    private final DungeonTrials plugin;
    private final ConfigManager configManager;
    private volatile RewardConfig rewardConfig = RewardConfig.EMPTY;

    public RewardService(DungeonTrials plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void reload() {
        this.rewardConfig = configManager.rewardConfig();
    }

    public boolean isEnabled() {
        return rewardConfig.enabled();
    }

    public double xpMultiplier() {
        return rewardConfig.xpMultiplier();
    }

    public void grantKillRewards(Player killer, Location dropLoc, EntityType mobType) {
        if (!rewardConfig.enabled() || killer == null || !killer.isOnline()) return;
        List<RewardEntry> entries = rewardConfig.perKillFor(mobType);
        if (entries.isEmpty()) return;

        int overflowed = grantEntries(killer, dropLoc, entries);
        sendOverflowIfAny(killer, overflowed);
    }

    public void grantLevelClearBonus(Player player, int completedLevel) {
        if (!rewardConfig.enabled() || player == null || !player.isOnline()) return;
        List<RewardEntry> entries = rewardConfig.levelClearFor(completedLevel);
        if (entries.isEmpty()) return;
        int overflowed = grantEntries(player, player.getLocation(), entries);
        sendOverflowIfAny(player, overflowed);
    }

    public void grantInfiniteBonus(Player player) {
        if (!rewardConfig.enabled() || !rewardConfig.infiniteBonusEnabled()) return;
        if (player == null || !player.isOnline()) return;
        if (rewardConfig.infiniteBonus().isEmpty()) return;
        int overflowed = grantEntries(player, player.getLocation(), rewardConfig.infiniteBonus());
        sendOverflowIfAny(player, overflowed);
    }

    private int grantEntries(Player player, Location dropLoc, List<RewardEntry> entries) {
        int overflowed = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (RewardEntry e : entries) {
            if (!e.rollChance(rng)) continue;
            int amount = e.rollAmount(rng);
            if (amount <= 0) continue;
            int dropped = giveOrDrop(player, dropLoc, e.material(), amount);
            if (dropped > 0) {
                overflowed += dropped;
            }
        }
        return overflowed;
    }

    private int giveOrDrop(Player player, Location dropLoc, Material material, int amount) {
        if (amount <= 0) return 0;
        ItemStack stack = new ItemStack(material, amount);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        if (overflow.isEmpty()) return 0;
        Location loc = (dropLoc != null && dropLoc.getWorld() != null) ? dropLoc : player.getLocation();
        int total = 0;
        for (ItemStack leftover : overflow.values()) {
            int size = leftover.getAmount();
            loc.getWorld().dropItemNaturally(loc, leftover);
            total += size;
        }
        return total;
    }

    private void sendOverflowIfAny(Player player, int overflowed) {
        if (overflowed <= 0) return;
        Component msg = plugin.getConfigManager().getMessage("rewards.inventory-full-dropped",
                "count", String.valueOf(overflowed));
        player.sendMessage(msg);
    }
}
