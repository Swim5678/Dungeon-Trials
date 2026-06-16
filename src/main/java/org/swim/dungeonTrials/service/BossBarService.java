package org.swim.dungeonTrials.service;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.swim.dungeonTrials.config.ConfigManager;
import org.swim.dungeonTrials.config.LevelConfig;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.util.Players;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BossBarService {

    private static final BarStyle BOSS_BAR_STYLE = BarStyle.SOLID;
    private static final BarColor BOSS_BAR_COLOR = BarColor.GREEN;

    private final ConfigManager configManager;
    private final Map<String, BossBar> regionBossBars = new ConcurrentHashMap<>();

    public BossBarService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void onRegionCreated(RegionState region) {
        if (region == null) return;
        if (regionBossBars.containsKey(region.regionId())) return;
        String title = computeTitle(region);
        BossBar bar = Bukkit.createBossBar(title, BOSS_BAR_COLOR, BOSS_BAR_STYLE);
        bar.setVisible(true);
        regionBossBars.put(region.regionId(), bar);
    }

    public void onMemberAdded(RegionState region, UUID memberUuid) {
        if (region == null) return;
        BossBar bar = regionBossBars.get(region.regionId());
        if (bar == null) return;
        Player p = Players.online(memberUuid);
        if (p == null) return;
        if (!bar.getPlayers().contains(p)) {
            bar.addPlayer(p);
        }
    }

    public void onMemberRemoved(RegionState region, UUID memberUuid) {
        if (region == null) return;
        BossBar bar = regionBossBars.get(region.regionId());
        if (bar == null) return;
        Player p = Players.online(memberUuid);
        if (p != null && bar.getPlayers().contains(p)) {
            bar.removePlayer(p);
        }
    }

    public void onPlayerEnteredRegionWorld(RegionState region, UUID memberUuid) {
        onMemberAdded(region, memberUuid);
    }

    public void onPlayerLeftRegionWorld(RegionState region, UUID memberUuid) {
        onMemberRemoved(region, memberUuid);
    }

    public void onStateChanged(RegionState region) {
        if (region == null) return;
        BossBar bar = regionBossBars.get(region.regionId());
        if (bar == null) return;
        updateContent(bar, region);
    }

    public void onRegionEnded(String regionId) {
        if (regionId == null) return;
        BossBar bar = regionBossBars.remove(regionId);
        if (bar != null) bar.removeAll();
    }

    public void applyStyle() {
        for (BossBar bar : regionBossBars.values()) {
            bar.setStyle(BOSS_BAR_STYLE);
        }
    }

    public void clearAll() {
        for (BossBar bar : regionBossBars.values()) {
            bar.removeAll();
        }
        regionBossBars.clear();
    }

    private String computeTitle(RegionState region) {
        LevelConfig config = configManager.getEffectiveLevelConfig(region.currentLevel(), region.structureId());
        int requirement = (config != null) ? config.clearRequirement() : 0;
        return configManager.getMessageLegacy(
                "bossbar.title",
                "level", String.valueOf(region.currentLevel()),
                "kills", String.valueOf(region.kills()),
                "requirement", String.valueOf(requirement));
    }

    private void updateContent(BossBar bar, RegionState region) {
        bar.setTitle(computeTitle(region));

        LevelConfig config = configManager.getEffectiveLevelConfig(region.currentLevel(), region.structureId());
        int requirement = (config != null) ? config.clearRequirement() : 0;
        float progress = (requirement > 0)
                ? Math.min(1f, region.kills() / (float) requirement)
                : 0f;
        bar.setProgress(progress);

        BarColor color;
        if (region.infinite()) {
            color = BarColor.PURPLE;
        } else if (progress >= 1f) {
            color = BarColor.RED;
        } else if (progress >= 0.75f) {
            color = BarColor.YELLOW;
        } else {
            color = BOSS_BAR_COLOR;
        }
        bar.setColor(color);
    }
}
