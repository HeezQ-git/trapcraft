package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.function.SetCountLootFunction;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cases and keys: the Counter-Strike loop, wearing this server's clothes.
 *
 * A case is free and a key is not. You get cases for playing -- off things you
 * kill and out of chests you were opening anyway -- and they sit in your
 * inventory being worth nothing until you find or buy the key that matches.
 * That asymmetry is the entire mechanic, and it is why the cases are NOT sold
 * anywhere: a case you can buy is just a slow shop.
 *
 * The odds and the contents are in {@link CaseOdds}, away from Minecraft so
 * they can be checked without one. This class is the half that needs a world:
 * turning ids into items, putting keys in chests, dropping cases off mobs, and
 * telling the room when somebody pulls a wand out of a box.
 *
 * <h2>Where the money goes</h2>
 *
 * Nowhere, and deliberately. Opening a case moves no emeralds: the only
 * transaction in the feature is buying the key, which happens at a shop shelf
 * and goes through {@link TrapMarket} like everything else. So there is no
 * route by which this file can mint or destroy currency, and the economy
 * invariant is held by not participating in it. The sink is real all the same
 * -- 22,000e for a phantom key is money that leaves the server -- it just
 * leaves through the till rather than through here.
 */
public final class TrapCases {

    private TrapCases() {
    }

    /** Rarity colours, Counter-Strike's own. */
    private static final Map<CaseOdds.Grade, Formatting> COLOURS =
            new EnumMap<>(Map.of(
                    CaseOdds.Grade.MIL_SPEC, Formatting.BLUE,
                    CaseOdds.Grade.RESTRICTED, Formatting.DARK_PURPLE,
                    CaseOdds.Grade.CLASSIFIED, Formatting.LIGHT_PURPLE,
                    CaseOdds.Grade.COVERT, Formatting.RED,
                    CaseOdds.Grade.EXOTIC, Formatting.GOLD));

    public static Formatting colour(CaseOdds.Grade grade) {
        return COLOURS.get(grade);
    }

    // --- turning the tables into items ---------------------------------------

