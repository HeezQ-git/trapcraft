package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.function.SetCountLootFunction;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.loot.provider.number.UniformLootNumberProvider;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * Puts seeds into the world so the first one doesn't have to come from a
 * command or a trader who may never spawn.
 *
 * Three sources, deliberately: grass for the player who just wanders, chests
 * for the player who explores, traders for the player who wants a specific
 * strain. Which strain you get from grass and chests is random -- picking your
 * phenotype is what the traders are for.
 */
public final class TrapLoot {
    private TrapLoot() {
    }

    // ponytail: identifiers, not LootTables constants. The constant names churn
    // between versions; these data paths have been stable for years.
    private static final Set<RegistryKey<LootTable>> GRASS = Set.of(
            blocks("short_grass"),
            blocks("tall_grass"));

    private static final Set<RegistryKey<LootTable>> CHESTS = Set.of(
            chest("village/village_plains_house"),
            chest("village/village_savanna_house"),
            chest("village/village_desert_house"),
            chest("abandoned_mineshaft"),
            chest("simple_dungeon"),
            chest("shipwreck_supply"),
            chest("jungle_temple"),
            chest("desert_pyramid"));

    /** Warm-climate structures only -- coca shouldn't turn up in a snowy village. */
    private static final Set<RegistryKey<LootTable>> EXOTIC_CHESTS = Set.of(
            chest("jungle_temple"),
            chest("desert_pyramid"),
            chest("shipwreck_supply"),
            chest("village/village_desert_house"));

    /**
     * Where somebody is already in the business.
     *
     * Poppy seed comes off nobody's lawn and out of no village chest -- it is
     * in the places the game already marks as run by people with an operation,
     * which is the fiction AND the gate. Between these and the wandering
     * trader's one-per-visit stock, starting the long line is an expedition
     * rather than a walk, and that is the whole idea.
     */
    private static final Set<RegistryKey<LootTable>> LAB_CHESTS = Set.of(
            chest("pillager_outpost"),
            chest("woodland_mansion"),
            chest("bastion_treasure"),
            chest("bastion_other"));

    private static RegistryKey<LootTable> blocks(String name) {
        return RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.ofVanilla("blocks/" + name));
    }

    private static RegistryKey<LootTable> chest(String name) {
        return RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.ofVanilla("chests/" + name));
    }

    public static void register() {
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            // Only touch vanilla's own tables. Without this we'd also inject
            // into datapack and other mods' tables that happen to share an id.
            if (!source.isBuiltin()) {
                return;
            }
            if (GRASS.contains(key)) {
                // 2% -- roughly as common as vanilla wheat seeds feel, so you
                // find one eventually without it becoming grass confetti.
                builder.pool(seedPool(0.02F, 1, 1));
            } else if (CHESTS.contains(key)) {
                builder.pool(seedPool(0.25F, 1, 3));
            }
            // Coca is deliberately harder to start: warm-climate structures
            // only, and no grass drops at all. You have to go looking.
            if (EXOTIC_CHESTS.contains(key)) {
                builder.pool(LootPool.builder()
                        .rolls(ConstantLootNumberProvider.create(1))
                        .conditionally(RandomChanceLootCondition.builder(0.12F))
                        .with(ItemEntry.builder(TrapContent.cocaSeeds)
                                .apply(SetCountLootFunction.builder(
                                        UniformLootNumberProvider.create(1, 2)))));
            }
            // Rarer again than coca, and never more than one. See LAB_CHESTS.
            if (LAB_CHESTS.contains(key)) {
                builder.pool(LootPool.builder()
                        .rolls(ConstantLootNumberProvider.create(1))
                        .conditionally(RandomChanceLootCondition.builder(0.08F))
                        .with(ItemEntry.builder(TrapContent.poppySeeds)
                                .apply(SetCountLootFunction.builder(
                                        ConstantLootNumberProvider.create(1)))));
            }
        });
    }

    /** One roll, one strain, chosen evenly between them. */
    private static LootPool.Builder seedPool(float chance, int min, int max) {
        LootPool.Builder pool = LootPool.builder()
                .rolls(ConstantLootNumberProvider.create(1))
                .conditionally(RandomChanceLootCondition.builder(chance));
        for (Strain strain : Strain.values()) {
            pool.with(ItemEntry.builder(TrapContent.seeds(strain))
                    .weight(1)
                    .apply(SetCountLootFunction.builder(
                            UniformLootNumberProvider.create(min, max))));
        }
        return pool;
    }
}
