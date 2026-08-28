package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Five wands, one class.
 *
 * They are the top of the market: nothing else on the shelves costs this much,
 * and nothing else here can be farmed, brewed or grown into existence. That is
 * the point -- the shop needed something at the far end worth saving for, and
 * "a thing that does what no tool in the game does" is the only kind of prize
 * that stays interesting after the first one.
 *
 * All five cast from {@link #use}, not {@link #useOnBlock}, with the builder as
 * the single exception: the client only falls through to item use when the
 * block it was pointing at declined the click, so a wand that wanted a block
 * face would go dead every time it was aimed at a chest, a door or one of this
 * mod's own right-clickable crops. Wave them at the ground instead.
 *
 * The shop will not buy any of them back -- see {@link TrapScrap#refusal} --
 * because a wand you can craft from a nether star is a wand you could sell for
 * more than the star cost, and that is a printer rather than a sink.
 *
 * Each of the five has three tiers. The one you buy is the floor and the two
 * above it are bought again at an enchanting table -- half the shelf price and
 * then all of it -- which is the answer to what a wand is FOR after the evening
 * you bought it: a shelf item with no ladder is a purchase, and a purchase
 * stops being interesting the moment it is made. It is also another sink at the
 * only end of the market that has one. See {@link #upgrade} for the bench and
 * TrapMath#wandCooldown for what a tier is worth.
 */
public class WandItem extends Item implements PolymerItem {

    /** Which wand this is. Everything but the cast is shared. */
    public enum Kind {
        BOOST, HARVEST, PROSPECT, BUILDER, STORM;

        /**
         * Four lines on the stack itself -- what it does, how you use it, how
         * often, and what it would take to make it better -- so the wand
         * explains itself wherever it is looked at: in a hand, in a chest, and
         * on the shelf where somebody is deciding whether it is worth a
         * hundred and twenty thousand.
         *
         * The figures come from the constants below rather than being typed
         * out again, for the same reason the guide book reads them: a tooltip
         * that quotes a range the wand no longer has is worse than no tooltip.
         * Which is doubly true now that the same wand quotes five different
         * sets of them depending on how far it has been taken.
         */
        public List<Text> blurb(int tier) {
            List<Text> lore = switch (this) {
                case BOOST -> lines("Rzuca cię tam, gdzie patrzysz.",
                        "Skradanie: przeskok o " + reach(BLINK_RANGE, tier) + " bloków.",
                        every(cool(DASH_COOLDOWN, tier)) + ", przeskok co "
                                + secs(cool(BLINK_COOLDOWN, tier)) + ".");
                case HARVEST -> lines("Zbiera dojrzałe plony "
                                + (reach(HARVEST_RADIUS, tier) * 2 + 1) + "x"
                                + (reach(HARVEST_RADIUS, tier) * 2 + 1)
                                + " wokół ciebie.",
                        "Sadzi je z powrotem, plon idzie do plecaka.",
                        every(cool(HARVEST_COOLDOWN, tier)) + ".");
                case PROSPECT -> lines("Podświetla rudy w promieniu "
                                + reach(PROSPECT_RADIUS, tier) + " bloków.",
                        "Przez kamień. Widzisz je tylko ty.",
                        every(cool(PROSPECT_COOLDOWN, tier)) + ".");
                case BUILDER -> lines("Dokłada do " + reach(BUILDER_REACH, tier)
                                + " takich samych bloków w bok.",
                        "Bierze je z twojego plecaka.",
                        every(cool(BUILDER_COOLDOWN, tier)) + ".");
                case STORM -> lines("Piorun tam, gdzie patrzysz. Zasięg "
                                + STORM_RANGE + " bloków.",
                        "Nie podpala. Bije tylko potwory. "
                                + Math.round(power(STORM_DAMAGE, tier)) + " obrażeń.",
                        every(cool(STORM_COOLDOWN, tier)) + ".");
            };
            lore.add(ladder(tier));
            return lore;
        }

        /**
         * The line that says the wand is not finished yet.
         *
         * On the stack rather than only in the book, because the upgrade is a
         * sneaking right-click on an enchanting table and nobody discovers
         * that by accident. It rides the shelf copy too -- {@code priceTag}
         * puts the self-description back on top of the price -- so the rack
         * advertises the ladder to somebody who has not bought one yet.
         */
        private Text ladder(int tier) {
            if (tier >= TrapMath.WAND_TIERS - 1) {
                return Text.literal("Rozbudowana do końca.")
                        .formatted(Formatting.LIGHT_PURPLE)
                        .styled(style -> style.withItalic(false));
            }
            // The rule rather than the figure. Lore is baked when the item is
            // registered and rewritten when the tier moves, and at neither of
            // those moments is there a catalogue to read a price out of --
            // ShopStock is built per server start, long after registration.
            // The exact number is one click away: ask for an upgrade you
            // cannot afford and the wand says what it wanted.
            return Text.literal("Ulepszenie przy stole zaklęć: "
                    + (tier == 0 ? "pół ceny z półki." : "cena z półki."))
                    .formatted(Formatting.DARK_PURPLE)
                    .styled(style -> style.withItalic(false));
        }

        /**
         * "Raz na 15 s".
         *
         * The cooldown is drawn on the item as a sweep, which tells you that
         * you are waiting but never how long for -- and how long for is the
         * whole question when you are deciding whether the thing is worth
         * five figures.
         */
        private static String every(int ticks) {
            return "Raz na " + secs(ticks);
        }

        private static String secs(int ticks) {
            float seconds = ticks / 20.0f;
            return (seconds == Math.round(seconds)
                    ? String.valueOf(Math.round(seconds))
                    : String.valueOf(seconds).replace('.', ',')) + " s";
        }

        /**
         * Grey and upright.
         *
         * Lore is dark purple italics unless the line says otherwise --
         * fillStyle only fills what is UNSET -- so both have to be stated or
         * the description arrives looking like a magic item's flavour text
         * instead of the instructions it is.
         */
        private static List<Text> lines(String... said) {
            List<Text> lore = new ArrayList<>();
            for (String line : said) {
                lore.add(Text.literal(line).formatted(Formatting.GRAY)
                        .styled(style -> style.withItalic(false)));
            }
            return lore;
        }
    }

    // --- the numbers, which the guide book quotes rather than retypes --------

    /** Pace, in blocks a second-ish. Above 2.0 you outrun chunk loading. */
    public static final double DASH_PUSH = 1.7;
    public static final int DASH_COOLDOWN = 40;
    /** Slow falling after a dash: the wand is movement, not a way to die. */
    public static final int SOFT_LANDING = 120;
    public static final int BLINK_RANGE = 12;
    public static final int BLINK_COOLDOWN = 100;

    /** Half-width of the harvested square, centred on you: 4 -> 9x9. */
    public static final int HARVEST_RADIUS = 4;
    /** How far up and down it reaches, for terraced fields. */
    public static final int HARVEST_HEIGHT = 2;
    public static final int HARVEST_COOLDOWN = 60;

    public static final int PROSPECT_RADIUS = 10;
    public static final int PROSPECT_COOLDOWN = 200;

    /** Blocks added per cast, at most. */
    public static final int BUILDER_REACH = 8;
    public static final int BUILDER_COOLDOWN = 30;

    public static final int STORM_RANGE = 40;
    public static final float STORM_DAMAGE = 12.0F;
    /** Everything hostile this close to the strike takes it too. */
    public static final double STORM_SPLASH = 3.5;
    /**
     * Fifteen seconds, up from eight.
     *
     * Six hearts in an area, from forty blocks away, through a wall, on a
     * count of eight is not a wand -- it is a turret, and it makes every mob
     * in the game somebody else's problem. Long enough now that a fight is
     * still a fight and the wand is what opens it.
     */
    public static final int STORM_COOLDOWN = 300;

    // --- tiers ----------------------------------------------------------------

    /**
     * What the wand has been taken to, 0 for one straight off the shelf.
     *
     * Read through TrapMath's clamp rather than trusted: this is a data
     * component, which is to say a number anybody with a command block can
     * write, and an array index is a bad place to find that out.
     */
    public static int tierOf(ItemStack stack) {
        Integer level = stack.get(TrapComponents.wandTier);
        return level == null ? 0 : TrapMath.wandTier(level);
    }

    /** How the tier is written on the name: nothing, then II, then III. */
    private static final String[] MARK = {"", " II", " III"};

    private static int cool(int ticks, int tier) {
        return TrapMath.wandCooldown(ticks, tier);
    }

    private static int reach(int blocks, int tier) {
        return TrapMath.wandReach(blocks, tier);
    }

    private static float power(float damage, int tier) {
        return TrapMath.wandDamage(damage, tier);
    }

    private final Kind kind;
    private final Identifier model;

    public WandItem(Kind kind, Settings settings, Identifier model) {
        super(settings);
        this.kind = kind;
        this.model = model;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld server) || !(user instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;
        }
        ItemStack wand = player.getStackInHand(hand);
        // Vanilla checks this before calling us, but only on the path the
        // client predicts. A wand that fires twice on a laggy tick is a wand
        // that teleports you into a wall.
        if (player.getItemCooldownManager().isCoolingDown(wand)) {
            return ActionResult.FAIL;
        }

        int tier = tierOf(wand);
        int spent = switch (kind) {
            // Sneak for the expensive half. The dash is what you press a
            // hundred times an evening, so it keeps the plain click.
            case BOOST -> player.isSneaking()
                    ? blink(server, player, tier) : dash(server, player, tier);
            case HARVEST -> harvest(server, player, tier);
            case PROSPECT -> prospect(server, player, tier);
            case STORM -> storm(server, player, tier);
            // The builder needs a face to work from; a click into the air is
            // the player telling us they missed.
            case BUILDER -> 0;
        };

        if (spent <= 0) {
            fizzle(server, player);
            return ActionResult.FAIL;
        }
        player.getItemCooldownManager().set(wand, spent);
        return ActionResult.SUCCESS;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (!(context.getWorld() instanceof ServerWorld server)
                || !(context.getPlayer() instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
        }
        // The bench. It has to be the sneaking click and it always is: without
        // sneaking the table's own screen answers first and the item is never
        // asked, which is also why this needs no gesture of its own.
        //
        // One wrinkle, checked in the bytecode rather than assumed: vanilla
        // returns PASS from interactBlock without calling us at all while the
        // stack is cooling down, so an upgrade clicked in the seconds after a
        // cast does nothing and cannot even fizzle. Clicking again once the
        // sweep has run out works, and the book says so.
        if (server.getBlockState(context.getBlockPos()).isOf(Blocks.ENCHANTING_TABLE)) {
            return upgrade(server, player, context.getStack());
        }
        if (kind != Kind.BUILDER) {
            // Every other wand falls through to use() and casts at the block,
            // which is the behaviour they had before the bench existed.
            return ActionResult.PASS;
        }
        if (player.getItemCooldownManager().isCoolingDown(context.getStack())) {
            return ActionResult.FAIL;
        }
        int spent = build(server, player, context.getBlockPos(), context.getSide(),
                tierOf(context.getStack()));
        if (spent <= 0) {
            fizzle(server, player);
            return ActionResult.FAIL;
        }
        player.getItemCooldownManager().set(context.getStack(), spent);
        return ActionResult.SUCCESS;
    }

    // --- the bench ------------------------------------------------------------

    /**
     * Buy the next tier up at an enchanting table.
     *
     * Done in code rather than as a crafting recipe because a vanilla
     * ingredient matches on the ITEM and cannot see components: a shapeless
     * "wand plus payment" recipe would happily take a wand that is already
     * finished and hand back a fresh one, which is a grinder for the most
     * expensive thing in the mod. Here the tier on the stack is read, checked
     * and written back, and the same click cannot go backwards.
     *
     * Paid in emeralds off the wand's own shelf price, which is read from the
     * catalogue rather than typed in here: the shop is where that number
     * lives, and an upgrade quoting a price the rack no longer charges is the
     * same lie as a tooltip quoting a range the wand no longer has.
     */
    private ActionResult upgrade(ServerWorld world, ServerPlayerEntity player, ItemStack wand) {
        int tier = tierOf(wand);
        if (tier >= TrapMath.WAND_TIERS - 1) {
            player.sendMessage(Text.literal("Dalej już nie idzie.")
                    .formatted(Formatting.GRAY), true);
            fizzle(world, player);
            return ActionResult.FAIL;
        }

        ShopStock.Entry listed = ShopStock.matching(wand);
        if (listed == null) {
            // Only reachable if a wand is ever taken off the rack. Say so
            // rather than handing out a free tier or a price of one emerald.
            player.sendMessage(Text.literal("Tego nikt nie ulepszy.")
                    .formatted(Formatting.GRAY), true);
            fizzle(world, player);
            return ActionResult.FAIL;
        }

        // The flat catalogue price, not what the shelf is asking this morning:
        // the market swings a listing by tens of percent and an upgrade whose
        // price moves under you while you walk to the table is a bad shop.
        int price = TrapMath.wandPrice(listed.base(), tier);
        int purse = TrapMarket.wealthOf(player);
        if (purse < price) {
            player.sendMessage(Text.literal("Brakuje ci ").formatted(Formatting.GRAY)
                    .append(Text.literal((price - purse) + "e").formatted(Formatting.RED))
                    .append(Text.literal(" z " + price + "e.").formatted(Formatting.GRAY)), true);
            fizzle(world, player);
            return ActionResult.FAIL;
        }
        TrapMarket.take(player, price);
        // Through the market and the ledger both, like every other counter in
        // the mod: money that leaves a pocket without telling either is money
        // the economy never sees leaving.
        TrapLedger.record(player, TrapLedger.Source.MARKET, -price);

        // The number, the name and the description move together or the wand
        // starts lying about itself in a chest.
        int next = tier + 1;
        wand.set(TrapComponents.wandTier, next);
        wand.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                wand.getItem().getName().copy()
                        .append(MARK[next])
                        .formatted(Formatting.LIGHT_PURPLE)
                        .styled(style -> style.withItalic(false)));
        wand.set(net.minecraft.component.DataComponentTypes.LORE,
                new net.minecraft.component.type.LoreComponent(kind.blurb(next)));

        world.spawnParticles(ParticleTypes.ENCHANT,
                player.getX(), player.getY() + 1.2, player.getZ(), 80, 0.4, 0.7, 0.4, 0.6);
        world.spawnParticles(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.3, 0.5, 0.3, 0.05);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE,
                SoundCategory.BLOCKS, 1.0F, 0.8F);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                SoundCategory.PLAYERS, 0.8F, 1.2F);
        player.sendMessage(wand.getName().copy().formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(" za " + price + "e").formatted(Formatting.GREEN)), true);
        return ActionResult.SUCCESS;
    }

    // --- the casts ------------------------------------------------------------

    /**
     * Throw the player where they're looking.
     *
     * velocityModified is the whole trick: a player's motion lives on their own
     * client, and without that flag the server's new velocity is never sent and
     * nothing happens at all.
     */
    private static int dash(ServerWorld world, ServerPlayerEntity player, int tier) {
        Vec3d look = player.getRotationVector();
        // The lift is what makes it a leap rather than a shove into the dirt,
        // and it is what carries you over a fence when you're looking level.
        player.setVelocity(look.multiply(DASH_PUSH).add(0.0, 0.25, 0.0));
        player.velocityModified = true;
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOW_FALLING, SOFT_LANDING, 0, false, false));

        world.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL,
                player.getX(), player.getY() + 0.2, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticles(ParticleTypes.CLOUD,
                player.getX(), player.getY() + 0.2, player.getZ(), 18, 0.3, 0.1, 0.3, 0.02);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.8F, 1.2F);
        // The push itself does not scale: past 2.0 you outrun chunk loading and
        // arrive in a hole in the world. What an upgraded wand buys here is how
        // often you get to press it.
        return cool(DASH_COOLDOWN, tier);
    }

    /**
     * Step the player forward until something is in the way.
     *
     * Walked out in half blocks with the player's own hitbox rather than
     * raycast-and-hope: a ray finds the wall's face, which is exactly where you
     * must NOT land, and the difference between the two is a suffocating player
     * asking for their twenty-five thousand emeralds back.
     */
    private static int blink(ServerWorld world, ServerPlayerEntity player, int tier) {
        Vec3d from = player.getPos();
        Vec3d step = player.getRotationVector().multiply(0.5);
        Vec3d best = from;
        for (int i = 0; i < reach(BLINK_RANGE, tier) * 2; i++) {
            Vec3d probe = best.add(step);
            if (!world.isSpaceEmpty(player, player.getBoundingBox().offset(probe.subtract(from)))) {
                break;
            }
            best = probe;
        }
        if (best.squaredDistanceTo(from) < 1.0) {
            return 0; // nose against a wall
        }

        world.spawnParticles(ParticleTypes.REVERSE_PORTAL,
                from.x, from.y + 1.0, from.z, 40, 0.3, 0.6, 0.3, 0.05);
        world.playSound(null, from.x, from.y, from.z,
                SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 0.7F, 1.4F);

        player.teleport(world, best.x, best.y, best.z, Set.of(),
                player.getYaw(), player.getPitch(), true);
        // Arriving is not falling. Without this a blink taken mid-drop lands
        // you with the whole fall still on the clock.
        player.fallDistance = 0.0;

        world.spawnParticles(ParticleTypes.PORTAL,
                best.x, best.y + 1.0, best.z, 60, 0.3, 0.6, 0.3, 0.4);
        world.playSound(null, best.x, best.y, best.z,
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6F, 1.3F);
        return cool(BLINK_COOLDOWN, tier);
    }

    /**
     * Pick every ripe plant around you and leave the field standing.
     *
     * Our own crops go through their own harvest(): breaking one runs the loot
     * table and hands back a SEED, so a wand that reached for getDroppedStacks
     * would quietly demolish a weed farm and give nothing for it. That is the
     * same trap the crew fell into -- see TrapCrew.
     */
    private static int harvest(ServerWorld world, ServerPlayerEntity player, int tier) {
        BlockPos middle = player.getBlockPos();
        List<ItemStack> picked = new ArrayList<>();
        int plants = 0;
        int radius = reach(HARVEST_RADIUS, tier);

        for (BlockPos pos : BlockPos.iterate(
                middle.add(-radius, -HARVEST_HEIGHT, -radius),
                middle.add(radius, HARVEST_HEIGHT, radius))) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            List<ItemStack> off;

            if (block instanceof CannabisCropBlock weed) {
                off = weed.harvest(world, pos, state);
            } else if (block instanceof CocaCropBlock coca) {
                off = coca.harvest(world, pos, state);
            } else if (block instanceof PoppyCropBlock poppy) {
                off = poppy.harvest(world, pos, state);
            } else if (block instanceof CropBlock crop && crop.isMature(state)) {
                off = Block.getDroppedStacks(state, world, pos, null);
                // Age zero rather than broken: the wand harvests a field, it
                // doesn't dismantle one.
                world.setBlockState(pos, crop.withAge(0));
            } else {
                continue;
            }

            if (off.isEmpty()) {
                continue;
            }
            plants++;
            picked.addAll(off);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.0);
        }

        if (plants == 0) {
            player.sendMessage(Text.literal("Nic tu nie dojrzało.")
                    .formatted(Formatting.GRAY), true);
            return 0;
        }
        // Into the bag, not onto the floor. Walking the field to pick up what
        // the wand just cut is the chore this is sold to remove.
        for (ItemStack stack : picked) {
            player.getInventory().offerOrDrop(stack);
        }
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_CROP_BREAK,
                SoundCategory.PLAYERS, 0.9F, 1.1F);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ALLAY_ITEM_GIVEN,
                SoundCategory.PLAYERS, 0.7F, 1.3F);
        player.sendMessage(Text.literal("Zebrano ").formatted(Formatting.GRAY)
                .append(Text.literal(plants + " roślin").formatted(Formatting.GREEN)), true);
        return cool(HARVEST_COOLDOWN, tier);
    }

    /**
     * Light up every ore around you, for your eyes only.
     *
     * The particles go to one player rather than the world -- standing next to
     * someone prospecting should not hand you their survey. Ore is matched on
     * the id ending in _ore rather than a tag: this pack runs 136 mods and the
     * ones that add ores do not agree on tags, but they all agree on the name.
     */
    private static int prospect(ServerWorld world, ServerPlayerEntity player, int tier) {
        BlockPos middle = player.getBlockPos();
        Map<String, Integer> tally = new LinkedHashMap<>();
        // A cube, so the top tier is walking 30k blockstates rather than 9k.
        // That is a handful of milliseconds once every six seconds and it is
        // why this radius grows by a quarter and not by a factor.
        int radius = reach(PROSPECT_RADIUS, tier);

        for (BlockPos pos : BlockPos.iterate(
                middle.add(-radius, -radius, -radius),
                middle.add(radius, radius, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || !isOre(state)) {
                continue;
            }
            tally.merge(state.getBlock().getName().getString(), 1, Integer::sum);
            world.spawnParticles(player, ParticleTypes.GLOW, true, true,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
        }

        if (tally.isEmpty()) {
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    SoundCategory.PLAYERS, 0.6F, 0.6F);
            player.sendMessage(Text.literal("Cisza. Nic tu nie ma.")
                    .formatted(Formatting.GRAY), true);
            return cool(PROSPECT_COOLDOWN, tier);
        }

        // Three names is what fits the action bar and is what anyone actually
        // wants to know -- the long tail is one coal seam and a lot of noise.
        String summary = tally.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(entry -> entry.getValue() + "x " + entry.getKey())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        player.sendMessage(Text.literal(summary).formatted(Formatting.AQUA), true);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 0.8F, 1.5F);
        return cool(PROSPECT_COOLDOWN, tier);
    }

    private static boolean isOre(BlockState state) {
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    /**
     * Extend the face you clicked, out of your own inventory.
     *
     * Copies the source blockstate rather than placing the item: a builder's
     * wand that reset the rotation of every stair it laid would be a worse tool
     * than the hand it replaces. The cost comes out of your bag one block at a
     * time, so it lays exactly as much as you are carrying.
     */
    private static int build(ServerWorld world, ServerPlayerEntity player,
                             BlockPos hit, Direction side, int tier) {
        BlockState pattern = world.getBlockState(hit);
        if (pattern.isAir() || pattern.getHardness(world, hit) < 0) {
            return 0;
        }
        Item cost = pattern.getBlock().asItem();
        if (cost == Items.AIR) {
            return 0; // no item form: nothing to charge for it
        }

        int laid = 0;
        BlockPos pos = hit;
        for (int i = 0; i < reach(BUILDER_REACH, tier); i++) {
            pos = pos.offset(side);
            BlockState there = world.getBlockState(pos);
            // The whole cube rather than the pattern's own shape: an empty
            // VoxelShape throws when you ask it for bounds, and "is anything
            // standing here" is the question anyway.
            if (!there.isReplaceable() || !world.isSpaceEmpty(new Box(pos))) {
                break; // a wall, or something standing where the block would go
            }
            if (!player.isCreative() && !take(player, cost)) {
                break; // out of blocks, which is the only limit worth having
            }
            world.setBlockState(pos, pattern);
            world.playSound(null, pos, pattern.getSoundGroup().getPlaceSound(),
                    SoundCategory.BLOCKS, 0.8F, 1.0F);
            world.spawnParticles(ParticleTypes.WAX_ON,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
            laid++;
        }

        if (laid == 0) {
            return 0;
        }
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 0.5F, 1.6F);
        return cool(BUILDER_COOLDOWN, tier);
    }

    /** One block out of the player's bag, or false if they haven't got one. */
    private static boolean take(ServerPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                stack.decrement(1);
                return true;
            }
        }
        return false;
    }

    /**
     * Lightning where you're looking, without the wildfire.
     *
     * Cosmetic lightning does no damage, sets nothing alight and turns no pigs
     * into piglins, so the damage is dealt by hand in a small radius. That is
     * the difference between a weapon and a way to burn down somebody's roof
     * from forty blocks away. Only hostiles are in the blast -- not players,
     * and not villagers: half this mod's economy is a villager standing in a
     * town square, and a wand that could clear one out of spite, or by
     * accident, is a wand that ends a server.
     */
    private static int storm(ServerWorld world, ServerPlayerEntity player, int tier) {
        // The range is the one number on this wand that does NOT grow. Forty
        // blocks through a wall is already the far edge of fair; what an
        // upgrade buys is the bolt landing harder and sooner, not from further
        // away, where the thing being hit has even less say in it.
        HitResult aim = player.raycast(STORM_RANGE, 0.0F, false);
        if (aim.getType() != HitResult.Type.BLOCK) {
            return 0;
        }
        Vec3d at = ((BlockHitResult) aim).getPos();

        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world,
                net.minecraft.entity.SpawnReason.TRIGGERED);
        if (bolt == null) {
            return 0;
        }
        bolt.refreshPositionAfterTeleport(at);
        bolt.setCosmetic(true);
        bolt.setChanneler(player);
        world.spawnEntity(bolt);

        int struck = 0;
        for (LivingEntity victim : world.getEntitiesByClass(LivingEntity.class,
                new Box(at.x - STORM_SPLASH, at.y - STORM_SPLASH, at.z - STORM_SPLASH,
                        at.x + STORM_SPLASH, at.y + STORM_SPLASH, at.z + STORM_SPLASH),
                found -> found.isAlive() && found instanceof net.minecraft.entity.mob.Monster)) {
            // Indirect magic, credited to the caster: drops, experience and
            // every "killed by" the game keeps track of land on the right
            // player, which a bare magic() source loses.
            victim.damage(world, player.getDamageSources().indirectMagic(player, player),
                    power(STORM_DAMAGE, tier));
            struck++;
        }

        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                60, 0.6, 0.4, 0.6, 0.5);
        world.spawnParticles(ParticleTypes.FLASH, at.x, at.y + 0.5, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        world.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
                SoundCategory.WEATHER, 1.0F, 1.0F);
        if (struck > 0) {
            player.sendMessage(Text.literal("Trafione: " + struck)
                    .formatted(Formatting.GOLD), true);
        }
        return cool(STORM_COOLDOWN, tier);
    }

    /** A cast that didn't happen still has to sound like something. */
    private static void fizzle(ServerWorld world, ServerPlayerEntity player) {
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH,
                SoundCategory.PLAYERS, 0.4F, 1.6F);
    }

    // --- polymer --------------------------------------------------------------

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        // A rod: something a vanilla client will hold like a wand even in the
        // frame or two before our model reaches it.
        return Items.BLAZE_ROD;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }
}
