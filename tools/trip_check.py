#!/usr/bin/env python3
"""Sanity-check the trip curve that drives the high visuals.

The client works out how hard a hit was by dividing the Baked duration by the
strain's base duration -- there is no packet carrying potency. That only works
if the server's application formula and the client's inverse agree, and the
thresholds in TrapCraftClient are only useful if real hits actually reach them.

Both are easy to get wrong in a way you'd never notice in game: the visuals
would just quietly never escalate, or escalate on the first puff.

    python3 tools/trip_check.py
"""

# Kept in step with the Java by hand. If these drift the asserts stop meaning
# anything, so they name their source.
GRADES = {"Swill": 0.6, "Mids": 1.0, "Loud": 1.5, "Fire": 2.0}    # Quality.java
METHODS = {"joint": 1.0, "bong": 1.5, "tlok": 2.2}                # BongBlock/GravityBongBlock
TOLERANCE = [1.0, 0.82, 0.64, 0.46, 0.40]                         # ToleranceStatusEffect
STRAIN_SECONDS = {"haze": 60, "kush": 90, "sunset": 90, "diesel": 100,
                  "purp": 120, "midnight": 140}                   # Strain.java

CHAOS_START, CHAOS_FULL = 1.20, 4.50                              # TrapCraftClient
TRIP_MAX = 8.0


def applied_ticks(seconds: float, grade: float, method: float, damping: float) -> int:
    """What the server actually puts on the player. Mirrors Strain.effects()
    followed by TrapContent.hit() -- note the two separate roundings."""
    base = round(seconds * 20 * grade)
    return max(20, round(base * damping * method))


def derived(ticks: int, seconds: float) -> float:
    """What the client infers back out. TrapCraftClient.onHit()."""
    return ticks / (seconds * 20)


def chaos(trip: float) -> float:
    return min(max((trip - CHAOS_START) / (CHAOS_FULL - CHAOS_START), 0.0), 1.0)


def main() -> None:
    worst_error = 0.0
    lowest = (1e9, "")
    highest = (0.0, "")

    for strain, seconds in STRAIN_SECONDS.items():
        for gname, grade in GRADES.items():
            for mname, method in METHODS.items():
                for level, damping in enumerate(TOLERANCE):
                    intended = grade * method * damping
                    ticks = applied_ticks(seconds, grade, method, damping)
                    got = derived(ticks, seconds)
                    worst_error = max(worst_error, abs(got - intended))
                    label = f"{gname} {strain} via {mname}, tolerance {level}"
                    if got < lowest[0]:
                        lowest = (got, label)
                    if got > highest[0]:
                        highest = (got, label)

    # The round trip is the whole design. A percent of rounding slop is fine;
    # anything more means the two formulas have drifted apart.
    assert worst_error < 0.01, f"round trip off by {worst_error:.4f}"

    print(f"round trip worst case: {worst_error:.5f}  (ok)")
    print(f"weakest hit: {lowest[0]:.2f}  {lowest[1]}")
    print(f"strongest hit: {highest[0]:.2f}  {highest[1]}")
    print(f"spread: {highest[0] / lowest[0]:.1f}x\n")

    # A single hit must be able to reach chaos, or the heavy layers are dead
    # code -- but a mild one must NOT, or they're always on and mean nothing.
    assert chaos(highest[0]) > 0.5, "even the best single hit barely trips"
    assert chaos(GRADES["Mids"] * METHODS["joint"] * TOLERANCE[0]) == 0.0, \
        "a plain mids joint already triggers the heavy layers"

    print("one hit, clear head:")
    for mname, method in METHODS.items():
        for gname, grade in GRADES.items():
            t = grade * method
            print(f"  {gname:<5} {mname:<5}  trip {t:4.2f}  chaos {chaos(t):4.2f}"
                  f"  band {0 if t < 1.6 else 1 if t < 3.2 else 2}")

    print("\nstacking fire tloks (clear head, ignoring decay between hits):")
    t = 0.0
    for hit in range(1, 4):
        t = min(TRIP_MAX, t + GRADES["Fire"] * METHODS["tlok"])
        print(f"  hit {hit}  trip {t:4.2f}  chaos {chaos(t):4.2f}")

    check_blend_amplifiers()
    print("\nall checks passed")


# Mirrors Blend.blendAmplifier(). The Baked amplifier is a single shared
# channel: 0-5 are the six strains, 6-8 are two/three/four-way blends. Anything
# that lands a blend below 6 makes the client render a strain nobody smoked.
BLEND_BASE, MIN_PARTS, MAX_PARTS = 6, 2, 4
STRAIN_COUNT = 6


def blend_amplifier(distinct: int) -> int:
    return BLEND_BASE + min(max(distinct, MIN_PARTS), MAX_PARTS) - MIN_PARTS


def check_blend_amplifiers() -> None:
    print("\nblend amplifiers:")
    # distinct=1 is a same-strain mix like Kush + Kush, which the mixing
    # station accepts. It used to compute 5 -- Sunset -- so the high rendered
    # as a strain that was never in it and didn't count as a blend at all.
    for distinct in range(1, MAX_PARTS + 1):
        amp = blend_amplifier(distinct)
        assert amp >= STRAIN_COUNT, \
            f"{distinct} distinct strains -> amplifier {amp}, which is a STRAIN not a blend"
        assert amp < STRAIN_COUNT + 3, f"amplifier {amp} is past the blend styles"
        print(f"  {distinct} distinct -> amplifier {amp}  (blend{min(max(distinct, 2), 4)})")


if __name__ == "__main__":
    main()
