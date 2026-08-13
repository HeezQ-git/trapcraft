# Somebody who is not from here

## The problem

The city has nobody in it but the people who live in it.

Every customer in the mod is a resident. The casino pulls one
(`TrapFloor.arrive` -> `TrapHomes.freeResident`), the shops pull one
(`TrapShops:1171`, the same call), and the hospital only ever sees a villager
that turned. That was the right correction when it was made -- the strangers
who used to appear out of nowhere were a stand-in for a town that did not
exist, and deleting them is what made the town real.

The town exists now, and the stand-in has become a ceiling.

Measured on the live floor on 2026-08-13:

- The casino's costs are **linear in machines** (51e a beat at 51 cabinets,
  isolated in a 90-second window where the handle was frozen and the vault
  still fell 51e a beat).
- Its income is **capped by the town**: one arrival attempt every three
  seconds server-wide, `room()` concurrent punters, and
  `punterStakeCeiling` *shrinks* the bets as the crowd grows -- so the handle
  is near flat however many cabinets are standing there.
- Break-even wanted ~1,320e of handle a beat. A town of 28 supplies ~840e, and
  ~1,230e at the absolute peak of the night. **At 51 machines the floor could
  not break even at any hour of any day.**

A city whose only customers are its own residents can only ever be as busy as
it is populous. Tourists are the answer to "how do I get busier" that is not
"evict somebody to build another house".

## Shape

A visitor arrives at the edge of town, does one to three things, and leaves.
They are not from here, they do not live here, and they take nothing with
them but what they did not spend.

### Who they are

`TrapVisitors.java`, one tick loop, a list of `Visit` records deliberately
shaped like `TrapFloor.Punter` -- that record has already survived every
restart bug worth having, and the second system to walk a villager across a
town should not re-learn the first one's lessons.

```
Visit { UUID body; ArrayDeque<Errand> itinerary; int purse; long deadline; boolean ill; }
Errand = CASINO | SHOP | WARD
```

**They are not residents.** No `TENANT_TAG`. This is the load-bearing sentence
of the whole design: `TrapHomes.population()` feeds rent, payroll, house
reputation and the casino's own `room()` cap, and a fake resident corrupts all
four at once. A tourist carries `trapcraft_tourist` and nothing else.

### How you know

An out-of-town villager type -- DESERT, JUNGLE, SAVANNA, SNOW, SWAMP or TAIGA
against the PLAINS everybody local wears. Confirmed present in 1.21.8:
`VillagerType` holds all seven, and `VillagerData.withType` takes a
`RegistryEntry<VillagerType>` rather than the bare constant, so it is a
registry lookup at spawn and not an enum assignment.

Different clothes, readable across the square, and it costs **nothing**: no
texture, no model, and -- the reason it was chosen over anything custom -- not
one Polymer carrier. The pack booted with `16 left in
BIOME_TRANSPARENT_BLOCK`, so anything needing a new block or item is a bill
this feature cannot afford to pay.

No name tag. The residents' aqua tags are what a tourist is being read
against, so the absence is part of the signal. While one is actually at a
machine the floor's existing `name  ·  stake` readout still shows, because
that belongs to the cabinet rather than to the visitor.

### Where they come in

`TrapCity.vaultAt()`, **taken to the surface**. The vault is guaranteed to
exist -- no vault, no city -- which makes it the one anchor that cannot be
missing, but it is a hidden thing in a hole and nobody arrives in town by
climbing out of the treasury. So the anchor is its column, not its position:

```
TrapSpawn.near(world, world.getTopPosition(MOTION_BLOCKING_NO_LEAVES, scattered), 6)
```

the `TrapContracts:404` pattern, `MOTION_BLOCKING_NO_LEAVES` so a treetop is
not a pavement, scattered around the column so a crowd is a crowd rather than
a stack, and guarded by a chunk-loaded check -- `getTopY` on an unloaded chunk
is the mistake `TrapHeat:557` already carries a comment about.

