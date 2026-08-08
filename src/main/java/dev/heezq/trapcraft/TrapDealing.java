package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Customers find you.
 *
 * The wandering trader already lets you SELL product, but you have to go and
 * find one, and it buys everything at a flat rate. This is the other side of
 * the counter: if you're carrying, somebody eventually turns up wanting it,
 * walks over, and pays well above the market because they're not in a position
 * to shop around.
 *
 * Each customer craves ONE thing. That's what makes a visit an event rather
 * than a vending machine -- sometimes they want exactly the Fire Purp you've
 * got, and sometimes they want Haze and you're holding Kush.
 *
 * Built on WanderingTraderEntity rather than a custom mob or a villager
 * profession. Villagers can't carry custom trades without a profession, and
 * VillagerProfession is a final record in 1.21.8 that can't be made
 * Polymer-safe -- see the note in {@link TrapTrades}. The trader is the one
 * vanilla entity that exists to be a temporary merchant, it already knows how
 * to despawn on a timer, and its trading screen needs no client code at all.
 */
public final class TrapDealing {
    /** Marks our customers so they're distinguishable from real traders. */
    public static final String TAG = "trapcraft_customer";

    /** How often the spawn roll happens. */
    private static final int CHECK_INTERVAL = 20 * 15;
    /** Chance per check, so roughly one visit every 7-8 minutes while holding. */
    private static final int SPAWN_CHANCE = 30;

    /** How long they'll hang around before giving up on you. */
    public static final int LIFETIME_TICKS = 20 * 180;

    private static final int SPAWN_MIN = 11;
    private static final int SPAWN_RANGE = 9;      // 11..20 blocks out
    private static final int GIVE_UP_DISTANCE = 48;

    /** They pay this much over the wandering trader for what they crave. */
    private static final float PREMIUM = 1.9F;

    private record Customer(UUID player, int bornAt, Craving craving) {
    }

    /** Live customers, by entity id. In memory only -- see tick(). */
    private static final Map<UUID, Customer> CUSTOMERS = new HashMap<>();

    /**
     * Customers on their way out, and the tick they started leaving.
     *
     * They walk off rather than popping: a visitor who vanishes the instant
     * the deal closes reads as despawn jank, and the walk away is the part
     * that makes them feel like a person who came and went.
     */
    private static final Map<UUID, Integer> LEAVING = new HashMap<>();
    /** How long they get to walk before they're gone. */
    private static final int LEAVE_TICKS = 20 * 8;
    /** Far enough away to stop being interesting. */
    private static final int LEAVE_DISTANCE = 28;

