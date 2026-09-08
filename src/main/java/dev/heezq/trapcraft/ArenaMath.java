package dev.heezq.trapcraft;

/**
 * The arena's numbers, away from Minecraft so a plain JUnit run can reach
 * them -- the same split as {@link TrapMath}.
 *
 * Everything the guide book quotes about the boss is a constant here, and
 * every rule that decides who gets what is a function of plain numbers, so
 * a retune cannot leave the book lying and a share formula that leaks money
 * fails a test before it fails a server.
 */
public final class ArenaMath {
    private ArenaMath() {
    }

    // --- the event ------------------------------------------------------------

    /** Fewer people online than this and the omen never rolls. */
    public static final int MIN_PLAYERS = 2;
    /** How often the omen is rolled, in real time. */
    public static final int ROLL_PERIOD_TICKS = 20 * 60 * 20;
    /** The odds per roll. About one event per two and a half hours of company. */
    public static final float ROLL_CHANCE = 0.14F;
    /** Quiet time after an event ends before the next can roll. */
    public static final int COOLDOWN_SECONDS = 90 * 60;
    /** The gathering window: from the omen to the boss rising. */
    public static final int GATHER_TICKS = 20 * 120;
    /** The fight's clock. Past it the watcher leaves without a word. */
    public static final int FIGHT_TICKS = 20 * 60 * 10;
    /** How long a knocked-out player sits in the stands. */
    public static final int KNOCKOUT_TICKS = 20 * 20;
    /** What a knockout hands the boss, as a fraction of its maximum. */
    public static final float KNOCKOUT_HEAL = 0.08F;
    /** Nobody is fought below this; the void below the pit sends you back. */
    public static final int VOID_Y = 40;

    // --- the boss -------------------------------------------------------------

    public static final int BASE_HEALTH = 400;
    public static final int HEALTH_PER_EXTRA = 220;
    /** No single hit takes more than this share of the maximum. */
    public static final float HIT_CAP = 0.06F;
    public static final int MELEE_DAMAGE = 8;
    public static final int SLAM_DAMAGE = 7;
    public static final int ORB_DAMAGE = 6;
    /** What a parried orb does to the boss. */
    public static final int PARRY_DAMAGE = 25;
    public static final int STARE_DAMAGE = 3;
    public static final int LIGHTNING_DAMAGE = 9;
    public static final int GRIP_SLAM_DAMAGE = 10;
    /** Damage the others must deal to free somebody held, per player in the fight. */
    public static final int GRIP_RELEASE_PER_PLAYER = 12;
    public static final int GRIP_TICKS = 100;
    public static final int STARE_TICKS = 60;
    /** Below these percentages the next phase begins. */
    public static final int PHASE_TWO_AT = 66;
    public static final int PHASE_THREE_AT = 33;
    /** Groza stacks: amplifier 0..3. */
    public static final int DREAD_MAX = 3;
    public static final int DREAD_TICKS = 20 * 15;
    /** How close another player must stand for Groza to fade. */
    public static final double COMPANY_RANGE = 4.0;

    // --- the loot -------------------------------------------------------------

    public static final int BOUNTY_BASE = 2500;
    public static final int BOUNTY_PER_PLAYER = 1200;
    public static final int DIRTY_BASE = 12;
    public static final int DIRTY_PER_PLAYER = 6;
    public static final int XP_TOTAL = 1200;
    public static final int ADRENALINE_PHASE_TICKS = 20 * 20;
    public static final int ADRENALINE_WIN_TICKS = 20 * 180;
    /** The Eye's reveal: how far, for how long, how often. */
    public static final int EYE_RANGE = 40;
    public static final int EYE_REVEAL_TICKS = 20 * 8;
    public static final int EYE_COOLDOWN_TICKS = 20 * 90;

    // --- the other bosses ---------------------------------------------------------
    // Two of them walk up and hit; nine on a swing where the witness has eight,
    // because the witness also stares and the others only bite.
    public static final int HEAVY_MELEE_DAMAGE = 9;

    // Bandyta. Stawka is the one debuff that is also a buff: every stack is
    // fifteen percent more taken and a point and a half more dealt.
    public static final int LEVER_DAMAGE = 9;
    public static final int CHIP_DAMAGE = 5;
    public static final int CHIPS = 12;
    public static final int ROULETTE_DAMAGE = 8;
    public static final int STAKE_MAX = 3;
    public static final int STAKE_TICKS = 20 * 20;
    public static final float STAKE_TAKEN_PER_STACK = 0.15F;
    public static final float JACKPOT_HEAL = 0.06F;
    public static final int DOUBLE_TICKS = 20 * 8;
    public static final int CASH_TICKS = 20 * 5;
    public static final int CASH_EMERALDS = 8;
    public static final float CASH_TAKEN = 1.5F;
    public static final int LEVER_SPIN_TICKS = 20 * 30;
    public static final int LEVER_COOLDOWN_TICKS = 20 * 300;

