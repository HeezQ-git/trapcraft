package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemGroupUtils;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.item.ItemGroup;
import net.minecraft.text.Text;
import net.minecraft.block.Block;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.type.ConsumableComponents;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.UseAction;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Every registry entry in the mod, plus the lookups the blocks need. */
public final class TrapContent {
    private TrapContent() {
    }

    private static final Map<Strain, Block> CROPS = new EnumMap<>(Strain.class);
    private static final Map<Strain, Item> SEEDS = new EnumMap<>(Strain.class);
    private static final Map<Strain, Item> RAW_BUDS = new EnumMap<>(Strain.class);
    private static final Map<Strain, Item> DRIED_BUDS = new EnumMap<>(Strain.class);
    private static final Map<Strain, Item> JOINTS = new EnumMap<>(Strain.class);
    private static final Map<Item, Strain> RAW_BUD_LOOKUP = new HashMap<>();
    private static final Map<Item, Strain> DRIED_BUD_LOOKUP = new HashMap<>();

    public static Block dryingRack;
    public static Item dryingRackItem;
    public static Block wildCannabis;
    public static Item hammer;
    public static Block cocaCrop;
    public static Item cocaSeeds;
    public static Item cocaLeaves;
    public static Item cocaPaste;
    public static Item cocaPowder;
    public static Block leafPress;
    public static Item leafPressItem;
    public static Block refiner;
    public static Item refinerItem;
    public static Item nerveTonic;
    public static Item ledger;
    public static Item wallet;
    public static Item casinoCard;
    public static Item burnerPhone;
    public static Block marketStall;
    public static Item marketStallItem;
    public static Block nightclub;
    public static Item nightclubItem;
    public static Block mailbox;
    public static Item mailboxItem;
    public static Block cityVault;
    public static Item cityVaultItem;
    public static Block hospital;
    public static Item hospitalItem;
    public static Block marketShelf;
    public static Item marketShelfItem;
    public static net.minecraft.block.entity.BlockEntityType<MarketShelfBlockEntity>
            marketShelfEntity;
    public static Block shopTill;
    public static Item shopTillItem;
    public static Item dirtyEmerald;
    public static Block dirtyEmeraldBlock;
    public static Item dirtyEmeraldBlockItem;
    public static Block laundry;
    public static Item laundryItem;
    public static Block slotMachine;
    public static Item slotMachineItem;
    public static Block roulette;
    public static Item rouletteItem;
    public static Block plinko;
    public static Item plinkoItem;
    public static Block climb;
    public static Item climbItem;
    public static Block toss;
    public static Item tossItem;
    public static Block blackjack;
    public static Item blackjackItem;
    public static Block scratch;
    public static Item scratchItem;
    public static Block casinoBar;
    public static Item casinoBarItem;
    public static RegistryEntry<StatusEffect> wiredEffect;
    public static Block poppyCrop;
    public static Item poppySeeds;
    public static Item poppyPod;
    public static Item rawOpium;
    public static Item morphineBase;
    public static Item heroin;
    public static Block scoringTable;
    public static Item scoringTableItem;
    public static Block washPot;
    public static Item washPotItem;
    public static Block acetylator;
    public static Item acetylatorItem;
    public static RegistryEntry<StatusEffect> nodEffect;
    public static RegistryEntry<StatusEffect> withdrawalEffect;
    public static Block bong;
    public static Item bongItem;
    public static Block gravityBong;
    public static Item gravityBongItem;
    public static RegistryEntry<StatusEffect> bakedEffect;
    public static RegistryEntry<StatusEffect> toleranceEffect;

    public static Block crop(Strain s) {
        return CROPS.get(s);
    }

    public static Item seeds(Strain s) {
        return SEEDS.get(s);
    }

    public static Item rawBud(Strain s) {
        return RAW_BUDS.get(s);
    }

    public static Item driedBud(Strain s) {
        return DRIED_BUDS.get(s);
    }

    public static Item joint(Strain s) {
        return JOINTS.get(s);
    }

    /** Null when the stack isn't a raw bud -- used by the rack to reject junk. */
    public static Strain strainOfRawBud(Item item) {
        return RAW_BUD_LOOKUP.get(item);
    }

    /** Whether this item is one that carries a quality grade. */
    public static boolean carriesQuality(Item item) {
        for (Strain strain : Strain.values()) {
            if (item == RAW_BUDS.get(strain) || item == DRIED_BUDS.get(strain)
                    || item == JOINTS.get(strain)) {
                return true;
            }
        }
        return false;
    }

    /** Null when the stack isn't a cured bud -- the bongs use this to reject junk. */
    public static Strain strainOfDriedBud(Item item) {
        return DRIED_BUD_LOOKUP.get(item);
    }

    // ------------------------------------------------------------ blends

    public static Item blendBudItem;
    public static Item blendJointItem;
    public static Block mixer;
    public static Item mixerItem;

    /** A bud stack carrying a mix, named and coloured after it. */
    public static ItemStack blendBud(Blend blend) {
        return describeBlend(new ItemStack(blendBudItem), blend, "Bud");
    }

    public static ItemStack blendJoint(Blend blend) {
        return describeBlend(new ItemStack(blendJointItem), blend, "Joint");
    }

    /**
     * Name, colour and lore all derive from the mix, so a blend is readable in
     * a chest without opening anything. The lore lists the parts because the
     * name only mentions the top two -- otherwise a four-way mix would be
     * indistinguishable from the two-way one that shares its leaders.
     */
    private static ItemStack describeBlend(ItemStack stack, Blend blend, String noun) {
        stack.set(TrapComponents.blend, blend);
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                Text.literal(blend.display() + " " + noun)
                        .styled(style -> style.withColor(blend.colour()).withItalic(false)));