    private TrapDealing() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TrapDealing::tick);
        registerCommand();

        // Every interaction with a customer is handled here and nowhere else.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
                (player, world, hand, entity, hit) -> {
                    // MAIN_HAND only: the callback fires once per hand, and a
                    // sale must not happen twice for one right-click.
                    if (!world.isClient() && hand == net.minecraft.util.Hand.MAIN_HAND
                            && entity instanceof WanderingTraderEntity customer
                            && customer.getCommandTags().contains(TAG)) {
                        Customer record = CUSTOMERS.get(customer.getUuid());
                        if (record != null && player instanceof ServerPlayerEntity seller) {
                            // The trade screen never opens for a customer.
                            //
                            // It cannot draw a payout for a Polymer item -- the
                            // client recomputes the result slot and paints an
                            // empty square over emeralds the server has already
                            // worked out -- so showing it at all just offers a
                            // broken way to do the thing handOver does properly.
                            // Consuming the interaction is what suppresses it.
                            if (seller.isSneaking()) {
                                dismiss(customer, seller, seller.getServer().getTicks());
                            } else if (!handOver(customer, seller, record.craving())) {
                                nudge(seller, customer, record.craving());
                            }
                            return net.minecraft.util.ActionResult.SUCCESS;
                        }
                    }
                    return net.minecraft.util.ActionResult.PASS;
                });
    }

    /**
     * /customer [strain|powder] -- summon one on demand.
     *
     * A visit is deliberately rare, which is good for play and terrible for
     * testing: the trade list was carrying vanilla's stock for who knows how
     * long precisely because nobody could reproduce a customer to look at.
     * Ops only, and it still refuses if you already have one.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> {
                    var root = net.minecraft.server.command.CommandManager.literal("customer")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> summon(context.getSource().getPlayer(), null));
                    for (Strain strain : Strain.values()) {
                        root.then(net.minecraft.server.command.CommandManager
                                .literal(strain.id())
                                .executes(context -> summon(context.getSource().getPlayer(),
                                        Craving.of(strain, Contract.Form.EITHER))));
                    }
                    root.then(net.minecraft.server.command.CommandManager.literal("powder")
                            .executes(context -> summon(context.getSource().getPlayer(),
                                    Craving.forPowder())));
                    // "any mix" rather than a name: the named blends are data,
                    // and a test command that only works for blends you happen
                    // to have already discovered isn't much of a test command.
                    root.then(net.minecraft.server.command.CommandManager.literal("mix")
                            .executes(context -> summon(context.getSource().getPlayer(),
                                    Craving.mixed(""))));
                    dispatcher.register(root);
                });
    }

    private static int summon(ServerPlayerEntity player, Craving craving) {
        if (player == null) {
            return 0;
        }
        if (hasCustomer(player)) {
            player.sendMessage(Text.literal("You've already got one on the way.")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        // No argument means "whatever I'm carrying", the same choice the random
        // path makes, so the default tests the real behaviour rather than a
        // special case.
        Craving wanted = craving != null ? craving : cravingFor(player);
        if (wanted == null) {
            player.sendMessage(Text.literal(
                            "Nobody wants nothing. Carry some product, or name a strain.")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        // server.getTicks(), NOT world.getTime(). The shepherd measures a
        // customer's age against the server tick counter, and world time is a
        // different, far larger clock -- mixing them made the difference
        // negative, so a summoned customer never aged out and waited forever.
        if (!visit(player, wanted, player.getServer().getTicks())) {
            player.sendMessage(Text.literal("Nowhere for them to walk in from.")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        return 1;
    }

    private static void tick(MinecraftServer server) {
        int now = server.getTicks();
        if (!CUSTOMERS.isEmpty()) {
            shepherd(server, now);
        }
        if (now % CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            maybeVisit(player, now);
        }
    }

    // --- arrival -------------------------------------------------------------

    private static void maybeVisit(ServerPlayerEntity player, int now) {
        if (hasCustomer(player)) {
            return;   // one at a time; a queue of junkies is a mob, not a deal
        }
        if (player.getWorld().getRandom().nextInt(SPAWN_CHANCE) != 0) {
            return;
        }

        // They can smell it. No product, no visit -- otherwise this is just a
        // random mob spawner bolted onto the mod.
        Craving craving = cravingFor(player);
        if (craving == null) {
            return;
        }
        visit(player, craving, now);
    }

    /**
     * Actually send somebody over.
     *
     * Split out from the random roll above so /customer can summon one without
     * a second copy of the spawn code -- a visit is rare by design, which is
     * exactly what made the trade list bug survive so long.
     */
    private static boolean visit(ServerPlayerEntity player, Craving craving, int now) {
        ServerWorld world = player.getWorld();
        BlockPos spot = findSpot(world, player.getBlockPos());
        if (spot == null) {
            return false;
        }

        WanderingTraderEntity customer =
                EntityType.WANDERING_TRADER.create(world, SpawnReason.EVENT);
        if (customer == null) {
            return false;
        }
        customer.refreshPositionAndAngles(spot, world.getRandom().nextFloat() * 360.0F, 0.0F);
        customer.setCustomName(Text.literal(craving.title())
                .formatted(Formatting.DARK_GREEN));
        customer.setCustomNameVisible(true);
        customer.addCommandTag(TAG);
        // Vanilla's own timer as a backstop: if the server restarts and our
        // in-memory record is lost, they still wander off on their own instead
        // of standing in a field forever.
        customer.setDespawnDelay(LIFETIME_TICKS + 20 * 30);

        world.spawnEntity(customer);
        // No trade offers are set at all. The screen never opens (see the
        // UseEntityCallback in register), so whatever the wandering trader
        // stocks itself is never seen, and everything this customer buys goes
        // through handOver instead.
        CUSTOMERS.put(customer.getUuid(), new Customer(player.getUuid(), now, craving));

        player.sendMessage(Text.literal(craving.greeting()).formatted(Formatting.GRAY), false);
        world.playSound(null, spot, SoundEvents.ENTITY_WANDERING_TRADER_YES,
                SoundCategory.NEUTRAL, 0.7F, 0.8F);
        return true;
    }

    /** A clear surface spot near the player but not on top of them. */
    private static BlockPos findSpot(ServerWorld world, BlockPos near) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = world.getRandom().nextDouble() * Math.PI * 2;
            int distance = SPAWN_MIN + world.getRandom().nextInt(SPAWN_RANGE);
            int x = near.getX() + (int) (Math.cos(angle) * distance);
            int z = near.getZ() + (int) (Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            BlockPos spot = new BlockPos(x, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z), z);
            if (world.getBlockState(spot).isAir() && world.getBlockState(spot.up()).isAir()) {
                return spot;
            }
        }
        return null;
    }

    // --- the approach --------------------------------------------------------

    /**
     * Walk them toward their customer, and get rid of them when it's over.
     *
     * Re-issuing a navigation target beats adding an AI goal: goalSelector is
     * protected and would need an accessor mixin for one behaviour that amounts
     * to "walk that way", and a path recomputed every second and a half tracks
     * a moving player perfectly well.
     */
    private static void shepherd(MinecraftServer server, int now) {
        CUSTOMERS.entrySet().removeIf(entry -> {
            Customer record = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(record.player());
            var entity = findCustomer(server, entry.getKey());

            if (entity == null) {
                // Killed, despawned or unloaded. leave() never runs on this
                // path, so its bookkeeping has to be cleared here or DEALT and
                // APPETITE grow a row per lost customer for the life of the
                // server.
                LEAVING.remove(entry.getKey());
                DEALT.remove(entry.getKey());
                APPETITE.remove(entry.getKey());
                return true;
            }

            // Already walking off: let them finish, then they're gone.
            if (tickLeaving(entity, player, now)) {
                return true;
            }

            // Out of range, out of time, or the player left the world. These
            // vanish outright rather than walking, because there is nobody
            // there to watch them go.
            if (player == null || player.getWorld() != entity.getWorld()
                    || !entity.getBlockPos().isWithinDistance(player.getBlockPos(), GIVE_UP_DISTANCE)) {
                LEAVING.remove(entry.getKey());
                leave(entity);
                return true;
            }

            // The deal's done. Wait for the screen to close so they don't
            // evaporate out of the menu mid-trade, then send them off happy.
            if (entity.getCustomer() == null && hasTraded(entity)) {
                startLeaving(entity, player, now, true);
                return false;
            }

            // Nobody's selling. Give up and move on.
            if (now - record.bornAt() > LIFETIME_TICKS) {
                startLeaving(entity, player, now, false);
                return false;
            }

            // Wandering traders drink an invisibility potion at night. That's
            // fine for a vanilla trader wandering the wilds and useless for
            // somebody who is supposed to be walking up to you, so it gets
            // stripped every pass rather than fought at the AI level.
            //
            // The EFFECT and the BOTTLE are two separate problems: removing the
            // effect alone leaves them stood there holding a potion forever,
            // because the drink goal keeps re-arming and we keep cancelling its
            // result. Take the bottle out of their hands too.
            entity.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY);
            for (var slot : new net.minecraft.entity.EquipmentSlot[]{
                    net.minecraft.entity.EquipmentSlot.MAINHAND,
                    net.minecraft.entity.EquipmentSlot.OFFHAND}) {
                if (!entity.getEquippedStack(slot).isEmpty()) {
                    entity.equipStack(slot, ItemStack.EMPTY);
                }
            }

            // Re-assert what they buy, every pass, unconditionally.
            //
            // WanderingTraderEntity.fillRecipes() APPENDS to the existing list
            // rather than replacing it, so vanilla's stock piles on top of ours
            // whenever it runs -- which is why a Midnight customer ended up
            // selling saplings and dye with one of our rows buried among them.
            // A size check wasn't enough because the totals could coincide;
            // overwriting outright is cheap for one customer and can't be
            // outraced by an append.

            // Don't drag them away mid-trade -- or once they're heading off,
            // which would have them trudging back to somebody they're done with.
            if (entity.getCustomer() == null && now % 30 == 0
                    && !LEAVING.containsKey(entry.getKey())) {
                entity.getNavigation().startMovingTo(player, 0.55);
            }
            // Somebody walking away shouldn't still be asking.
            if (now % 80 == 0 && !LEAVING.containsKey(entry.getKey())) {
                beg(entity, player);
            }
            return false;
        });
    }

    private static WanderingTraderEntity findCustomer(MinecraftServer server, UUID id) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(id) instanceof WanderingTraderEntity trader && trader.isAlive()) {
                return trader;
            }
        }
        return null;
    }

    private static void beg(WanderingTraderEntity customer, ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) customer.getWorld();
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                customer.getX(), customer.getEyeY() + 0.4, customer.getZ(),
                1, 0.2, 0.1, 0.2, 0.0);
        if (customer.getBlockPos().isWithinDistance(player.getBlockPos(), 6)) {
            world.playSound(null, customer.getBlockPos(),
                    SoundEvents.ENTITY_WANDERING_TRADER_TRADE,
                    SoundCategory.NEUTRAL, 0.5F, 0.7F);
        }
    }


    /** Customers who have bought all they came for, so they know to leave. */
    private static final java.util.Set<UUID> DEALT = new java.util.HashSet<>();

    /** How much each customer still wants, by entity id. */
    private static final Map<UUID, Integer> APPETITE = new HashMap<>();

    /**
     * How many items one customer will take before they're done.
     *
     * They used to leave after a SINGLE hand-over, which made a visit worth one
     * item -- two emeralds for somebody who walked across the map to find you.
     * A visit should be worth the interruption.
     */
    public static final int UNITS_WANTED = 8;

    /**
     * Sell by handing it over, with no screen at all.
     *
     * The vanilla merchant screen cannot render a payout for a Polymer item:
     * the client recomputes that result slot itself, disagrees, and paints an
     * empty square over emeralds the server has already worked out. Polymer
     * #254 is the same family of bug and its one-item workaround did not cover
     * this. Every attempt to correct the client -- re-sending the slot,
     * dropping the grade predicate, matching the required count -- lost to the
     * client's own recomputation.
     *
     * So the sale stops going through that screen. Walk up holding what they
     * want, right-click, and they take one and pay you. No menu can desync,
     * and for a street deal, handing it over is the better fiction anyway.
     *
     * The trade screen still opens when your hands are empty, as the shop
     * window telling you what they came for.
     *
     * @return true if a sale happened, so the screen does not also open
     */
    private static boolean handOver(WanderingTraderEntity customer, ServerPlayerEntity seller,
                                    Craving craving) {
        ItemStack held = seller.getMainHandStack();
        if (held.isEmpty()) {
            return false;
        }

        int each;
        if (craving.powder()) {
            if (!held.isOf(TrapContent.cocaPowder)) {
                return false;
            }
            each = premium(TrapComponents.getPurity(held).emeralds() * 2);
        } else if (craving.isMix()) {
            Blend blend = matchingMix(held, craving);
            if (blend == null) {
                return false;
            }
            each = mixPrice(blend, held.isOf(TrapContent.blendJointItem));
        } else if (craving.takesBuds() && held.isOf(TrapContent.driedBud(craving.strain()))) {
            each = budPrice(TrapComponents.get(held));
        } else if (craving.takesJoints() && held.isOf(TrapContent.joint(craving.strain()))) {
            each = jointPrice(TrapComponents.get(held));
        } else {
            return false;
        }

        // Take the whole handful, up to what they still want. One item per
        // click would mean eight clicks for a full sale, and the grade is read
        // per stack anyway -- so hand over the good stuff separately if you
        // want paying for it separately.
        int appetite = APPETITE.getOrDefault(customer.getUuid(), UNITS_WANTED);
        int units = Math.min(held.getCount(), appetite);
        if (units <= 0) {
            return false;
        }
        int paid = units * each;

        // Captured BEFORE the decrement: emptying the stack turns it into air,
        // and the receipt would name the wrong thing.
        Text sold = held.getName();

        held.decrement(units);
        TrapMarket.pay(seller, paid);

        int left = appetite - units;
        APPETITE.put(customer.getUuid(), left);
        // Satisfied either when they've had their fill OR when you've run dry.
        // Waiting for the full eight when the player has nothing left to sell
        // left them loitering forever with no way to end it.
        boolean soldOut = !hasMore(seller, craving);
        if (left <= 0 || soldOut) {
            DEALT.add(customer.getUuid());
        }

        ServerWorld world = seller.getWorld();
        world.playSound(null, customer.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 1.0F);
        world.playSound(null, seller.getBlockPos(),
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6F, 1.4F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                customer.getX(), customer.getY() + 1.6, customer.getZ(),
                10, 0.3, 0.3, 0.3, 0.02);
        // A receipt in chat, so a sale leaves a record you can scroll back to
        // rather than a line that flashes past the actionbar and is gone.
        // The item's own name carries its grade and colour already.
        seller.sendMessage(Text.literal("Sold ").formatted(Formatting.GRAY)
                        .append(Text.literal(units + "x ").formatted(Formatting.WHITE))
                        .append(sold)
                        .append(Text.literal("  for  ").formatted(Formatting.GRAY))
                        .append(Text.literal(paid + " emerald" + (paid == 1 ? "" : "s"))
                                .formatted(Formatting.GREEN))
                        // Three outcomes, not two. Reporting "still wants 5"
                        // and then walking off because the seller ran dry reads
                        // as a bug -- the number was true and the conclusion
                        // wasn't, which is worse than saying nothing.
                        .append(Text.literal(left <= 0
                                        ? "   that's the lot"
                                        : soldOut
                                        ? "   you're out -- they'll take it and go"
                                        : "   still wants " + left)
                                .formatted(Formatting.DARK_GRAY)),
                false);
        return true;
    }

    /**
     * Say what they're after, since there's no screen to show it.
     *
     * The name over their head gives the strain; this gives the rest -- what
     * form they'll take and whether they still want any.
     */
    private static void nudge(ServerPlayerEntity seller, WanderingTraderEntity customer,
                              Craving craving) {
        int appetite = APPETITE.getOrDefault(customer.getUuid(), UNITS_WANTED);
        Text message;
        if (appetite <= 0) {
            message = Text.literal("They've had enough. They're off.")
                    .formatted(Formatting.GRAY);
        } else if (craving.powder()) {
            message = Text.literal("They want powder. Put some in your hand.")
                    .formatted(Formatting.GRAY);
        } else if (craving.isMix()) {
            message = Text.literal("They want ").formatted(Formatting.GRAY)
                    .append(Text.literal(craving.isNamedMix() ? craving.mix() : "any mix")
                            .formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal(" -- in your hand.  Sneak-click to send them off.")
                            .formatted(Formatting.GRAY));
        } else {
            message = Text.literal("They want ").formatted(Formatting.GRAY)
                    .append(Text.literal(craving.strain().display())
                            .withColor(craving.strain().colour()))
                    .append(Text.literal(" -- " + craving.form().label.toLowerCase(
                            java.util.Locale.ROOT) + ", in your hand.")
                            .formatted(Formatting.GRAY))
                    .append(Text.literal("  Sneak-click to send them off.")
                            .formatted(Formatting.DARK_GRAY));
        }
        seller.sendMessage(message, true);
        seller.getWorld().playSound(null, customer.getBlockPos(),
                SoundEvents.ENTITY_WANDERING_TRADER_TRADE, SoundCategory.NEUTRAL, 0.5F, 1.1F);
    }

    /** Is the player still carrying anything this customer would buy? */
    private static boolean hasMore(ServerPlayerEntity seller, Craving craving) {
        if (craving.powder()) {
            return lowestPurity(seller) != null;
        }
        if (craving.isMix()) {
            var inventory = seller.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (matchingMix(inventory.getStack(slot), craving) != null) {
                    return true;
                }
            }
            return false;
        }
        return (craving.takesBuds()
                && lowestQuality(seller, TrapContent.driedBud(craving.strain())) != null)
                || (craving.takesJoints()
                && lowestQuality(seller, TrapContent.joint(craving.strain())) != null);
    }

    /** The blend on this stack if it's what they asked for, else null. */
    private static Blend matchingMix(ItemStack stack, Craving craving) {
        if (!stack.isOf(TrapContent.blendBudItem) && !stack.isOf(TrapContent.blendJointItem)) {
            return null;
        }
        Blend blend = TrapComponents.getBlend(stack);
        if (blend == null) {
            return null;
        }
        // "Any mix" takes whatever you've got; a named request takes only that
        // blend, because otherwise asking by name means nothing.
        if (craving.isNamedMix() && !craving.mix().equals(blend.display())) {
            return null;
        }
        return blend;
    }

    /**
     * What a mix fetches, per item.
     *
     * Priced off the joint curve rather than invented from scratch, then
     * scaled by how much work went in: more parts is more buds spent, and a
     * named blend is the thing somebody actually went looking for. A four-part
     * named Fire mix comes out at roughly three times a plain Fire joint,
     * which is about right for four Fire buds plus knowing the recipe.
     */
    private static int mixPrice(Blend blend, boolean rolled) {
        int base = rolled
                ? jointPrice(blend.quality())
                : budPrice(blend.quality());
        float parts = 0.55f + 0.22f * blend.parts().size();
        float fame = blend.named() != null ? 1.45f : 1.0f;
        return Math.max(1, Math.round(base * parts * fame));
    }

    /** The poorest grade of this item the player has on them, or null. */
    private static Quality lowestQuality(ServerPlayerEntity seller, net.minecraft.item.Item item) {
        Quality worst = null;
        var inventory = seller.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            Quality grade = TrapComponents.get(stack);
            if (worst == null || grade.index() < worst.index()) {
                worst = grade;
            }
        }
        return worst;
    }

    private static Purity lowestPurity(ServerPlayerEntity seller) {
        Purity worst = null;
        var inventory = seller.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(TrapContent.cocaPowder)) {
                continue;
            }
            Purity grade = TrapComponents.getPurity(stack);
            if (worst == null || grade.index() < worst.index()) {
                worst = grade;
            }
        }
        return worst;
    }

    /**
     * Send them off yourself: sneak and right-click.
     *
     * Emeralds are paid per hand-over, so ending early costs nothing you have
     * already earned -- this only exists so you aren't stuck with somebody
     * loitering for product you don't intend to sell them.
     */
    private static void dismiss(WanderingTraderEntity customer, ServerPlayerEntity seller, int now) {
        boolean bought = APPETITE.getOrDefault(customer.getUuid(), UNITS_WANTED) < UNITS_WANTED;
        DEALT.add(customer.getUuid());
        startLeaving(customer, seller, now, bought);
        seller.sendMessage(Text.literal(bought
                        ? "Deal's done. They're off."
                        : "You wave them off.")
                .formatted(Formatting.GRAY), true);
    }

    /** Has this customer actually bought something? */
    private static boolean hasTraded(WanderingTraderEntity customer) {
        // Only hand-overs count now: the trade screen is never opened, so the
        // offers' own use counters can never move.
        return DEALT.contains(customer.getUuid());
    }

    /**
     * Send them on their way, with a word about how it went.
     *
     * Two different departures on purpose: a customer who got what they came
     * for and one who waited around for nothing should not feel the same, and
     * the difference is the only feedback telling you a visit was missed.
     */
    private static void startLeaving(WanderingTraderEntity customer, ServerPlayerEntity player,
                                     int now, boolean satisfied) {
        if (LEAVING.containsKey(customer.getUuid())) {
            return;
        }
        LEAVING.put(customer.getUuid(), now);

        ServerWorld world = (ServerWorld) customer.getWorld();
        world.playSound(null, customer.getBlockPos(),
                satisfied ? SoundEvents.ENTITY_WANDERING_TRADER_YES
                        : SoundEvents.ENTITY_WANDERING_TRADER_NO,
                SoundCategory.NEUTRAL, 0.8F, satisfied ? 1.0F : 0.8F);
        world.spawnParticles(satisfied ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
                customer.getX(), customer.getY() + 1.4, customer.getZ(),
                8, 0.25, 0.25, 0.25, 0.01);

    }

    /** Walk them away from the player until they're far enough to vanish. */
    private static boolean tickLeaving(WanderingTraderEntity customer, ServerPlayerEntity player,
                                       int now) {
        Integer since = LEAVING.get(customer.getUuid());
        if (since == null) {
            return false;
        }
        boolean farEnough = player == null
                || !customer.getBlockPos().isWithinDistance(player.getBlockPos(), LEAVE_DISTANCE);
        if (now - since > LEAVE_TICKS || farEnough) {
            LEAVING.remove(customer.getUuid());
            leave(customer);
            return true;
        }
        if (player != null && now % 20 == 0) {
            // Straight away from the player, so the exit reads as deliberate.
            var away = customer.getPos().subtract(player.getPos()).normalize()
                    .multiply(LEAVE_DISTANCE);
            customer.getNavigation().startMovingTo(
                    customer.getX() + away.x, customer.getY(), customer.getZ() + away.z, 0.6);
        }
        return false;
    }

    private static void leave(WanderingTraderEntity customer) {
        DEALT.remove(customer.getUuid());
        APPETITE.remove(customer.getUuid());
        ServerWorld world = (ServerWorld) customer.getWorld();
        world.spawnParticles(ParticleTypes.POOF,
                customer.getX(), customer.getY() + 0.6, customer.getZ(),
                12, 0.25, 0.4, 0.25, 0.01);
        customer.discard();
    }

    // --- what they want ------------------------------------------------------

    /**
     * What one customer turned up for. Exactly one of the three.
     *
     * `mix` is a blend's display name, or the empty string for "any mix at
     * all". Named blends are asked for specifically because that is the payoff
     * for having found one -- a customer who walks up asking for Trinity by
     * name is worth more than the arithmetic says.
     */
    private record Craving(Strain strain, boolean powder, String mix, Contract.Form form) {
        static Craving of(Strain strain, Contract.Form form) {
            return new Craving(strain, false, null, form);
        }

        static Craving forPowder() {
            return new Craving(null, true, null, Contract.Form.EITHER);
        }

        static Craving mixed(String name) {
            return new Craving(null, false, name, Contract.Form.EITHER);
        }

        boolean takesBuds() {
            return form != Contract.Form.JOINTS;
        }

        boolean takesJoints() {
            return form != Contract.Form.BUDS;
        }

        /** Short enough for a nameplate. */
        String formWord() {
            return switch (form) {
                case BUDS -> "bud";
                case JOINTS -> "joints";
                case EITHER -> "any";
            };
        }

        boolean isMix() {
            return mix != null;
        }

        /** True if they asked for one blend in particular rather than any. */
        boolean isNamedMix() {
            return mix != null && !mix.isEmpty();
        }

        /** On the nameplate, so you can tell from across a field whether it's worth walking over. */
        String title() {
            if (powder) {
                return "Customer (powder)";
            }
            if (isMix()) {
                return "Customer (" + (isNamedMix() ? mix : "any mix") + ")";
            }
            return "Customer (" + strain.display() + ", " + formWord() + ")";
        }

        String greeting() {
            if (powder) {
                return "Somebody's heading your way. They want powder.";
            }
            if (isNamedMix()) {
                return "Somebody's heading your way. They're asking for " + mix + " by name.";
            }
            if (isMix()) {
                return "Somebody's heading your way. They want something mixed.";
            }
            return "Somebody's heading your way. They're after " + strain.display()
                    + " -- " + form.label.toLowerCase(java.util.Locale.ROOT) + ".";
        }
    }

    /**
     * What this player could sell, picked at random from what they actually
     * hold. Cravings are drawn from your inventory rather than at random so a
     * visit is always worth answering -- a customer who wants something you've
     * never grown is just a mob walking at you.
     */
    private static Craving cravingFor(ServerPlayerEntity player) {
        var options = new java.util.ArrayList<Craving>();
        var random = player.getWorld().getRandom();
        for (Strain strain : Strain.values()) {
            boolean buds = carries(player, TrapContent.driedBud(strain));
            boolean joints = carries(player, TrapContent.joint(strain));
            if (!buds && !joints) {
                continue;
            }
            // Only ask for a form they could actually satisfy. A customer who
            // turns up demanding joints from somebody holding nothing but buds
            // is a wasted visit, and visits are rare enough to matter.
            Contract.Form form;
            if (buds && joints) {
                form = switch (random.nextInt(4)) {
                    case 0 -> Contract.Form.BUDS;
                    case 1 -> Contract.Form.JOINTS;
                    default -> Contract.Form.EITHER;
                };
            } else {
                form = buds ? Contract.Form.BUDS : Contract.Form.JOINTS;
            }
            options.add(Craving.of(strain, form));
        }
        if (carries(player, TrapContent.cocaPowder)) {
            options.add(Craving.forPowder());
        }
        // Mixes are drawn from what you're actually holding, same as strains,
        // and a named one is listed twice: it's rarer to be carrying, and
        // being asked for Trinity by name should feel like the point of having
        // made it rather than a coin flip.
        for (String name : mixesCarried(player)) {
            options.add(Craving.mixed(name));
            if (!name.isEmpty()) {
                options.add(Craving.mixed(name));
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        return options.get(player.getWorld().getRandom().nextInt(options.size()));
    }

    /**
     * Distinct blends the player is carrying, by display name.
     *
     * Named blends come back as their name; everything else collapses to the
     * empty string, which reads as "any mix" -- there is no point in a
     * customer demanding "Kush + Purp" by its generated title.
     */
    private static java.util.List<String> mixesCarried(ServerPlayerEntity player) {
        var found = new java.util.LinkedHashSet<String>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(TrapContent.blendBudItem) && !stack.isOf(TrapContent.blendJointItem)) {
                continue;
            }
            Blend blend = TrapComponents.getBlend(stack);
            if (blend != null) {
                found.add(blend.named() != null ? blend.display() : "");
            }
        }
        return java.util.List.copyOf(found);
    }

    private static boolean carries(ServerPlayerEntity player, net.minecraft.item.Item item) {
        return player.getInventory().contains(stack -> stack.isOf(item));
    }

    /**
     * One offer per grade, so a customer pays properly for what they're given.
     *
     * Same component-predicate trick as {@link TrapTrades}: a plain item match
     * would let a Swill bud satisfy a Fire offer, and the grade system exists
     * precisely so that can't happen.
     */

    private static int premium(int base) {
        // Always at least one emerald better than the trader, or the low grades
        // round back down to the same price and the premium is invisible.
        return Math.max(base + 1, Math.round(base * PREMIUM));
    }

    /**
     * What a customer pays, per item.
     *
     * Carrying the grade up from Swill is most of the work in this mod, and a
     * single emerald a bud made the whole chain -- breed, grow, cure, grade --
     * come out worse than mining. A rolled joint is worth more than the bud it
     * came from, so the two curves differ rather than one being a scale of the
     * other.
     *
     *          Swill  Mids  Loud  Fire
     *   bud        1     2     4     7
     *   joint      2     4     8    13
     */
    private static int budPrice(Quality grade) {
        return Math.max(1, Math.round(premium(grade.emeralds()) / 2.0F));
    }

    private static int jointPrice(Quality grade) {
        return Math.max(1, premium(grade.emeralds()));
    }

    private static boolean hasCustomer(ServerPlayerEntity player) {
        return CUSTOMERS.values().stream()
                .anyMatch(customer -> customer.player().equals(player.getUuid()));
    }
}
