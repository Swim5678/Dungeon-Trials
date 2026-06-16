package org.swim.dungeonTrials.util;

import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class MonsterTypes {

    public static final Set<EntityType> MONSTERS;

    static {
        Set<EntityType> s = EnumSet.of(
                EntityType.BLAZE, EntityType.BOGGED, EntityType.BREEZE,
                EntityType.CAVE_SPIDER, EntityType.CREAKING,
                EntityType.CREEPER, EntityType.DROWNED, EntityType.ELDER_GUARDIAN,
                EntityType.ENDER_DRAGON, EntityType.ENDERMAN, EntityType.ENDERMITE,
                EntityType.EVOKER, EntityType.GHAST, EntityType.GIANT, EntityType.GUARDIAN,
                EntityType.HOGLIN, EntityType.HUSK, EntityType.ILLUSIONER, EntityType.MAGMA_CUBE,
                EntityType.PARCHED, EntityType.PHANTOM, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
                EntityType.PILLAGER, EntityType.RAVAGER, EntityType.SHULKER,
                EntityType.SILVERFISH, EntityType.SKELETON, EntityType.SKELETON_HORSE,
                EntityType.SLIME, EntityType.SPIDER, EntityType.STRAY,
                EntityType.VEX, EntityType.VINDICATOR, EntityType.WARDEN, EntityType.WITCH,
                EntityType.WITHER, EntityType.WITHER_SKELETON, EntityType.ZOGLIN, EntityType.ZOMBIE,
                EntityType.ZOMBIE_HORSE, EntityType.ZOMBIFIED_PIGLIN
        );
        addIfPresent(s, "CAMEL_HUSK");
        addIfPresent(s, "ZOMBIE_NAUTILUS");
        addIfPresent(s, "SULFUR_CUBE");
        addIfPresent(s, "ZOMBIE_VILLAGER");
        MONSTERS = Collections.unmodifiableSet(s);
    }

    private MonsterTypes() {
    }

    public static boolean isMonster(EntityType type) {
        return MONSTERS.contains(type);
    }

    private static void addIfPresent(Set<EntityType> set, String name) {
        try {
            set.add(EntityType.valueOf(name));
        } catch (IllegalArgumentException ignored) {
        }
    }
}