    // Król Szczurów. The rats are the fight; the king bites harder for every
    // one still alive, so the room has to choose between him and them.
    public static final int TAIL_DAMAGE = 7;
    public static final double TAIL_RANGE = 4.5;
    public static final int ERUPT_DAMAGE = 10;
    public static final int PUDDLE_DAMAGE = 2;
    public static final int PUDDLE_TICKS = 20 * 8;
    public static final int PLAGUE_MAX = 3;
    public static final int PLAGUE_TICKS = 20 * 15;
    public static final int RAT_HEALTH = 8;
    public static final int RAT_DAMAGE = 3;
    public static final int RAT_LIFE_TICKS = 20 * 60;
    public static final int RATS_MAX = 12;
    public static final float RAT_BITE_BONUS = 0.05F;
    public static final float RAT_BITE_CAP = 1.6F;
    public static final int CROWN_BLINK = 8;
    public static final int CROWN_COOLDOWN_TICKS = 20 * 60;

    // Sztorm. Nothing to hide behind but copper and fire, on purpose.
    public static final int BOLT_DAMAGE = 9;
    public static final double ROD_RANGE = 3.5;
    public static final int HAIL_DAMAGE = 3;
    public static final int HAIL_FREEZE_TICKS = 200;
    public static final double FIRE_RANGE = 3.0;
    public static final int SHOCK_DAMAGE = 6;
    public static final int STATIC_DAMAGE = 3;
    public static final int TWISTER_DAMAGE = 4;
    public static final double WIND_PUSH = 0.055;
    public static final float DESCEND_TAKEN = 1.5F;
    public static final int HEART_COOLDOWN_TICKS = 20 * 45;

    /** Health for a fight this many people joined. Never below one player's worth. */
    public static int bossHealth(int players) {
        return BASE_HEALTH + HEALTH_PER_EXTRA * Math.max(0, players - 1);
    }

    /** The most one hit may take off. */
    public static float hitCap(float maxHealth) {
        return Math.max(1.0F, maxHealth * HIT_CAP);
    }

    /** 1, 2 or 3 for a health fraction of 0..1. */
    public static int phaseOf(float fraction) {
        int percent = Math.round(fraction * 100.0F);
        if (percent <= PHASE_THREE_AT) {
            return 3;
        }
        return percent <= PHASE_TWO_AT ? 2 : 1;
    }

    public static int bountyPool(int players) {
        return BOUNTY_BASE + BOUNTY_PER_PLAYER * Math.max(1, players);
    }

    /**
     * Split the pool: half evenly, half by damage.
     *
     * Half evenly so turning up and swinging is never worth nothing, half by
     * damage so carrying the fight is. The shares round down and the pool is
     * never exceeded -- see the test -- because a share formula is the one
     * place a boss can quietly mint.
     */
    public static int[] bountyShares(float[] damage, int pool) {
        int[] shares = new int[damage.length];
        if (damage.length == 0) {
            return shares;
        }
        float total = 0.0F;
        for (float dealt : damage) {
            total += Math.max(0.0F, dealt);
        }
        for (int i = 0; i < damage.length; i++) {
            float even = 0.5F / damage.length;
            float earned = total <= 0.0F ? even : 0.5F * Math.max(0.0F, damage[i]) / total;
            shares[i] = (int) Math.floor(pool * (even + earned));
        }
        return shares;
    }

    /** How the bandit hits somebody with this many Stawka stacks: 1 = as written. */
    public static float stakeMultiplier(int stacks) {
        return 1.0F + STAKE_TAKEN_PER_STACK * Math.max(0, stacks);
    }

    /** How many rats a swarm calls in this phase. */
    public static int ratsFor(int phase) {
        return Math.min(RATS_MAX, 6 + 2 * Math.max(1, phase));
    }

    /** How the rat king bites with this many rats alive: capped, or a swarm is a one-shot. */
    public static float ratBite(int rats) {
        return Math.min(RAT_BITE_CAP, 1.0F + RAT_BITE_BONUS * Math.max(0, rats));
    }

    /** Damage the room must land to free a held player. */
    public static int gripRelease(int players) {
        return GRIP_RELEASE_PER_PLAYER * Math.max(1, players);
    }

    public static int dirtyBlocks(int players) {
        return DIRTY_BASE + DIRTY_PER_PLAYER * Math.max(1, players);
    }

    /**
     * Whether an omen may roll now.
     *
     * @param secondsSinceLast seconds since the last event ended, or a huge
     *                         number when there has never been one
     * @param online           players on the server
     * @param roll             a uniform 0..1
     */
    public static boolean omenRolls(long secondsSinceLast, int online, float roll) {
        return online >= MIN_PLAYERS && secondsSinceLast >= COOLDOWN_SECONDS && roll < ROLL_CHANCE;
    }

    /** "1:32" for a tick count, for the countdown bar. */
    public static String clock(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }
}