Between errands they walk, on `TrapFloor`'s step/deadline/doorway ladder. A
town-length walk is still not a thing the engine will do, and this design does
not pretend otherwise: the fallback is the same one the floor already uses,
and it reads the same way, as somebody coming in off the street.

### What they spend

A purse of 150-600e, drawn low-biased, that is **genuinely outside money**.

Spending it at a player's business puts it into circulation through the
`circulate(+n)` path, so the market index sees the inflation and prices answer
it. That is deliberate and it is the safety catch: money that appears without
the index noticing is silent inflation, and money the index does notice is
self-limiting.

The purse is capped under `TrapHouse.covers(...)`, because a visitor who
breaks the bank is a visitor who took the owner's money away while they were
stood somewhere else entirely.

**The city takes its duty on every tourist transaction, including Gaming duty
on their stakes -- which resident punters do not pay.** Visitors pay the
tourist tax. This is the drain welded to the faucet, and it is the reason this
feature is allowed to exist next to
`2026-08-13-money-sinks-design.md`, which is otherwise right that the economy's
problem is too many taps.

### What they came for

One to three errands, drawn from what the city actually has:

- **CASINO** -- a machine, played through the existing punter path.
- **SHOP** -- a till or a stall, through the existing shopper path.
- **WARD** -- new. A share of visitors arrive already unwell, walk to a free
  ward bed, occupy it a while and pay the 450e fee (270e where the Clinic is
  built) to whoever owns it. This is the only errand with no machinery behind
  it today; `TrapHospitals` currently only knows how to cure a villager that
  turned.

### How many

Enough that it is not an event. Baseline ~4 concurrent visitors so the city is
never empty, scaled by Street Lamps, the Tramway, Paved Roads and the School
to ~10-12, with an attempt every ~15s.

The works multiplier is what finally pays for the works. Right now the city
has 28,000e of unbuilt public works and a purse of ~3,000e, and every one of
them buys an effect you have to squint at a spreadsheet to notice. Tourists
are the first return on that money you can stand in the street and watch walk
past.

### And they do not count against the town

A tourist does not occupy a slot in `room()`. That cap is the town's own
evening and should stay that way; visitors are capacity **on top** of it,
which is precisely the ceiling that made 51 machines unfeedable. This is the
whole economic point of the feature.

## Not doing

- **Registering them as homeless residents.** Smallest possible diff and the
  worst possible idea: it corrupts `population()`, and with it rent, payroll,
  reputation and the casino cap.
- **Arriving by tram.** Thematically perfect and gates the entire feature
  behind 7,000e nobody has spent, against a requirement that this not be rare.
- **Walking in from the edge of the loaded area.** Three restarts already went
  into establishing that villagers will not cross a town.
- **A name tag, particles, or custom clothing.** One marker is a marker; three
  are noise, and the custom one costs carriers the pack has not got.

## Risks

- **The casino becomes a tap again.** Tourists lift the income ceiling on
  purpose, and lifting it too far undoes the thing that makes a floor a
  business. Gaming duty on visitor stakes and the works upkeep are the
  counterweight; the first thing to watch after shipping is the vault's slope
  with the floor idle.
- **A tourist that outlives its Visit.** The failure mode is already written
  down in `TrapFloor.leave`: AI switched off to root them at a machine and
  nothing switching it back on, so they stand at that cabinet for good. A
  tourist has no home to be sent to, so the departure path must `discard()`
  and must never reach `TrapHomes.sendHome`.
- **Restart orphans.** `ServerEntityEvents.ENTITY_LOAD` already discards a
  tagged stranger it does not recognise. Tourists want the same handler and
  the same answer: their trip ended when the server stopped.

## Checks

Source-read tests in the `ResidentTest` style, pinning the two things that
fail silently and expensively:

1. A tourist never receives `TENANT_TAG` -- or the town's whole economy
   inflates behind your back.
2. The departure path discards and does not call `sendHome`/`stayIn` -- or
   every visitor who ever had a night out becomes a statue.