    /**
     * Resolved drops, built once the registry is complete.
     *
     * The pools name items by id for the same reason {@link ShopStock} does --
     * they are read by a test that has no Minecraft on its classpath -- so
     * somebody has to resolve them, once, after registration. An id that
     * doesn't resolve is dropped with a log line rather than crashing the
     * server: a case one line short still opens.
     */
    private static final Map<String, Item> RESOLVED = new HashMap<>();
    private static boolean resolved;

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
            for (CaseOdds.Grade grade : CaseOdds.Grade.values()) {
                for (CaseOdds.Reward reward : CaseOdds.pool(tier, grade)) {
                    for (CaseOdds.Drop drop : reward.drops()) {
                        RESOLVED.computeIfAbsent(drop.id(), id -> {
                            Item item = Registries.ITEM.get(Identifier.of(id));
                            if (item == Items.AIR) {
                                TrapCraft.LOGGER.warn(
                                        "case drop {} is not an item; that line is dead", id);
                                return null;
                            }
                            return item;
                        });
                    }
                }
            }
        }
    }

    /** The stacks a reward hands over, in the order they are given. */
    public static List<ItemStack> stacksOf(CaseOdds.Reward reward) {
        resolve();
        List<ItemStack> stacks = new ArrayList<>();
        for (CaseOdds.Drop drop : reward.drops()) {
            Item item = RESOLVED.get(drop.id());
            if (item == null) {
                continue;
            }
            // Split by the item's own stack limit, or an elytra reward would
            // hand over one elytra with a count of six that vanishes the
            // moment anything validates it.
            int left = drop.count();
            while (left > 0) {
                int take = Math.min(left, item.getDefaultStack().getMaxCount());
                stacks.add(new ItemStack(item, take));
                left -= take;
            }
        }
        return stacks;
    }

    /**
     * The item shown on the reel for a reward.
     *
     * The first drop wearing the reward's name and grade, which is how a
     * skin looks in the game this is copying: a picture, a name, and a
     * coloured bar. The worth is on it too -- see {@link CaseOdds.Reward} for
     * why that number is written down rather than looked up.
     */
    public static ItemStack faceOf(CaseOdds.Reward reward, CaseOdds.Grade grade) {
        resolve();
        Item first = reward.drops().isEmpty() ? null : RESOLVED.get(reward.drops().get(0).id());
        ItemStack face = new ItemStack(first == null ? Items.PAPER : first);
        face.set(DataComponentTypes.CUSTOM_NAME,
                plain(reward.label()).formatted(colour(grade), Formatting.BOLD));
        face.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                plain(grade.title()).formatted(colour(grade)),
                plain("~" + reward.worth() + "e na półce").formatted(Formatting.DARK_GRAY))));
        return face;
    }

    // --- opening one ----------------------------------------------------------

    /** Draw a grade, then a line inside it. */
    public static CaseOdds.Reward roll(Random random, CaseOdds.Tier tier) {
        CaseOdds.Grade grade = CaseOdds.gradeFor(random.nextInt(CaseOdds.DRAWS));
        List<CaseOdds.Reward> pool = CaseOdds.pool(tier, grade);
        return pool.get(random.nextInt(pool.size()));
    }

    public static CaseOdds.Grade gradeOf(CaseOdds.Tier tier, CaseOdds.Reward reward) {
        for (CaseOdds.Grade grade : CaseOdds.Grade.values()) {
            if (CaseOdds.pool(tier, grade).contains(reward)) {
                return grade;
            }
        }
        return CaseOdds.Grade.MIL_SPEC;
    }

    /**
     * Hand the prize over, on the floor if the bag is full.
     *
     * The dead branch matters more than it looks. The reel runs for three and
     * a half seconds with the player standing still watching it, which is long
     * enough to be killed, and a corpse's inventory has already dropped -- so
     * putting a phantom case's prize into it would delete the most expensive
     * thing in the mod at the exact moment the player is least able to argue.
     * On the ground it is at least where they died.
     */
    public static void grant(ServerPlayerEntity player, CaseOdds.Reward reward) {
        for (ItemStack stack : stacksOf(reward)) {
            if (player.isAlive() && player.giveItemStack(stack)) {
                continue;
            }
            player.dropItem(stack, false);
        }
    }

    /**
     * Tell the room about a red or a gold.
     *
     * Only those two. Announcing every open would be a chat channel of other
     * people's blue drops within an hour, and the reason a gold is worth
     * announcing is precisely that the room has not seen one lately.
     */
    public static void announce(ServerPlayerEntity opener, CaseOdds.Tier tier,
                                CaseOdds.Reward reward, CaseOdds.Grade grade) {
        if (grade != CaseOdds.Grade.COVERT && grade != CaseOdds.Grade.EXOTIC) {
            return;
        }
        MinecraftServer server = opener.getServer();
        if (server == null) {
            return;
        }
        Text line = plain(grade == CaseOdds.Grade.EXOTIC ? "ZŁOTO  " : "SKRZYNIA  ")
                .formatted(colour(grade), Formatting.BOLD)
                .append(plain(opener.getNameForScoreboard()).formatted(Formatting.WHITE))
                .append(plain(" wyciągnął ").formatted(Formatting.GRAY))
                .append(plain(reward.label()).formatted(colour(grade), Formatting.BOLD))
                .append(plain(" — ").formatted(Formatting.DARK_GRAY))
                .append(name(tier.caseKey()).formatted(Formatting.GRAY));
        for (ServerPlayerEntity watcher : server.getPlayerManager().getPlayerList()) {
            watcher.sendMessage(line, false);
            watcher.playSoundToPlayer(
                    grade == CaseOdds.Grade.EXOTIC
                            ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
                            : SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    SoundCategory.PLAYERS, 0.5F, 1.0F);
        }
    }

    static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    /**
     * An item's own name, out of the language file.
     *
     * The only place the Polish for a case or key exists is the lang entry
     * gen_assets.py writes, so every sentence that mentions one has to come
     * back here for it. Un-italicised for the same reason as
     * {@link #plain}: lore and item names inherit italics otherwise.
     */
    static MutableText name(String translationKey) {
        return Text.translatable(translationKey).styled(style -> style.withItalic(false));
    }

    // --- where they come from -------------------------------------------------

    /**
     * Which chests carry which key, worst to best.
     *
     * The ladder is the game's own idea of how far from spawn you are: a
     * village chest is a walk, an end city is an expedition. Keys turn up in
     * all four bands so that exploring is a real route to opening cases and
     * not just a way to accumulate boxes you can't afford to unlock -- which
     * is the failure mode of the game this copies, and the one thing about it
     * worth fixing.
     */
    private static final Map<CaseOdds.Tier, Set<String>> KEY_CHESTS = new EnumMap<>(Map.of(
            CaseOdds.Tier.STREET, Set.of(
                    "village/village_plains_house", "village/village_savanna_house",
                    "village/village_desert_house", "village/village_taiga_house",
                    "village/village_snowy_house", "village/village_weaponsmith",
                    "abandoned_mineshaft", "simple_dungeon", "shipwreck_supply",
                    "igloo_chest", "ruined_portal", "underwater_ruin_big"),
            CaseOdds.Tier.DOCKS, Set.of(
                    "desert_pyramid", "jungle_temple", "buried_treasure",
                    "shipwreck_treasure", "stronghold_corridor", "stronghold_crossing",
                    "stronghold_library", "trial_chambers/supply",
                    "trial_chambers/corridor"),
            CaseOdds.Tier.CARTEL, Set.of(
                    "nether_bridge", "bastion_other", "bastion_hoglin_stable",
                    "bastion_bridge", "pillager_outpost", "woodland_mansion",
                    "trial_chambers/reward"),
            CaseOdds.Tier.PHANTOM, Set.of(
                    "end_city_treasure", "ancient_city", "ancient_city_ice_box",
                    "bastion_treasure", "trial_chambers/reward_ominous")));

    /**
     * How often a key is in one, per tier.
     *
     * Falling with the tier, and not by much: a phantom key is 22,000e on the
     * shelf and 2.5% of an end city chest, which is the trade the whole
     * feature is built on. Somebody who explores properly opens the big cases
     * for nothing; somebody who farms emeralds buys their way in. Both are
     * meant to work.
     */
    private static final Map<CaseOdds.Tier, Float> KEY_CHANCE = new EnumMap<>(Map.of(
            CaseOdds.Tier.STREET, 0.05F,
            CaseOdds.Tier.DOCKS, 0.04F,
            CaseOdds.Tier.CARTEL, 0.03F,
            CaseOdds.Tier.PHANTOM, 0.025F));

    /**
     * And the cases themselves, in the same chests but commoner.
     *
     * Three times the key rate, because a case without a key is the thing you
     * are supposed to end up holding. A player whose box count matches their
     * key count has no decision to make.
     */
    private static final float CASE_MULTIPLIER = 3.0F;

    /** What a kill is worth, for the mobs worth more than the default. */
    private record Bounty(CaseOdds.Tier tier, float chance) {
    }

    /**
     * Cases off things that fight back.
     *
     * This is the "drop for playing" half, and the list is the game's own
     * difficulty curve: a blaze is an errand, a warden is an evening. The
     * three at 100% are the fights nobody has by accident -- if you killed a
     * wither you meant to, and a phantom case is a fair receipt for it.
     *
     * Everything else hostile falls through to {@link #COMMON_KILL}, which is
     * low on purpose: at 1.2% a night of mob farming is a couple of street
     * cases, not a stack of them.
     */
    private static final Map<String, Bounty> KILLS = Map.ofEntries(
            Map.entry("minecraft:warden", new Bounty(CaseOdds.Tier.PHANTOM, 1.0F)),
            Map.entry("minecraft:wither", new Bounty(CaseOdds.Tier.PHANTOM, 1.0F)),
            Map.entry("minecraft:ender_dragon", new Bounty(CaseOdds.Tier.PHANTOM, 1.0F)),
            Map.entry("minecraft:elder_guardian", new Bounty(CaseOdds.Tier.CARTEL, 0.25F)),
            Map.entry("minecraft:evoker", new Bounty(CaseOdds.Tier.CARTEL, 0.08F)),
            Map.entry("minecraft:ravager", new Bounty(CaseOdds.Tier.DOCKS, 0.06F)),
            Map.entry("minecraft:piglin_brute", new Bounty(CaseOdds.Tier.DOCKS, 0.05F)),
            Map.entry("minecraft:shulker", new Bounty(CaseOdds.Tier.DOCKS, 0.03F)),
            Map.entry("minecraft:vindicator", new Bounty(CaseOdds.Tier.DOCKS, 0.03F)),
            Map.entry("minecraft:wither_skeleton", new Bounty(CaseOdds.Tier.DOCKS, 0.02F)),
            Map.entry("minecraft:blaze", new Bounty(CaseOdds.Tier.STREET, 0.03F)));

    private static final Bounty COMMON_KILL = new Bounty(CaseOdds.Tier.STREET, 0.012F);

    public static void register() {
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            // Vanilla's own tables only -- same reasoning as TrapLoot: without
            // this we would also inject into datapack tables that happen to
            // share an id, and the pack ships several.
            if (!source.isBuiltin()) {
                return;
            }
            for (CaseOdds.Tier tier : CaseOdds.Tier.values()) {
                if (!carries(tier, key)) {
                    continue;
                }
                float chance = KEY_CHANCE.get(tier);
                builder.pool(one(TrapContent.KEYS.get(tier), chance));
                builder.pool(one(TrapContent.CASES.get(tier),
                        Math.min(1.0F, chance * CASE_MULTIPLIER)));
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (!(damage.getAttacker() instanceof ServerPlayerEntity killer)) {
                return;
            }
            Bounty bounty = bountyFor(entity);
            if (bounty == null || killer.getRandom().nextFloat() >= bounty.chance()) {
                return;
            }
            drop(killer, bounty.tier());
        });
    }

    private static boolean carries(CaseOdds.Tier tier, RegistryKey<LootTable> key) {
        for (String path : KEY_CHESTS.get(tier)) {
            if (key.getValue().equals(Identifier.ofVanilla("chests/" + path))) {
                return true;
            }
        }
        return false;
    }

    private static LootPool.Builder one(Item item, float chance) {
        return LootPool.builder()
                .rolls(ConstantLootNumberProvider.create(1))
                .conditionally(RandomChanceLootCondition.builder(chance))
                .with(ItemEntry.builder(item)
                        .apply(SetCountLootFunction.builder(ConstantLootNumberProvider.create(1))));
    }

    /**
     * What this corpse owes, or null for nothing.
     *
     * The named list is checked by id BEFORE the hostile test, because the
     * ender dragon is not a {@link HostileEntity} and would otherwise be the
     * one fight in the game that pays nothing.
     */
    private static Bounty bountyFor(LivingEntity entity) {
        Bounty named = KILLS.get(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
        if (named != null) {
            return named;
        }
        return entity instanceof HostileEntity ? COMMON_KILL : null;
    }

    /** Put a case in somebody's hands and make sure they noticed. */
    private static void drop(ServerPlayerEntity player, CaseOdds.Tier tier) {
        ItemStack box = new ItemStack(TrapContent.CASES.get(tier));
        if (!player.giveItemStack(box)) {
            player.dropItem(box, false);
        }
        player.sendMessage(plain("Wypadła ").formatted(Formatting.GRAY)
                .append(name(tier.caseKey()).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(plain(". Potrzebujesz klucza.").formatted(Formatting.GRAY)), true);
        player.playSoundToPlayer(SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS,
                0.7F, 0.7F);
    }
}
