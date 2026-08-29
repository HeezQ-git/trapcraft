package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The force, the crime, and the four ways they could quietly break the town.
 *
 * Two kinds of check, for the two kinds of thing that can go wrong here.
 *
 * The arithmetic half is real formula testing: {@link TrapMath#crimeOdds} is a
 * product of five multipliers feeding a per-round dice roll, and the
 * difference between "a couple of burglaries a day" and "nothing ever happens"
 * or "the town is on fire" is one factor of a hundred in a conversion nobody
 * can eyeball from inside the game.
 *
 * The other half reads the source, the way {@link WardTest} does and for the
 * same reason: money moving between {@link TrapPayroll} and {@link TrapCity}
 * is not arithmetic, it is a JOIN, and every way it has been got wrong so far
 * presents as an unrelated system misbehaving -- a purse that inflates, shops
 * that quietly stop being able to buy anything, a resident standing in two
 * places at once.
 */
class PoliceTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/heezq/trapcraft/" + file));
    }

    // --- the arithmetic -------------------------------------------------------

    @Test
    void aTownOfTwentyGetsACoupleOfCrimesADay() {
        // Nobody at home, nothing happens: the guard that stops an empty
        // server generating a crime wave nobody is there to see.
        assertEquals(0f, TrapMath.crimeOdds(0, 0f, 0f, false, 0), 1e-6,
                "no residents means no crime -- there is nobody to rob");

        float perDay = TrapMath.crimeOdds(20, 0f, 0f, false, 0)
                * TrapMath.CRIME_ROUNDS_PER_DAY;
        assertTrue(perDay > 0.4f && perDay < 1.2f,
                "a quiet town of twenty should see about one offence a day, not "
                        + perDay + " -- an in-game day is twenty real minutes, so this "
                        + "number is the difference between a city with a crime problem "
                        + "and a city being demolished");

        assertTrue(TrapMath.crimeOdds(20, 0f, 0f, true, 0)
                        > TrapMath.crimeOdds(20, 0f, 0f, false, 0),
                "and more of it at night");
        assertTrue(TrapMath.crimeOdds(20, 1f, 0f, false, 0)
                        > TrapMath.crimeOdds(20, 0f, 0f, false, 0),
                "and more of it when the housing is bad, which is the one driver a "
                        + "player can fix without touching the police at all");
    }

    /**
     * The regression that made this feature unplayable on its first evening.
     *
     * Poverty, heat and darkness used to be three independent MULTIPLIERS, so
     * a poor town at night with a farm running hot ran at 6.8x its own base
     * rate -- twelve offences a day, one every ninety seconds of real time.
     * The shape of the formula, not its constants, was the bug, so this checks
     * the shape: the worst case a town can reach has to stay somewhere a
     * person would still want to live.
     */
    @Test
    void theWorstCaseIsStillATownAndNotASiege() {
        float worst = TrapMath.crimeOdds(20, 1f, 0f, true, 10_000)
                * TrapMath.CRIME_ROUNDS_PER_DAY;
        assertTrue(worst <= TrapMath.CRIME_CEILING + 1e-4,
                "the ceiling has to actually bind: a poor, hot town at night reached "
                        + worst + " a day");
        assertTrue(worst < 3.0f,
                "which at twenty real minutes to the day is still under one offence "
                        + "every seven minutes -- the rate a live server called XDD");

        // And a big city cannot walk itself past it either, which is the half
        // a constant alone would not have fixed.
        assertTrue(TrapMath.crimeOdds(400, 1f, 0f, true, 10_000)
                        * TrapMath.CRIME_ROUNDS_PER_DAY <= TrapMath.CRIME_CEILING + 1e-4,
                "population is unbounded, so the ceiling has to be reached by scale "
                        + "as well as by misery");
    }

    @Test
    void payingForPoliceActuallyBuysSomething() {
        float unpoliced = TrapMath.crimeOdds(20, 0f, 0f, false, 0);
        float policed = TrapMath.crimeOdds(20, 0f,
                TrapMath.deterrence(4, 20, 2), false, 0);
        assertTrue(policed < unpoliced * 0.75f,
                "four kitted coppers in a town of twenty have to make a visible dent, "
                        + "or the dial at the vault is a subscription that buys nothing");

        assertEquals(0f, TrapMath.deterrence(0, 20, 3), 1e-6,
                "an unfunded force deters nothing, however good the kit it isn't wearing");
        assertTrue(TrapMath.deterrence(40, 20, 3) <= TrapMath.TOP_DETERRENCE,
                "and no amount of money buys a town with no crime in it -- an "
                        + "uncapped deterrence would make the top of the dial delete "
                        + "the feature it is supposed to be fighting");
        assertTrue(TrapMath.deterrence(4, 20, 0) < TrapMath.deterrence(4, 20, 3),
                "kit has to matter on its own, or 'and faster' is decoration");
        assertTrue(TrapMath.deterrence(4, 80, 1) < TrapMath.deterrence(4, 20, 1),
                "and the same four coppers must be worth less in a bigger town, or a "
                        + "city never has to fund its force as it grows");
    }

    @Test
    void aRunnerOutpacesAnUnpaidForceAndNotAPaidOne() {
        assertTrue(TrapMath.SUSPECT_PACE > TrapMath.officerPace(0),
                "a force on the minimum budget must be able to LOSE a chase -- that "
                        + "gap is the only place a player can watch what the money buys");
        assertTrue(TrapMath.SUSPECT_PACE < TrapMath.officerPace(TrapPolice.TOP_GEAR),
                "and a fully funded one must be able to win it, or the top of the "
                        + "dial is a tax with no return");
    }

    /**
     * A funded copper wins a fight with a zombie, and walks the wound off.
     *
     * They were dying constantly, and the numbers say why: ten damage a swing
     * against a twenty-health zombie is three seconds of being hit back, no
     * armour at all, and no way to heal afterwards -- a villager regenerates
     * from food and these carry none. Every wound was permanent, so death was
     * only a question of enough nights. Measured live: 25, 38, 7, 21, 21 and
     * 38 out of 38 across one shift.
     */
    @Test
    void aFundedOfficerWinsAndRecovers() {
        float zombie = 20f;
        assertTrue(TrapMath.truncheonHit(TrapPolice.TOP_GEAR) >= zombie,
                "at full kit a swing has to drop a zombie: one swing is one decision "
                        + "pass, a second and a half, and anything slower means the "
                        + "officer spends that time being bitten");
        assertTrue(TrapMath.truncheonHit(0) < zombie / 2,
                "and at no kit it must NOT -- an unfunded force is meant to be fodder, "
                        + "or the budget dial buys nothing");

        assertTrue(TrapMath.officerArmour(0) < TrapMath.officerArmour(TrapPolice.TOP_GEAR),
                "kit has to mean a vest, which they had none of at any grade");
        assertTrue(TrapMath.officerArmour(TrapPolice.TOP_GEAR) <= 15,
                "but not plate -- a force that cannot be hurt is one nobody has to fund");

        assertTrue(TrapMath.officerMend(0) > 0,
                "and they have to heal at all, which is the actual reason the shift kept "
                        + "dying: no food, so no regeneration, so every wound was forever");
        // A full recovery has to be minutes, not seconds: fast enough that a
        // quiet night restores the shift, slow enough that it cannot rescue
        // somebody mid-fight.
        float passes = (float) TrapMath.officerHealth(0) / TrapMath.officerMend(0);
        assertTrue(passes > 20 && passes < 200,
                "healing from empty took " + passes + " passes of 30 ticks; that wants "
                        + "to land between half a minute and a few minutes");
        assertTrue(TrapMath.OFFICER_RETREAT > 0f && TrapMath.OFFICER_RETREAT < 0.5f,
                "and a badly hurt officer breaks off, rather than walking at the next "
                        + "zombie on a tenth of its health");
    }

    /**
     * An army bought once must still cost the city something every morning.
     *
     * A public work is capital: paid for at the vault, owned forever, never
     * looked at again. Every other work in the game is a modifier on somebody
     * else's number, so that is harmless -- but golems are BODIES that kill
     * things, and a permanent free garrison would end the night on a server
     * whose whole police feature is a dial you have to keep feeding. The gate
     * is the only thing standing between "the city funds its force" and "the
     * city funded its force once, in spring".
     */
    @Test
    void golemsWalkOutWithCoppersOrNotAtAll() {
        assertEquals(0, TrapMath.golemGuard(0, 8),
                "a city that never bought the works has no golems, however well it "
                        + "pays the force");
        assertEquals(0, TrapMath.golemGuard(3, 0),
                "and a city that bought them but let its force go has none either -- "
                        + "that is the whole of what stops one purchase opting out of "
                        + "the running cost the rest of this feature is built on");

        assertEquals(TrapMath.GOLEMS_PER_LEVEL, TrapMath.golemGuard(1, 99),
                "one level is one level's worth, not everything the town could staff");
        assertTrue(TrapMath.golemGuard(2, 99) > TrapMath.golemGuard(1, 99),
                "and paying for the next level has to put more of them on the street, "
                        + "or the tiers above the first are a donation");
        assertEquals(2, TrapMath.golemGuard(3, 2),
                "two coppers on the street means two golems, whatever the city owns");
        assertEquals(0, TrapMath.golemGuard(-1, -1),
                "and nothing here may go negative: a save read back wrong would "
                        + "otherwise turn into a negative want and a station that "
                        + "discards its whole yard every round");
    }

    /**
     * The three ways an iron body could quietly ruin the town it guards.
     *
     * Read out of the source, like the money joins above, and for the same
     * reason: none of these is arithmetic. Each is a single call whose absence
     * looks like an unrelated system misbehaving -- residents being executed,
     * a shift that never leaves the yard, an army stood in a corner all night.
     */
    @Test
    void aGolemNeverTurnsOnTheTownAndNeverLaysSiege() throws Exception {
        String police = source("TrapPolice.java");

        assertTrue(police.contains("golem.setPlayerCreated(true)"),
                "a village golem remembers who shoved a villager, and the city's own "
                        + "army executing a resident over a bar fight is not a police "
                        + "force -- playerCreated is what makes canTarget refuse "
                        + "EntityType.PLAYER outright");

        int march = police.indexOf("private static void march(");
        assertTrue(march > 0, "march() must exist -- it is the golem's whole decision");
        String body = police.substring(march, police.indexOf("\n    }", march));
        assertTrue(body.contains("golem.canSee(prey)"),
                "vanilla's monster scan does NOT check line of sight, so a golem stood "
                        + "over a cave has a target all night; without this it buys "
                        + "itself out of the round and lays siege to a floor, which is "
                        + "the exact bug the officers already have a comment about");
        assertTrue(body.contains("startMovingTo"),
                "and it has to actually be SENT somewhere -- a golem left to its own "
                        + "goals wanders ten blocks round wherever it was made, which "
                        + "makes an army of them an ornament in the station yard");
        assertTrue(body.contains("LEASH") && body.contains("LOST"),
                "a golem gets the same leash a copper does; a hundred hit points of "
                        + "iron loose two hundred blocks from its station is not a patrol");
    }

    /**
     * The round has to accumulate, or the beat never leaves the neighbourhood.
     *
     * A sticky LEG fixed the officer who turned round every second and a half.
     * It did not fix the officer who never got anywhere, because the ERRAND
     * was still re-rolled on every arrival -- twenty-two blocks toward a shop,
     * then a fresh roll, four times in ten landing on a ring drawn round their
     * own front door. Twenty-two blocks reaches nothing across a town, so the
     * walk was a drunkard's walk with a spring on it.
     */
    @Test
    void theBeatRemembersWhereItWasGoing() throws Exception {
        String police = source("TrapPolice.java");
        assertTrue(police.contains("BlockPos post;"),
                "a patrol has to remember the far end of its round across legs, or "
                        + "the legs cancel each other out");

        int leg = police.indexOf("private static BlockPos nextLeg(");
        String body = police.substring(leg, police.indexOf("\n    }", leg));
        assertTrue(body.contains("anchor = patrol.post"),
                "and the next leg has to start from that memory rather than rolling a "
                        + "new errand every time one finishes");
        assertFalse(body.contains("random.nextInt(10) < 6"),
                "the ring must not be rolled against the town on every leg either -- "
                        + "four legs in ten aimed back at the station pulled the shift "
                        + "home as fast as the houses pulled it out");

        // And the errand has to be one they can actually be left standing at.
        int worth = police.indexOf("private static BlockPos worthGuarding(");
        assertTrue(worth > 0, "worthGuarding() must exist");
        assertTrue(police.substring(worth, police.indexOf("\n    }", worth))
                        .contains("onTheRound(station"),
                "an errand past the leash is an officer who walks out, gets sent home "
                        + "at 128 blocks, and is handed the same impossible errand "
                        + "again -- a copper pacing one line all night");
    }

    @Test
    void aJointInAPocketIsNotAFine() {
        assertEquals(0, TrapMath.ticket(0, 0, 0, TrapPolice.LOOKS_AWAY),
                "a clean player standing next to a copper is not a revenue stream");
        assertEquals(0, TrapMath.ticket(TrapPolice.LOOKS_AWAY - 1, 0, 0,
                        TrapPolice.LOOKS_AWAY),
                "and neither is somebody carrying less than a pocketful");
        assertTrue(TrapMath.ticket(TrapPolice.LOOKS_AWAY, 0, 0, TrapPolice.LOOKS_AWAY) > 0,
                "a pocketful is");
        assertTrue(TrapMath.ticket(0, 2, 0, TrapPolice.LOOKS_AWAY) > 0,
                "so is walking around hot with nothing on you");
        assertTrue(TrapMath.ticket(4096, 3, 999, TrapPolice.LOOKS_AWAY) <= 600,
                "and it is capped, because a stop-and-search that can empty a wallet "
                        + "is a mugging with a badge on");
    }

    @Test
    void aHaulIsAShareOfWhatWasLyingThere() {
        assertEquals(0, TrapMath.haul(0, 0.3f, 0.7f, 0.5f),
                "an empty mailbox loses nothing");
        int light = TrapMath.haul(1000, 0.35f, 0.75f, 0f);
        int heavy = TrapMath.haul(1000, 0.35f, 0.75f, 1f);
        assertTrue(light < heavy && heavy <= 1000,
                "the roll has to move the number and can never exceed what was there");
        assertEquals(750, heavy,
                "the top of the range is the top of the range -- a burglary that could "
                        + "take more than the box held would mint emeralds");
    }

    // --- the chat line --------------------------------------------------------

    /**
     * Bold must not leak from a headline into the sentence after it.
     *
     * The live report was "wszystko jest szare i pogrubione, źle się to łączy",
     * and it is a real inheritance rule rather than a taste question: a Text's
     * children take its style, and {@code formatted(GRAY)} sets a colour
     * without clearing bold. So the ordinary way of writing a notice makes the
     * whole line bold, nothing stands out, and it is invisible from the server
     * side -- it compiles, it sends, and it only looks wrong in somebody's
     * chat window. This is the cheapest place to see it.
     */
    /**
     * The style a RUN is drawn with, which is not the style it declares.
     *
     * {@code Text.getStyle()} hands back a node's own style, where "bold" is a
     * nullable that reads false whether it was set false or never set at all
     * -- so asserting on it would pass for both the broken and the fixed
     * version and prove nothing. This walks the tree the way the renderer
     * does, merging parents into children, which is the only place the two
     * differ.
     */
    private static List<Boolean> boldRuns(net.minecraft.text.Text line) {
        List<Boolean> runs = new java.util.ArrayList<>();
        line.visit((style, text) -> {
            if (!text.isBlank()) {
                runs.add(style.isBold());
            }
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return runs;
    }

    @Test
    void aHeadlineDoesNotShoutTheWholeLine() {
        // The pattern the whole mod was written in, and the reported bug: a
        // bold root, and every sibling appended to it silently inherits.
        // Asserted rather than described, so this test cannot go vacuous if
        // the inheritance rule ever changes under it.
        var broken = net.minecraft.text.Text.literal("ZATRZYMANIE")
                .formatted(net.minecraft.util.Formatting.AQUA,
                        net.minecraft.util.Formatting.BOLD)
                .append(net.minecraft.text.Text.literal("   Wren")
                        .formatted(net.minecraft.util.Formatting.GRAY));
        assertTrue(boldRuns(broken).get(1),
                "if this run is not bold then Text stopped inheriting style and the "
                        + "check below is measuring nothing");

        var line = TrapNotes.headline("ZATRZYMANIE", net.minecraft.util.Formatting.AQUA)
                .append(TrapNotes.say("   Wren", net.minecraft.util.Formatting.WHITE))
                .append(TrapNotes.say("   Włamanie", net.minecraft.util.Formatting.RED))
                .append(TrapNotes.under("2 dni w celi"));
        List<Boolean> runs = boldRuns(line);
        assertTrue(runs.get(0),
                "the headline itself is the one thing allowed to be bold");
        for (int at = 1; at < runs.size(); at++) {
            assertFalse(runs.get(at),
                    "run " + at + " came out bold -- if everything shouts, the eye has "
                            + "nowhere to land, which is exactly what got reported");
        }
    }

    // --- the joins ------------------------------------------------------------

    @Test
    void theCityPaysTheForceAndNobodyMintsIt() throws Exception {
        String police = source("TrapPolice.java");
        assertTrue(police.contains("TrapCity.spend(want)"),
                "wages have to come OUT of the city purse -- a police force that costs "
                        + "nothing is the whole feature not existing");
        assertTrue(police.contains("TrapPayroll.credit(funded)"),
                "and INTO the town's, because an officer is a townsperson: credit moves "
                        + "money that already exists");
        assertFalse(police.contains("TrapPayroll.earned("),
                "earned() is the one mint left in this mod. A force that called it "
                        + "would be paid with emeralds nobody was taxed for");
    }

    @Test
    void stolenMoneyMovesRatherThanEvaporating() throws Exception {
        String crime = source("TrapCrime.java");
        assertTrue(crime.contains("TrapPayroll.credit(took)"),
                "a thief is a townsperson: what they take out of a mailbox has to land "
                        + "in the purse they will spend it from, or every burglary is a "
                        + "silent deflation the market index never sees");
        assertTrue(crime.contains("TrapPayroll.spend(back)"),
                "and restitution has to come back out of that same purse, which is what "
                        + "makes 'the town could not afford to pay it all back' a real "
                        + "outcome instead of a rounding error");
        assertFalse(crime.contains("TrapMarket.minted("),
                "nothing here mints or destroys. Crime is a TRANSFER, and the moment it "
                        + "stops being one the treasury starts inventing itself");
        assertTrue(crime.contains("TrapPayroll.spend(sprawa.kind.fine())"),
                "the fine too -- a court moves money from the people to the council");
    }

    @Test
    void anOfficerIsNotAlsoSomebodysTenant() throws Exception {
        String police = source("TrapPolice.java");
        assertFalse(police.contains("TENANT_TAG"),
                "a copper's body must not carry a house's tag: the housing census counts "
                        + "by that tag across the whole world, and a station in an "
                        + "unloaded chunk would read as a missing resident and get a "
                        + "second copy spawned on somebody's doorstep");
        String crime = source("TrapCrime.java");
        assertFalse(crime.contains("TrapHomes.TENANT_TAG"),
                "and neither must a suspect, for the same reason");
        assertTrue(police.contains("MOB_CONVERSION"),
                "an officer bitten on the night shift has to be handled here -- "
                        + "TrapHospitals' handler ignores them because they are nobody's "
                        + "tenant, so without this the body survives as a zombie villager "
                        + "wearing a rank forever");
    }

    @Test
    void theBudgetFailsPoorRatherThanFailingClosed() throws Exception {
        String police = source("TrapPolice.java");
        int payday = police.indexOf("private static void payday");
        assertTrue(payday > 0, "payday must exist -- it is the only place the force is paid");
        String body = police.substring(payday, payday + 1400);
        assertTrue(body.contains("while (want > 0 && !TrapCity.spend(want))"),
                "a city that is 200e short must lose a copper, not its police: a force is "
                        + "not a discrete bill and spending down in lumps is what makes "
                        + "an underfunded town a slower story than a bankrupt one");
        assertTrue(body.contains("paidOn == day"),
                "and it must be gated on the day, or eight restarts in an afternoon pay "
                        + "the force eight times out of the same treasury");
    }

    /**
     * A block you survey FROM has to be walkable to the flood fill.
     *
     * The fill starts at the anchor's own cell, so an anchor that reads as
     * solid never leaves the block it began in -- the room comes back
     * {@code buried}, every checklist prints that as "not sealed", and a
     * perfectly good building is told to go and find a hole that is not there.
     * Live report, first station anybody built: "wszystko jest szczelne, nie
     * ma żadnej dziury".
     *
     * The cause was a hand-maintained list of block names two files away from
     * the blocks. This checks the list is gone, because a list is a bug with a
     * delay on it and the next anchor would have paid for it again.
     */
    @Test
    void everySurveyAnchorIsTransparentToTheFill() throws Exception {
        String homes = source("TrapHomes.java");
        int open = homes.indexOf("public boolean open(int x, int y, int z)");
        assertTrue(open > 0, "Ground.open must exist -- it is what the fill walks on");
        String body = homes.substring(open, open + 1400);
        assertTrue(body.contains("instanceof SurveyAnchor"),
                "the fill has to ask the BLOCK whether it is an anchor. Naming them "
                        + "one by one here is what shipped a sealed police station "
                        + "reporting a hole in itself");
        assertFalse(body.contains("TrapContent.hospital") || body.contains("TrapContent.police")
                        || body.contains("TrapContent.mailbox"),
                "and the old name list must be gone, or it will drift again");

        for (String block : List.of("MailboxBlock", "HospitalBlock", "PoliceBlock")) {
            assertTrue(source(block + ".java").contains("SurveyAnchor"),
                    block + " hands its own position to TrapHomes.look, so it has to "
                            + "declare itself an anchor or its own survey starts inside it");
        }
    }

    /**
     * "Bricked over" and "full of holes" are opposite faults.
     *
     * Both arrive as {@code sealed == false}, and reporting them as one was
     * the second half of the same bug: the message sent somebody hunting a
     * draught in a building that had none. The leak's coordinates are the
     * other half -- a verdict is not a job until it says where.
     */
    @Test
    void aFaultSaysWhichFaultAndWhere() throws Exception {
        for (String office : List.of("TrapPolice.java", "TrapHospitals.java")) {
            String text = source(office);
            int fault = text.indexOf("public static String fault(");
            assertTrue(fault > 0, office + " must have a fault() -- it is the checklist");
            String body = text.substring(fault, fault + 1400);
            assertTrue(body.indexOf("reading.buried()") > 0
                            && body.indexOf("reading.buried()") < body.indexOf("!reading.sealed()"),
                    office + ": buried has to be tested BEFORE not-sealed, or a bricked-over "
                            + "anchor is reported as a hole that does not exist");
            assertTrue(body.contains("reading.leak()"),
                    office + ": a leak the survey located and did not print is a hole "
                            + "nobody can go and plug");
        }
    }

    /**
     * A patrol has to keep the destination it was given.
     *
     * A villager Brain drops a walk target the moment it has none, so the
     * target must be re-asserted every pass -- but re-ASSERTED, not re-rolled.
     * The first version picked a fresh random spot on each pass, thirty ticks
     * apart, so an officer turned round a second and a half after being sent
     * anywhere. Measured live: six of seven officers inside seven blocks of
     * their own front door.
     *
     * The guard lives in stride() rather than beat() because the golems walk
     * the same rounds and need the same promise; beat() is now the two lines
     * that turn a leg into a villager walk target.
     */
    @Test
    void anOfficerKeepsWalkingToTheSamePlace() throws Exception {
        String police = source("TrapPolice.java");
        int beat = police.indexOf("private static BlockPos stride(");
        assertTrue(beat > 0, "stride() must exist -- it is what a copper does all day");
        String body = police.substring(beat, police.indexOf("\n    }", beat));
        assertTrue(body.contains("patrol.beat == null") && body.contains("now > patrol.by"),
                "a new leg may only be picked when there is none, they arrived, or the "
                        + "patience ran out -- an unconditional re-roll is the oscillation "
                        + "that kept the whole shift on the doorstep");
        assertTrue(body.indexOf("nextLeg(") > body.indexOf("patrol.beat == null"),
                "and the roll has to sit INSIDE that guard, not before it");

        assertFalse(police.contains("Heightmap.Type"),
                "and no heightmap in the beat: aimed at a house or a shop it returns the "
                        + "ROOF, so every leg was a walk to somewhere unpathable");
    }

    /**
     * The police use the mod's definition of product, not their own.
     *
     * TrapPolice shipped with a hand-typed list that missed the blend line, so
     * a Trinity joint -- the most valuable thing anybody rolls -- did not
     * register as contraband at all. There is exactly one right answer to
     * "is this product" and a raid has been using it since it shipped.
     */
    @Test
    void aTrinityJointIsContraband() throws Exception {
        String police = source("TrapPolice.java");
        int scan = police.indexOf("private static int contraband(");
        assertTrue(scan > 0, "contraband() must exist -- it is what a search finds");
        String body = police.substring(scan, scan + 1400);
        assertTrue(body.contains("TrapContent.isContraband(stack)"),
                "the search has to go through the shared definition, or the police and "
                        + "the raids disagree about what is even illegal");
        assertFalse(body.contains("carriesQuality"),
                "carriesQuality() is the QUALITY-carrying subset -- single strains only. "
                        + "Using it as the contraband test is what made blends invisible");

        String content = source("TrapContent.java");
        int shared = content.indexOf("public static boolean isContraband(");
        assertTrue(shared > 0, "TrapContent.isContraband must exist");
        assertTrue(content.substring(shared, shared + 900).contains("blendJointItem"),
                "and it must cover the blend line, which is the half that was missing");
    }

    /**
     * A copper only goes after what a copper can actually see.
     *
     * The sight box is 34 blocks a side at full kit, and a box does not care
     * about walls or floors -- so underground it contains every mob in every
     * cave beneath the town. An officer sent at one of those walks into the
     * nearest wall and stays there, because the target never leaves. Seven of
     * them doing it at once is the photograph that came back from the live
     * server: the whole shift piled in one corner of its own station, shoving
     * each other, while the arrest count sat at zero and the kill count
     * climbed.
     */
    @Test
    void aPatrolDoesNotChaseThroughWalls() throws Exception {
        String police = source("TrapPolice.java");
        int walk = police.indexOf("private static void walk(");
        assertTrue(walk > 0, "walk() must exist -- it is the officer's whole decision");
        String body = police.substring(walk, police.indexOf("// 4. Nothing doing", walk));
        assertTrue(body.contains("officer.canSee(mob)"),
                "the monster scan has to require line of sight, or the beat is spent "
                        + "laying siege to a floor with a cave under it");
        assertTrue(body.contains("officer.canSee(body)"),
                "and so does the suspect scan, which has the same problem for the same "
                        + "reason -- a villain through a wall is not a chase, it is a stall");
    }

    /**
     * Seven officers must not all be sent to the same block.
     *
     * Villagers have collision. A shift given one shared destination is a
     * shift that arrives as a heap, and the heap was on the station's own
     * doorstep because the station was the fallback target.
     */
    @Test
    void aShiftFansOutRatherThanStacking() throws Exception {
        String police = source("TrapPolice.java");
        int leg = police.indexOf("private static BlockPos nextLeg(");
        assertTrue(leg > 0, "nextLeg() must exist");
        String body = police.substring(leg, leg + 1200);
        assertFalse(body.contains("anchor = station.sign;"),
                "the nick itself must never be a beat target -- it is where they already "
                        + "are, and sending everybody there is how the pile forms");
        assertTrue(body.contains("RING_MIN"),
                "with nothing worth guarding they should walk a ring around the town, "
                        + "which puts seven officers on seven different bearings");
    }

    /**
     * The garrison has to be able to walk out of wherever it is made.
     *
     * A golem is 1.4 blocks wide and cannot open a door, so a one-block
     * doorway is a wall to it. Made round the station sign -- which the
     * inspection's flood fill proves is INSIDE the building -- the city's
     * whole army lives in the lobby, which is exactly what the live server
     * had: five of them in the front corridor and a town with no patrol.
     */
    @Test
    void aGolemIsStoodSomewhereItCanWalkOutOf() throws Exception {
        String police = source("TrapPolice.java");

        int forge = police.indexOf("private static IronGolemEntity forge(");
        assertTrue(forge > 0, "forge() must exist");
        assertFalse(police.substring(forge, police.indexOf("\n    }", forge))
                        .contains("station.sign"),
                "a spot searched round the sign is a spot inside the station, and a "
                        + "golem does not fit back out through the door");

        int street = police.indexOf("private static BlockPos street(");
        assertTrue(street > 0, "street() must exist -- it is the only thing keeping the "
                + "garrison outdoors");
        String spot = police.substring(street, police.indexOf("\n    }", street));
        assertTrue(spot.contains("outdoors(world"),
                "and it has to actually test for a roof; headroom alone happily accepts "
                        + "the middle of somebody's front room");
        assertTrue(spot.contains("worthGuarding("),
                "the addresses come from the round, which is what spreads the garrison "
                        + "across the town instead of piling it in one yard");
        assertTrue(spot.contains("isChunkLoaded"),
                "reading a blockstate out in an unloaded chunk force-generates terrain "
                        + "from a tick");

        assertTrue(police.contains("unwall(world, station, body)"),
                "and the ones already walled in have to be let out -- muster() re-adopts "
                        + "anything alive and tagged, so they outlive the fix otherwise");
    }

    @Test
    void aStationCannotStaffMoreThanItHouses() throws Exception {
        String police = source("TrapPolice.java");
        assertTrue(police.contains("Math.min(cells(), funded / WAGE)"),
                "money with nowhere to sleep hires nobody -- the cell cap is the only "
                        + "thing keeping the BUILDING in a feature whose dial is at the "
                        + "vault, and without it the block is decoration");
    }

    /**
     * A crime has to RING somebody.
     *
     * The bug this exists to prevent shipped and lived for the whole life of a
     * world: every part of the chase was built and correct -- suspect stands
     * up, runs, officer sees, chases, cuffs -- and `callOut` had exactly one
     * caller, a pillager raid. So a theft told the police nothing, and the
     * only arrests possible were a copper walking past a runner by luck.
     *
     * The books were the tell and nobody read them: 130 cold, 0 solved,
     * 49,504e stolen, 0 recovered, with seven funded top-kit officers on the
     * street. Every number in this file was right and the wire between two
     * files was missing, which is exactly the shape a formula test cannot see.
     */
    @Test
    void aCrimeCallsThePolice() throws Exception {
        String crime = source("TrapCrime.java");
        int open = crime.indexOf("private static void openCase(");
        assertTrue(open > 0, "openCase moved -- this test is reading the wrong thing");
        String body = crime.substring(open, crime.indexOf("\n    }", open));
        assertTrue(body.contains("TrapPolice.callOut("),
                "openCase must dispatch the police, or a theft is a suspect running "
                        + "away from nobody and the whole force is a wage bill");
    }

    /**
     * And the call has to reach the town it is policing.
     *
     * The second half of the same failure, and it would have hidden the fix:
     * with the call wired up but bounded by BEAT_REACH, the live town had
     * three of its twenty-eight houses inside the catchment. A force that can
     * only attend an ninth of the burglaries against it looks, from the
     * street, identical to one that is never told at all.
     *
     * The margins are the part worth pinning. Each bound sits a STRIDE inside
     * the next, so arriving at the far end of a call leaves an officer short
     * of the leash, and the leash short of the distance that teleports them
     * home. Collapse either gap and you get the documented pacing bug: walk
     * out, trip the bound, get sent back, get handed the same call again.
     */
    @Test
    void aCallOutReachesFurtherThanABeat() throws Exception {
        String police = source("TrapPolice.java");
        assertTrue(police.contains("withinCall(station, where)"),
                "callOut must use the call catchment, not the beat's");

        int leash = constant(police, "LEASH");
        int lost = constant(police, "LOST");
        int stride = constant(police, "STRIDE");
        int beatReach = leash - stride;
        int callLeash = lost - stride;
        int callReach = callLeash - stride;

        assertTrue(callReach >= beatReach * 2,
                "a shout has to reach at least twice as far as pottering, or the "
                        + "catchment is still smaller than the town it is policing");
        assertTrue(callLeash < lost,
                "an officer answering a call must never be far enough out to be "
                        + "teleported home mid-chase");
        assertTrue(callReach + stride <= callLeash,
                "and arriving at the far end of a call must leave them inside the "
                        + "stretched leash, slop and all");
        assertTrue(police.contains("answering(world, station) ? CALL_LEASH : LEASH"),
                "the leash has to stretch for a live call, or the officer turns for "
                        + "home halfway there and paces the same line all night");
    }

    /** Read `private static final int NAME = <digits>;` out of the source. */
    private static int constant(String source, String name) {
        var match = java.util.regex.Pattern
                .compile("int " + name + " = (\\d+);").matcher(source);
        assertTrue(match.find(), name + " is no longer a plain literal");
        return Integer.parseInt(match.group(1));
    }
}