        var lore = new java.util.ArrayList<Text>();
        StringBuilder parts = new StringBuilder();
        for (var entry : blend.shares().entrySet()) {
            if (!parts.isEmpty()) {
                parts.append(" + ");
            }
            parts.append(entry.getKey().display());
            int count = Math.round(entry.getValue() * blend.parts().size());
            if (count > 1) {
                parts.append(" x").append(count);
            }
        }
        lore.add(Text.literal(parts.toString())
                .formatted(net.minecraft.util.Formatting.GRAY)
                .styled(style -> style.withItalic(false)));
        lore.add(Text.literal(blend.quality().display() + " grade  ·  "
                        + String.format("%.2fx", blend.potency()))
                .formatted(blend.quality().colour())
                .styled(style -> style.withItalic(false)));
        if (blend.named() != null) {
            lore.add(Text.literal("Known blend")
                    .formatted(net.minecraft.util.Formatting.GOLD)
                    .styled(style -> style.withItalic(false)));
        }
        stack.set(net.minecraft.component.DataComponentTypes.LORE,
                new net.minecraft.component.type.LoreComponent(lore));
        return stack;
    }

    /**
     * A blend hit. Same shape as {@link #hit} but the effects come off the mix
     * rather than a single strain, and the exact recipe is pushed to the client
     * so the visuals can be built from the actual colours involved.
     */
    public static void blendHit(ServerWorld world, net.minecraft.entity.player.PlayerEntity player,
                                Blend blend, float methodPotency, int extraTolerance) {
        var existing = player.getStatusEffect(toleranceEffect);
        int level = existing == null ? 0 : existing.getAmplifier() + 1;
        float damping = ToleranceStatusEffect.multiplier(level);

        for (var effect : blend.effects(bakedEffect)) {
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    effect.getEffectType(),
                    Math.max(20, Math.round(effect.getDuration() * damping * methodPotency)),
                    effect.getAmplifier(), effect.isAmbient(), effect.shouldShowParticles()));
        }
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                toleranceEffect, ToleranceStatusEffect.DURATION_TICKS,
                Math.min(level + extraTolerance, ToleranceStatusEffect.MAX_LEVEL), false, true));

        // A mix hooks you on its PARTS, weighted by how much of each went in.
        // Nobody gets addicted to "Trinity" -- they get addicted to the Kush in
        // it, which is why a blend is a way to spread a habit thin rather than
        // a way to dodge one.
        if (player instanceof net.minecraft.server.network.ServerPlayerEntity actor) {
            for (var share : blend.shares().entrySet()) {
                TrapAddiction.hit(actor, Drug.of(share.getKey()),
                        share.getValue() * blend.potency() * methodPotency);
            }
        }

        TrapNet.sendBlend(player, blend);
        TrapCough.maybe(world, player, blend.potency() * damping * methodPotency);
    }

    /**
     * One hit from a pipe. Shared by the bong and the tlok so the three
     * smoking methods differ only by their two numbers, not by three separate
     * copies of the effect logic drifting apart.
     */
    public static void hit(ServerWorld world, net.minecraft.entity.player.PlayerEntity player,
                           Strain strain, Quality grade, float potency, int extraTolerance) {
        var existing = player.getStatusEffect(toleranceEffect);
        int level = existing == null ? 0 : existing.getAmplifier() + 1;
        float damping = ToleranceStatusEffect.multiplier(level);

        for (var effect : strain.effects(bakedEffect, grade)) {
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    effect.getEffectType(),
                    Math.max(20, Math.round(effect.getDuration() * damping * potency)),
                    effect.getAmplifier(), effect.isAmbient(), effect.shouldShowParticles()));
        }
        // A harder hit costs more headroom, so the strong methods aren't
        // strictly better than a joint -- they burn your ceiling faster.
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                toleranceEffect, ToleranceStatusEffect.DURATION_TICKS,
                Math.min(level + extraTolerance, ToleranceStatusEffect.MAX_LEVEL), false, true));

        // Fed by the size of the hit, NOT by the damped effect. A tolerant
        // smoker gets less out of a bong rip and is still smoking a bong rip;
        // damping both would make tolerance a cure for addiction, which is
        // exactly backwards.
        if (player instanceof net.minecraft.server.network.ServerPlayerEntity actor) {
            TrapAddiction.hit(actor, Drug.of(strain), grade.potency() * potency);
        }

        TrapCough.maybe(world, player, grade.potency() * damping * potency);
    }

    public static ItemStack effectIcon() {
        return new ItemStack(JOINTS.get(Strain.PURP));
    }

    public static void register() {
        bakedEffect = Registry.registerReference(Registries.STATUS_EFFECT,
                RegistryKey.of(RegistryKeys.STATUS_EFFECT, TrapCraft.id("baked")),
                new BakedStatusEffect());
        toleranceEffect = Registry.registerReference(Registries.STATUS_EFFECT,
                RegistryKey.of(RegistryKeys.STATUS_EFFECT, TrapCraft.id("tolerance")),
                new ToleranceStatusEffect());

        for (Strain strain : Strain.values()) {
            Block crop = registerBlock("cannabis_crop_" + strain.id(),
                    settings -> new CannabisCropBlock(strain, settings),
                    AbstractBlock.Settings.create()
                            .noCollision()
                            .ticksRandomly()
                            .breakInstantly()
                            .sounds(BlockSoundGroup.CROP)
                            .pistonBehavior(net.minecraft.block.piston.PistonBehavior.DESTROY));
            CROPS.put(strain, crop);

            SEEDS.put(strain, registerItem("seeds_" + strain.id(),
                    (settings, model) -> new SeedsItem(crop, settings, model)));
            RAW_BUDS.put(strain, registerItem("raw_bud_" + strain.id(), TrapItem::new));
            DRIED_BUDS.put(strain, registerItem("dried_bud_" + strain.id(), TrapItem::new));
            JOINTS.put(strain, registerItem("joint_" + strain.id(),
                    (settings, model) -> new JointItem(strain, settings, model)));

            RAW_BUD_LOOKUP.put(RAW_BUDS.get(strain), strain);
            DRIED_BUD_LOOKUP.put(DRIED_BUDS.get(strain), strain);
        }

        dryingRack = registerBlock("drying_rack", DryingRackBlock::new,
                AbstractBlock.Settings.create()
                        .strength(2.0F)
                        .nonOpaque()
                        .sounds(BlockSoundGroup.WOOD));
        dryingRackItem = registerItem("drying_rack",
                (settings, model) -> new RackItem(dryingRack, settings, model));

        wildCannabis = registerBlock("wild_cannabis", WildCannabisBlock::new,
                AbstractBlock.Settings.create()
                        .noCollision()
                        .breakInstantly()
                        .sounds(BlockSoundGroup.GRASS)
                        .pistonBehavior(net.minecraft.block.piston.PistonBehavior.DESTROY));

        // Diamond-tier, and deliberately less durable than a diamond pickaxe:
        // you're getting nine blocks a swing, so it should wear faster.
        hammer = registerItem("miners_hammer", (settings, model) -> new HammerItem(
                settings.pickaxe(net.minecraft.item.ToolMaterial.DIAMOND, 2.0F, -3.0F)
                        .maxDamage(900)
                        // enchantable() only sets how GOOD the offers are; the
                        // table won't offer anything unless the item is also in
                        // the minecraft:enchantable/* tags (see gen_assets.py).
                        // Setting one without the other silently does nothing.
                        .enchantable(10)
                        .repairable(net.minecraft.item.Items.DIAMOND)
                        .rarity(net.minecraft.util.Rarity.RARE),
                model));

        registerCoca();
        registerPoppy();
        registerDevices();
        registerItemGroup();
        registerWorldgen();
    }

    /** The long line: grow -> score -> wash -> acetylate -> use. */
    private static void registerPoppy() {
        nodEffect = Registry.registerReference(Registries.STATUS_EFFECT,
                RegistryKey.of(RegistryKeys.STATUS_EFFECT, TrapCraft.id("nod")),
                new NodStatusEffect());
        withdrawalEffect = Registry.registerReference(Registries.STATUS_EFFECT,
                RegistryKey.of(RegistryKeys.STATUS_EFFECT, TrapCraft.id("withdrawal")),
                new WithdrawalStatusEffect());

        poppyCrop = registerBlock("poppy_crop", PoppyCropBlock::new,
                AbstractBlock.Settings.create()
                        .noCollision().ticksRandomly().breakInstantly()
                        .sounds(BlockSoundGroup.CROP)
                        .pistonBehavior(net.minecraft.block.piston.PistonBehavior.DESTROY));
        poppySeeds = registerItem("poppy_seeds",
                (settings, model) -> new SeedsItem(poppyCrop, settings, model));
        poppyPod = registerItem("poppy_pod", TrapItem::new);
        rawOpium = registerItem("raw_opium", TrapItem::new);
        morphineBase = registerItem("morphine_base", TrapItem::new);
        heroin = registerItem("heroin", HeroinItem::new);

        // Timber and blades: WOOD, so it places and breaks as what it is.
        scoringTable = registerBlock("scoring_table", ScoringTableBlock::new,
                AbstractBlock.Settings.create().strength(2.5F).sounds(BlockSoundGroup.WOOD));
        scoringTableItem = registerItem("scoring_table",
                (settings, model) -> new RackItem(scoringTable, settings, model));

        washPot = registerBlock("wash_pot", WashPotBlock::new,
                AbstractBlock.Settings.create().strength(3.0F).requiresTool()
                        .sounds(BlockSoundGroup.COPPER));
        washPotItem = registerItem("wash_pot",
                (settings, model) -> new RackItem(washPot, settings, model));

        acetylator = registerBlock("acetylator", AcetylatorBlock::new,
                AbstractBlock.Settings.create().strength(3.5F).requiresTool()
                        .sounds(BlockSoundGroup.METAL));
        acetylatorItem = registerItem("acetylator",
                (settings, model) -> new RackItem(acetylator, settings, model));
    }

    /** The two water pipes. Joint < bong < tlok, in setup cost and in payoff. */
    private static void registerDevices() {
        bong = registerBlock("bong", BongBlock::new,
                AbstractBlock.Settings.create().strength(0.6F).nonOpaque()
                        .sounds(BlockSoundGroup.GLASS));
        bongItem = registerItem("bong", (settings, model) -> new RackItem(bong, settings, model));

        gravityBong = registerBlock("gravity_bong", GravityBongBlock::new,
                AbstractBlock.Settings.create().strength(0.6F).nonOpaque()
                        .sounds(BlockSoundGroup.GLASS));
        gravityBongItem = registerItem("gravity_bong",
                (settings, model) -> new RackItem(gravityBong, settings, model));

        mixer = registerBlock("mixing_station", MixerBlock::new,
                AbstractBlock.Settings.create().strength(2.0F)
                        .sounds(BlockSoundGroup.WOOD));
        mixerItem = registerItem("mixing_station",
                (settings, model) -> new RackItem(mixer, settings, model));

        blendBudItem = registerItem("blend_bud", TrapItem::new);
        blendJointItem = registerItem("blend_joint",
                (settings, model) -> new BlendJointItem(settings, model));
    }

    /** The coca line: grow -> press -> refine -> use. */
    private static void registerCoca() {
        wiredEffect = Registry.registerReference(Registries.STATUS_EFFECT,
                RegistryKey.of(RegistryKeys.STATUS_EFFECT, TrapCraft.id("wired")),
                new WiredStatusEffect());

        cocaCrop = registerBlock("coca_crop", CocaCropBlock::new,
                AbstractBlock.Settings.create()
                        .noCollision().ticksRandomly().breakInstantly()
                        .sounds(BlockSoundGroup.CROP)
                        .pistonBehavior(net.minecraft.block.piston.PistonBehavior.DESTROY));
        nerveTonic = registerItem("nerve_tonic", NerveTonicItem::new);
        ledger = registerItem("ledger", (settings, model) ->
                new LedgerItem(settings.maxCount(1), model));
        // maxCount 1: the balance rides on the stack, and two stacked wallets
        // would merge into whichever balance won.
        wallet = registerItem("wallet", (settings, model) ->
                new WalletItem(settings.maxCount(1), model));
        // maxCount 1 because the casino id rides on the stack; fireproof
        // because it is a bearer instrument and losing a business to a lava
        // pool is a bug report, whereas losing one to a mugger is a story.
        casinoCard = registerItem("casino_card", (settings, model) ->
                new CasinoCardItem(settings.maxCount(1).fireproof(), model));
        // maxCount 1: rep rides on the stack, and stacking phones would merge
        // two different standings into whichever one won.
        burnerPhone = registerItem("burner_phone", (settings, model) ->
                new BurnerPhoneItem(settings.maxCount(1), model));

        marketStall = registerBlock("market_stall", MarketStallBlock::new,
                AbstractBlock.Settings.create().strength(2.5F).sounds(BlockSoundGroup.WOOD).nonOpaque());
        marketStallItem = registerItem("market_stall",
                (settings, model) -> new RackItem(marketStall, settings, model));

        // Ticks randomly so it can thump when there is somebody in -- a
        // Polymer block's client never knows it is there, so every ambient
        // effect has to be server-side and hung off a tick the block already
        // gets. See the note in NightclubBlock.randomTick.
        nightclub = registerBlock("nightclub", NightclubBlock::new,
                AbstractBlock.Settings.create().strength(2.5F)
                        .sounds(BlockSoundGroup.WOOD).ticksRandomly());
        nightclubItem = registerItem("nightclub",
                (settings, model) -> new RackItem(nightclub, settings, model));

        mailbox = registerBlock("mailbox", MailboxBlock::new,
                AbstractBlock.Settings.create().strength(1.5F)
                        .sounds(BlockSoundGroup.WOOD).nonOpaque());
        mailboxItem = registerItem("mailbox",
                (settings, model) -> new MailboxItem(mailbox, settings, model));

        cityVault = registerBlock("city_vault", CityVaultBlock::new,
                AbstractBlock.Settings.create().strength(6.0F).requiresTool()
                        .sounds(BlockSoundGroup.METAL));
        cityVaultItem = registerItem("city_vault",
                (settings, model) -> new RackItem(cityVault, settings, model));

        // Glass sounds and a light of its own: it is a lit sign over a door,
        // and a ward has to be lit anyway, so the block that registers one
        // pulls its own weight towards passing the inspection.
        hospital = registerBlock("hospital", HospitalBlock::new,
                AbstractBlock.Settings.create().strength(2.0F)
                        .sounds(BlockSoundGroup.GLASS).luminance(state -> 10));
        hospitalItem = registerItem("hospital",
                (settings, model) -> new RackItem(hospital, settings, model));

        marketShelf = registerBlock("market_shelf", MarketShelfBlock::new,
                AbstractBlock.Settings.create().strength(2.0F).sounds(BlockSoundGroup.WOOD));
        marketShelfItem = registerItem("market_shelf",
                (settings, model) -> new RackItem(marketShelf, settings, model));
        // A shelf holds its own stock, so it needs a block entity -- the only
        // one in this mod. Polymer is told it is server-side only, or a
        // vanilla client gets sent a block entity type it has never heard of.
        marketShelfEntity = Registry.register(Registries.BLOCK_ENTITY_TYPE,
                TrapCraft.id("market_shelf"),
                net.fabricmc.fabric.api.object.builder.v1.block.entity
                        .FabricBlockEntityTypeBuilder
                        .create(MarketShelfBlockEntity::new, marketShelf).build());
        eu.pb4.polymer.core.api.block.PolymerBlockUtils.registerBlockEntity(marketShelfEntity);

        shopTill = registerBlock("shop_till", ShopTillBlock::new,
                AbstractBlock.Settings.create().strength(2.5F).sounds(BlockSoundGroup.WOOD));
        shopTillItem = registerItem("shop_till",
                (settings, model) -> new RackItem(shopTill, settings, model));

        // Deliberately not fireproof and deliberately stackable: it is money,
        // it is meant to be awkward to hold, and burning a pocketful of it in
        // a lava pool is a story about laundering rather than a bug report.
        dirtyEmerald = registerItem("dirty_emerald", TrapItem::new);

        // Packs nine to a block like the real thing. Dirty money is meant to
        // be awkward because it has to be washed, not because a week's takings
        // is sixteen stacks to carry.
        dirtyEmeraldBlock = registerBlock("dirty_emerald_block", DirtyEmeraldBlock::new,
                AbstractBlock.Settings.create().strength(3.0F, 6.0F).requiresTool()
                        .sounds(BlockSoundGroup.METAL));
        dirtyEmeraldBlockItem = registerItem("dirty_emerald_block",
                (settings, model) -> new RackItem(dirtyEmeraldBlock, settings, model));

        laundry = registerBlock("laundry", LaundryBlock::new,
                AbstractBlock.Settings.create().strength(3.0F).requiresTool()
                        .sounds(BlockSoundGroup.METAL).nonOpaque());
        laundryItem = registerItem("laundry",
                (settings, model) -> new RackItem(laundry, settings, model));

        slotMachine = registerBlock("slot_machine", SlotMachineBlock::new,
                AbstractBlock.Settings.create().strength(3.0F).sounds(BlockSoundGroup.METAL).nonOpaque());
        slotMachineItem = registerItem("slot_machine",
                (settings, model) -> new RackItem(slotMachine, settings, model));

        roulette = registerBlock("roulette", RouletteBlock::new,
                AbstractBlock.Settings.create().strength(2.5F).sounds(BlockSoundGroup.WOOD).nonOpaque());
        rouletteItem = registerItem("roulette",
                (settings, model) -> new RackItem(roulette, settings, model));

        plinko = registerBlock("plinko", PlinkoBlock::new,
                AbstractBlock.Settings.create().strength(2.5F)
                        .sounds(BlockSoundGroup.WOOD).nonOpaque());
        plinkoItem = registerItem("plinko",
                (settings, model) -> new RackItem(plinko, settings, model));

        climb = registerBlock("climb", ClimbBlock::new,
                AbstractBlock.Settings.create().strength(3.5F).sounds(BlockSoundGroup.METAL));
        climbItem = registerItem("climb",
                (settings, model) -> new RackItem(climb, settings, model));

        toss = registerBlock("toss", TossBlock::new,
                AbstractBlock.Settings.create().strength(2.0F).sounds(BlockSoundGroup.WOOD));
        tossItem = registerItem("toss",
                (settings, model) -> new RackItem(toss, settings, model));

        blackjack = registerBlock("blackjack", BlackjackBlock::new,
                AbstractBlock.Settings.create().strength(2.5F).sounds(BlockSoundGroup.WOOD));
        blackjackItem = registerItem("blackjack",
                (settings, model) -> new RackItem(blackjack, settings, model));

        scratch = registerBlock("scratch", ScratchBlock::new,
                AbstractBlock.Settings.create().strength(2.0F).sounds(BlockSoundGroup.WOOD));
        scratchItem = registerItem("scratch",
                (settings, model) -> new RackItem(scratch, settings, model));

        casinoBar = registerBlock("casino_bar", BarBlock::new,
                AbstractBlock.Settings.create().strength(2.5F)
                        .sounds(BlockSoundGroup.WOOD).nonOpaque());
        casinoBarItem = registerItem("casino_bar",
                (settings, model) -> new RackItem(casinoBar, settings, model));

        cocaSeeds = registerItem("coca_seeds",
                (settings, model) -> new SeedsItem(cocaCrop, settings, model));
        cocaLeaves = registerItem("coca_leaves", TrapItem::new);
        cocaPaste = registerItem("coca_paste", TrapItem::new);
        cocaPowder = registerItem("coca_powder", PowderItem::new);

        leafPress = registerBlock("leaf_press", LeafPressBlock::new,
                AbstractBlock.Settings.create().strength(2.5F).sounds(BlockSoundGroup.WOOD).nonOpaque());
        leafPressItem = registerItem("leaf_press",
                (settings, model) -> new RackItem(leafPress, settings, model));

        refiner = registerBlock("refiner", RefinerBlock::new,
                AbstractBlock.Settings.create().strength(3.0F).sounds(BlockSoundGroup.COPPER));
        refinerItem = registerItem("refiner",
                (settings, model) -> new RackItem(refiner, settings, model));
    }

    /** Wild patches in the biomes cannabis would plausibly like. */
    private static void registerWorldgen() {
        BiomeModifications.addFeature(
                BiomeSelectors.includeByKey(
                        BiomeKeys.PLAINS, BiomeKeys.SUNFLOWER_PLAINS, BiomeKeys.MEADOW,
                        BiomeKeys.SAVANNA, BiomeKeys.SAVANNA_PLATEAU,
                        BiomeKeys.JUNGLE, BiomeKeys.SPARSE_JUNGLE),
                GenerationStep.Feature.VEGETAL_DECORATION,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, TrapCraft.id("wild_cannabis")));
    }

    /**
     * A Polymer item group: a real creative tab that vanilla clients render,
     * without the client having the mod. Ordered by stage of the loop rather
     * than by strain, so the tab reads seeds -> fresh -> cured -> joint.
     */
    /**
     * A creative-tab stack that carries a grade, like every real one does.
     *
     * Bare `entries.add(item)` hands out a stack with NO quality component,
     * which looks identical but behaves differently everywhere the grade is
     * read: dealer offers match on the component being present, so a creative
     * joint could not be sold to anybody. Harvested product always carries a
     * grade, so this makes the tab agree with the game.
     */
    private static ItemStack graded(Item item) {
        return TrapComponents.apply(new ItemStack(item), Quality.MIDS);
    }

    private static void registerItemGroup() {
        ItemGroup group = PolymerItemGroupUtils.builder()
                .displayName(Text.literal("TrapCraft"))
                .icon(() -> new ItemStack(JOINTS.get(Strain.PURP)))
                .entries((context, entries) -> {
                    for (Strain strain : Strain.values()) {
                        entries.add(SEEDS.get(strain));
                    }
                    for (Strain strain : Strain.values()) {
                        entries.add(graded(RAW_BUDS.get(strain)));
                    }
                    for (Strain strain : Strain.values()) {
                        entries.add(graded(DRIED_BUDS.get(strain)));
                    }
                    for (Strain strain : Strain.values()) {
                        entries.add(graded(JOINTS.get(strain)));
                    }
                    entries.add(dryingRackItem);
                    // Coca line, in chain order like the weed items above.
                    entries.add(cocaSeeds);
                    entries.add(cocaLeaves);
                    entries.add(cocaPaste);
                    entries.add(cocaPowder);
                    entries.add(leafPressItem);
                    entries.add(refinerItem);
                    // Poppy line, chain order again.
                    entries.add(poppySeeds);
                    entries.add(poppyPod);
                    entries.add(rawOpium);
                    entries.add(morphineBase);
                    // Graded, for the same reason the buds are: a bare stack
                    // has no purity on it and no dealer would touch it.
                    entries.add(TrapComponents.applyPurity(new ItemStack(heroin), Purity.STREET));
                    entries.add(scoringTableItem);
                    entries.add(washPotItem);
                    entries.add(acetylatorItem);
                    entries.add(bongItem);
                    entries.add(gravityBongItem);
                    entries.add(mixerItem);
                    // One worked example of a blend, so the mechanic is
                    // discoverable from the creative tab rather than only from
                    // the guide. Trinity is the three-base-strain mix.
                    entries.add(blendBud(new Blend(
                            java.util.List.of(Strain.KUSH, Strain.HAZE, Strain.PURP),
                            Quality.LOUD.index())));
                    entries.add(blendJoint(new Blend(
                            java.util.List.of(Strain.KUSH, Strain.HAZE, Strain.PURP),
                            Quality.LOUD.index())));
                    entries.add(nerveTonic);
                    entries.add(ledger);
                    entries.add(wallet());
                    entries.add(burnerPhone);
                    entries.add(marketStallItem);
                    entries.add(mailboxItem);
                    entries.add(cityVaultItem);
                    entries.add(hospitalItem);
                    entries.add(shopTillItem);
                    entries.add(marketShelfItem);
                    entries.add(laundryItem);
                    entries.add(dirtyEmerald);
                    entries.add(dirtyEmeraldBlockItem);
                    entries.add(slotMachineItem);
                    entries.add(rouletteItem);
                    entries.add(plinkoItem);
                    entries.add(climbItem);
                    entries.add(tossItem);
                    entries.add(blackjackItem);
                    entries.add(scratchItem);
                    entries.add(casinoBarItem);
                    entries.add(casinoCard());
                    entries.add(hammer);
                })
                .build();
        PolymerItemGroupUtils.registerPolymerItemGroup(TrapCraft.id("main"), group);
    }

    // ------------------------------------------------------------ helpers

    private static RegistryEntry<net.minecraft.sound.SoundEvent> sound(net.minecraft.sound.SoundEvent event) {
        return Registries.SOUND_EVENT.getEntry(event);
    }

    private interface BlockFactory {
        Block create(AbstractBlock.Settings settings);
    }

    private interface ItemFactory {
        Item create(Item.Settings settings, Identifier model);
    }

    private static Block registerBlock(String path, BlockFactory factory, AbstractBlock.Settings settings) {
        RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, TrapCraft.id(path));
        return Registry.register(Registries.BLOCK, key, factory.create(settings.registryKey(key)));
    }

    /**
     * A fresh, empty wallet with its balance already written on it.
     *
     * A bare `new ItemStack(wallet)` has no balance component and so reads as
     * a blank leather pouch with no lore -- same trap as the graded buds.
     */
    public static ItemStack wallet() {
        ItemStack stack = new ItemStack(wallet);
        WalletItem.setBalance(stack, 0);
        return stack;
    }

    /** A blank licence, stamped so the creative tab shows it as one. */
    public static ItemStack casinoCard() {
        ItemStack stack = new ItemStack(casinoCard);
        CasinoCardItem.restamp(stack, null);
        return stack;
    }

    /**
     * Is this the sort of thing you'd rather the authorities didn't find?
     *
     * Product only -- buds, joints, mixes, powder and the coca line. Seeds and
     * equipment are legal to own as far as this mod is concerned, which is
     * also what stops a raid emptying your entire farming chest.
     */
    public static boolean isContraband(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item == blendBudItem || item == blendJointItem
                || item == cocaLeaves || item == cocaPaste || item == cocaPowder
                // The whole poppy line bar the seeds, including the pods --
                // unlike coca leaves there is nothing else a pod is for, so a
                // sack of them found in your chest is not innocent.
                || item == poppyPod || item == rawOpium || item == morphineBase
                || item == heroin) {
            return true;
        }
        return RAW_BUDS.containsValue(item)
                || DRIED_BUDS.containsValue(item)
                || JOINTS.containsValue(item);
    }

    private static Item registerItem(String path, ItemFactory factory) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, TrapCraft.id(path));
        Identifier model = TrapCraft.id(path);
        return Registry.register(Registries.ITEM, key,
                factory.create(new Item.Settings().registryKey(key), model));
    }

    // ------------------------------------------------------------ item types
    // All of these exist only to carry a Polymer base item + model path. The
    // base item decides how a vanilla client treats the stack in inventories.

    static class TrapItem extends Item implements PolymerItem {
        private final Identifier model;

        TrapItem(Settings settings, Identifier model) {
            super(settings);
            this.model = model;
        }

        @Override
        public Item getPolymerItem(ItemStack stack, PacketContext context) {
            return BASE;
        }

        @Override
        public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
            return model;
        }
    }

    /**
     * The item a vanilla client is told it's holding.
     *
     * Deliberately WHEAT (a plain item) rather than WHEAT_SEEDS (a BlockItem).
     * With a placeable base, the client optimistically predicts placing
     * minecraft:wheat where you clicked, then the server's real block arrives
     * and it visibly snaps -- the plant jumps. A non-placeable base means the
     * client predicts nothing and simply draws what the server sends.
     *
     * Costs one round trip of latency before the plant appears, which is
     * invisible next to the wrong block flashing first.
     */
    private static final Item BASE = Items.WHEAT;

    static class SeedsItem extends BlockItem implements PolymerItem {
        private final Identifier model;

        SeedsItem(Block block, Settings settings, Identifier model) {
            super(block, settings);
            this.model = model;
        }


        @Override
        public Item getPolymerItem(ItemStack stack, PacketContext context) {
            return BASE;
        }

        @Override
        public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
            return model;
        }
    }

    /**
     * Finished product. Fast, strong, no hunger cost -- and then the crash,
     * which is where the bill comes due. Purity scales the up and the down
     * together, so a Pure hit is a better high AND a worse comedown.
     */
    /**
     * The counterplay to Paranoia, and deliberately cheap.
     *
     * A scare mechanic with no off-switch is one people turn off at the config
     * level instead of playing around. Honey, sugar and a flower are all
     * first-day materials on purpose: the answer to being terrified must never
     * be gated behind the operation that terrifies you.
     */
    static class NerveTonicItem extends TrapItem {
        /** How long it holds the meter down after you drink it. */
        public static final int CALM_TICKS = 90 * 20;

        NerveTonicItem(Settings settings, Identifier model) {
            super(settings.food(
                            new FoodComponent.Builder().nutrition(1).saturationModifier(0.2F)
                                    .alwaysEdible().build(),
                            ConsumableComponents.food()
                                    .consumeSeconds(1.6F)
                                    .useAction(UseAction.DRINK)
                                    // ENTITY_GENERIC_DRINK is already a
                                    // RegistryEntry; ENTITY_PLAYER_BREATH is a bare
                                    // SoundEvent and needs the wrapper. SoundEvents
                                    // is genuinely inconsistent about this.
                                    .sound(SoundEvents.ENTITY_GENERIC_DRINK)
                                    .finishSound(sound(SoundEvents.ENTITY_PLAYER_BREATH))
                                    .build())
                            .maxCount(8),
                    model);
        }

        @Override
        public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
            ItemStack result = super.finishUsing(stack, world, user);
            if (world instanceof ServerWorld server
                    && user instanceof net.minecraft.server.network.ServerPlayerEntity player) {
                TrapParanoia.calm(player, CALM_TICKS);
                // Second job, added with the habit: it holds the shakes off for
                // as long as it holds the fear off. It does NOT touch the meter
                // -- see TrapAddiction.medicate for why that line matters.
                TrapAddiction.medicate(player, CALM_TICKS);

                // The relief has to be legible or the tonic feels like it did
                // nothing -- the meter it clears is invisible by design.
                server.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                        player.getX(), player.getEyeY() - 0.2, player.getZ(),
                        16, 0.35, 0.4, 0.35, 0.01);
                // Two notes falling rather than one: a single chime reads as a
                // UI blip, a descending pair reads as tension leaving.
                server.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS,
                        0.7F, 1.2F);
                server.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS,
                        0.5F, 0.8F);
            }
            return result;
        }
    }

    static class PowderItem extends TrapItem {
        PowderItem(Settings settings, Identifier model) {
            super(settings.food(
                            new FoodComponent.Builder().nutrition(0).saturationModifier(0.0F)
                                    .alwaysEdible().build(),
                            ConsumableComponents.food()
                                    .consumeSeconds(1.2F)
                                    .useAction(UseAction.TOOT_HORN)
                                    .sound(sound(SoundEvents.ENTITY_PLAYER_BREATH))
                                    .finishSound(sound(SoundEvents.ENTITY_PLAYER_BREATH))
                                    .consumeParticles(false)
                                    .build())
                            .maxCount(16),
                    model);
        }

        @Override
        public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
            Purity grade = TrapComponents.getPurity(stack);
            ItemStack result = super.finishUsing(stack, world, user);
            if (world instanceof ServerWorld server) {
                float potency = grade.potency();
                int ticks = Math.round(70 * 20 * potency);
                int amplifier = Math.min(2, grade.index());

                user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        wiredEffect, ticks, amplifier, false, true));
                add(user, net.minecraft.entity.effect.StatusEffects.SPEED, ticks, amplifier);
                add(user, net.minecraft.entity.effect.StatusEffects.HASTE, ticks, amplifier);
                add(user, net.minecraft.entity.effect.StatusEffects.STRENGTH, ticks, 0);

                server.spawnParticles(ParticleTypes.END_ROD,
                        user.getX(), user.getEyeY(), user.getZ(), 12, 0.3, 0.3, 0.3, 0.03);

                if (user instanceof net.minecraft.server.network.ServerPlayerEntity actor) {
                    TrapAddiction.hit(actor, Drug.COKE, potency);
                }
            }
            return result;
        }

        private static void add(LivingEntity user, RegistryEntry<StatusEffect> effect,
                                int ticks, int amplifier) {
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    effect, ticks, amplifier, false, true));
        }
    }

    /**
     * The needle.
     *
     * Everything about how it plays is a deliberate inversion of the powder:
     * powder is fast, strong and free until the crash; this is slow, blissful,
     * and free until the bill arrives days later on the addiction meter. See
     * {@link NodStatusEffect} for the three-way split that makes each line feel
     * like a different drug rather than a different number.
     *
     * The one thing that CAN hurt you here and now is stacking doses. Take a
     * second while the first is still riding and you go over -- which is the
     * only place in the mod where using something is dangerous rather than
     * expensive, and heroin is the right thing for that to be true of.
     */
    static class HeroinItem extends TrapItem {
        /** Nod, at a Street purity. Purity scales it from there. */
        private static final int BASE_SECONDS = 100;
        /**
         * A second dose lands badly while this much of the first is left.
         *
         * Fraction rather than a flat window on purpose: a Pure hit nods you
         * for longer, so the danger zone after one is longer too.
         */
        private static final float OVERDOSE_ABOVE = 0.55F;

        HeroinItem(Settings settings, Identifier model) {
            super(settings.food(
                            new FoodComponent.Builder().nutrition(0).saturationModifier(0.0F)
                                    .alwaysEdible().build(),
                            ConsumableComponents.food()
                                    .consumeSeconds(2.6F)
                                    // BOW holds the item up in front of the
                                    // chest with the off hand steadying it,
                                    // which is as close to a syringe as vanilla
                                    // gets and reads correctly on a client with
                                    // no mod at all.
                                    .useAction(UseAction.BOW)
                                    .sound(sound(SoundEvents.ENTITY_PLAYER_BREATH))
                                    .finishSound(sound(SoundEvents.ENTITY_PLAYER_BREATH))
                                    .consumeParticles(false)
                                    .build())
                            .maxCount(16),
                    model);
        }

        /** Fires on START, so the gesture runs alongside the 2.6s. See JointItem. */
        @Override
        public ActionResult use(World world, net.minecraft.entity.player.PlayerEntity user,
                                net.minecraft.util.Hand hand) {
            ActionResult result = super.use(world, user, hand);
            if (!world.isClient && result.isAccepted()) {
                TrapNet.play(user, TrapNet.SHOOT_UP);
            }
            return result;
        }

        @Override
        public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
            // Read before super shrinks it -- on the last one it's air by now.
            Purity grade = TrapComponents.getPurity(stack);
            var riding = user.getStatusEffect(nodEffect);
            ItemStack result = super.finishUsing(stack, world, user);
            if (!(world instanceof ServerWorld server)) {
                return result;
            }

            float potency = grade.potency();
            int ticks = Math.round(BASE_SECONDS * 20 * potency);

            if (riding != null && riding.getDuration() > ticks * OVERDOSE_ABOVE) {
                overdose(server, user, grade);
                return result;
            }

            int amplifier = Math.min(2, grade.index());
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    nodEffect, ticks, amplifier, false, true));
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.RESISTANCE,
                    ticks, Math.min(1, amplifier), false, true));
            // The price of the bliss, paid in usefulness. This is what stops
            // the strongest drug in the mod being the best combat buff in it.
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                    ticks, 1 + Math.min(1, amplifier), false, true));
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE,
                    ticks, 1, false, true));

            server.spawnParticles(ParticleTypes.FALLING_HONEY,
                    user.getX(), user.getEyeY(), user.getZ(), 14, 0.3, 0.35, 0.3, 0.01);
            server.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.35F, 0.6F);

            if (user instanceof net.minecraft.server.network.ServerPlayerEntity actor) {
                TrapAddiction.hit(actor, Drug.DOPE, potency);
            }
            return result;
        }

        /**
         * Going over.
         *
         * Deliberately survivable: it takes you to the floor rather than
         * through it, because a mechanic that scatters your inventory into a
         * lava-adjacent cave is a griefing mechanic dressed as a story. What it
         * costs is the next two minutes, which is plenty.
         */
        private static void overdose(ServerWorld world, LivingEntity user, Purity grade) {
            float damage = Math.max(0.0F, user.getHealth() - 3.0F);
            if (damage > 0.0F) {
                user.damage(world, world.getDamageSources().magic(), damage);
            }
            int ticks = (60 + grade.index() * 20) * 20;
            for (var effect : java.util.List.of(
                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                    net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE,
                    net.minecraft.entity.effect.StatusEffects.WEAKNESS)) {
                user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        effect, ticks, 2, false, true));
            }
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.BLINDNESS, ticks / 3, 0, false, true));
            user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.NAUSEA, ticks / 2, 0, false, true));
            user.removeStatusEffect(nodEffect);

            world.spawnParticles(ParticleTypes.LARGE_SMOKE,
                    user.getX(), user.getEyeY(), user.getZ(), 30, 0.4, 0.5, 0.4, 0.02);
            world.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0F, 0.5F);
            if (user instanceof net.minecraft.server.network.ServerPlayerEntity actor) {
                actor.sendMessage(Text.literal("Too much, too soon.")
                        .formatted(net.minecraft.util.Formatting.DARK_RED,
                                net.minecraft.util.Formatting.BOLD), false);
                // Still counts. Going over is not a lucky escape from the
                // meter, it is the meter's whole argument.
                TrapAddiction.hit(actor, Drug.DOPE, grade.potency());
            }
        }
    }

    static class RackItem extends BlockItem implements PolymerItem {
        private final Identifier model;

        RackItem(Block block, Settings settings, Identifier model) {
            super(block, settings);
            this.model = model;
        }

        @Override
        public Item getPolymerItem(ItemStack stack, PacketContext context) {
            // Same reason as BASE: a barrel base made the client flash an
            // actual barrel before the rack arrived. Sticks aren't placeable.
            return Items.STICK;
        }

        /**
         * Play the place sound to the person doing the placing.
         *
         * Vanilla's place path calls world.playSound(player, ...), which
         * EXCLUDES that player on the assumption their client predicts the
         * sound locally. It can't here: getPolymerItem hands the client a
         * stick, and a stick doesn't place anything, so the client predicts
         * nothing at all. Bystanders heard the block go down correctly and the
         * person holding it heard silence.
         *
         * Sent only to the placer, because vanilla already broadcast it to
         * everyone else -- doing it for all would double the sound for them.
         */
        @Override
        public ActionResult place(ItemPlacementContext context) {
            ActionResult result = super.place(context);
            if (result.isAccepted() && context.getPlayer()
                    instanceof net.minecraft.server.network.ServerPlayerEntity player) {
                BlockSoundGroup group = getBlock().getDefaultState().getSoundGroup();
                TrapPhantom.sound(player, Vec3d.ofCenter(context.getBlockPos()),
                        group.getPlaceSound(), SoundCategory.BLOCKS,
                        (group.getVolume() + 1.0F) / 2.0F, group.getPitch() * 0.8F);
            }
            return result;
        }

        @Override
        public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
            return model;
        }
    }

    /**
     * A rolled blend. Same smoking flow as a plain joint, but the strain comes
     * off the stack's component instead of being baked into the item type --
     * that's the whole reason blends can be open-ended.
     *
     * Blends are joint-only by design. The bong and tlok keep their loaded bud
     * in blockstate properties and have no BlockEntity to put arbitrary data
     * in, so a mix physically cannot ride in one. Giving two blocks a
     * BlockEntity apiece to change that is a bigger job than it's worth.
     */
    static class BlendJointItem extends TrapItem {
        BlendJointItem(Settings settings, Identifier model) {
            super(settings.food(
                            new FoodComponent.Builder().nutrition(1).saturationModifier(0.1F)
                                    .alwaysEdible().build(),
                            ConsumableComponents.food()
                                    .consumeSeconds(3.2F)
                                    .useAction(UseAction.TOOT_HORN)
                                    .sound(sound(SoundEvents.BLOCK_CAMPFIRE_CRACKLE))
                                    .finishSound(sound(SoundEvents.BLOCK_FIRE_EXTINGUISH))
                                    .consumeParticles(false)
                                    .build())
                            .maxCount(16),
                    model);
        }



        /** Per-tick feedback so the 3.2 seconds isn't dead air. See TrapSmoke. */
        @Override
        public void usageTick(World world, LivingEntity user, ItemStack stack, int remaining) {
            super.usageTick(world, user, stack, remaining);
            TrapSmoke.usageTick(world, user, remaining, getMaxUseTime(stack, user));
        }

        /**
         * Fires when you START, not when you finish.
         *
         * The gesture has to run alongside the 3.2 seconds of smoking, so it's
         * hooked to the beginning of the use and authored to the same length.
         * Triggering on finishUsing would play the whole raise-and-lower after
         * the joint was already gone.
         */
        @Override
        public ActionResult use(World world, net.minecraft.entity.player.PlayerEntity user,
                                net.minecraft.util.Hand hand) {
            ActionResult result = super.use(world, user, hand);
            if (!world.isClient && result.isAccepted()) {
                TrapNet.play(user, TrapNet.JOINT_SMOKE);
            }
            return result;
        }
        @Override
        public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
            // Read before super shrinks the stack -- on the last joint it's
            // empty by the time we'd want it.
            Blend blend = TrapComponents.getBlend(stack);
            ItemStack result = super.finishUsing(stack, world, user);
            if (world instanceof ServerWorld server
                    && user instanceof net.minecraft.entity.player.PlayerEntity player) {
                if (blend == null) {
                    // A blend joint with no mix on it shouldn't exist, but a
                    // /give would make one. Fail quiet rather than throwing.
                    return result;
                }
                blendHit(server, player, blend, 1.0F, 0);
                JointItem.exhale(server, user);
            }
            return result;
        }
    }

    static class JointItem extends TrapItem {

        /** Per-tick feedback so the 3.2 seconds isn't dead air. See TrapSmoke. */
        @Override
        public void usageTick(World world, LivingEntity user, ItemStack stack, int remaining) {
            super.usageTick(world, user, stack, remaining);
            TrapSmoke.usageTick(world, user, remaining, getMaxUseTime(stack, user));
        }

        /**
         * Fires when you START, not when you finish.
         *
         * The gesture has to run alongside the 3.2 seconds of smoking, so it's
         * hooked to the beginning of the use and authored to the same length.
         * Triggering on finishUsing would play the whole raise-and-lower after
         * the joint was already gone.
         */
        @Override
        public ActionResult use(World world, net.minecraft.entity.player.PlayerEntity user,
                                net.minecraft.util.Hand hand) {
            ActionResult result = super.use(world, user, hand);
            if (!world.isClient && result.isAccepted()) {
                TrapNet.play(user, TrapNet.JOINT_SMOKE);
            }
            return result;
        }
        private final Strain strain;

        JointItem(Strain strain, Settings settings, Identifier model) {
            super(settings.food(
                            new FoodComponent.Builder().nutrition(1).saturationModifier(0.1F).alwaysEdible().build(),
                            ConsumableComponents.food()
                                    .consumeSeconds(3.2F)
                                    // TOOT_HORN raises the item to the mouth and holds
                                    // it there. It is the only vanilla use animation
                                    // that reads as smoking, and vanilla clients play
                                    // it with no mod installed.
                                    .useAction(UseAction.TOOT_HORN)
                                    // These SoundEvents fields are raw SoundEvent,
                                    // not RegistryEntry -- yarn exposes both forms.
                                    .sound(sound(SoundEvents.BLOCK_CAMPFIRE_CRACKLE))
                                    .finishSound(sound(SoundEvents.BLOCK_FIRE_EXTINGUISH))
                                    // Off: those are the eating crumbs. We spawn smoke
                                    // ourselves in finishUsing instead.
                                    .consumeParticles(false)
                                    .build())
                            .maxCount(16),
                    model);
            this.strain = strain;
        }

        @Override
        public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
            Quality grade = TrapComponents.get(stack);
            ItemStack result = super.finishUsing(stack, world, user);
            if (world instanceof ServerWorld server) {
                // Read the grade off the stack BEFORE super.finishUsing shrinks
                // it -- on the last joint the stack is empty by now.
                var existing = user.getStatusEffect(toleranceEffect);
                int level = existing == null ? 0 : existing.getAmplifier() + 1;
                float damping = ToleranceStatusEffect.multiplier(level);

                for (var effect : strain.effects(bakedEffect, grade)) {
                    user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                            effect.getEffectType(),
                            Math.max(20, Math.round(effect.getDuration() * damping)),
                            effect.getAmplifier(), effect.isAmbient(), effect.shouldShowParticles()));
                }

                // Then raise tolerance. Applied after, so this joint isn't
                // damped by the tolerance it just created.
                user.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        toleranceEffect, ToleranceStatusEffect.DURATION_TICKS,
                        Math.min(level, ToleranceStatusEffect.MAX_LEVEL), false, true));

                if (user instanceof net.minecraft.server.network.ServerPlayerEntity actor) {
                    TrapAddiction.hit(actor, Drug.of(strain), grade.potency());
                }

                exhale(server, user);
                // No method multiplier here -- a joint is the 1.0 that the
                // bong's 1.5 and the tlok's 2.2 are measured against.
                TrapCough.maybe(server, user, grade.potency() * damping);
            }
            return result;
        }

        /** A puff in whatever direction the player is looking. */
        private static void exhale(ServerWorld world, LivingEntity user) {
            TrapSmoke.exhale(world, user, 1.0F);
        }

        @Override
        public Item getPolymerItem(ItemStack stack, PacketContext context) {
            return Items.STICK;
        }
    }
}
