# Traps

Things in this codebase that fail *silently* or fail in a way that points at
the wrong culprit. Written down because every one of them cost real time, and
most of them look like a different problem than they are.

## The rule that explains most of this

> **"The server computed it correctly" and "the player can see it" are separate
> claims.**

Polymer works by lying to the client about what exists. Any vanilla system that
computes something **client-side** is therefore computing it from a lie and
will disagree with the server.

This is not academic. A trade that completed perfectly on the server rendered
as an empty slot for eight rounds of debugging, and every server-side check
kept confirming a healthy system. The one piece of evidence that broke it open
came from playing: *"if I click the empty slot, I get the emeralds."*

---

## Polymer

### Don't use the vanilla merchant screen for Polymer items

The result slot is computed client-side and cannot be corrected. Symptom: the
trade works, the payout is collectable by clicking a blank square, and the slot
draws empty. Re-sending the slot, removing the component predicate and matching
the required count all lose to the client's own recomputation.

Related upstream, though it doesn't cover the whole failure:
<https://github.com/Patbox/polymer/issues/254>

**Fix:** don't open it. Consume the `UseEntityCallback` (return `SUCCESS`) and
do the exchange in code — see `TrapDealing.handOver`. Container screens
(`GENERIC_9X*`, `HOPPER`, `CRAFTING`) are fine; they only display stacks the
server sets.

### `setOffersFromServer` is a no-op on the server

```java
public void setOffersFromServer(TradeOfferList);
  Code:
     0: return
```

It is the *client's* setter for offers sent to it. The name reads backwards.
Mutate the live list instead — `TradeOfferList extends ArrayList`:

```java
TradeOfferList live = customer.getOffers();
live.clear();
live.addAll(...);
```

Also: `WanderingTraderEntity.fillRecipes()` **appends**, it does not replace.

### Custom data components kick vanilla clients

`DATA_COMPONENT_TYPE` is a synced registry, so a custom component disconnects
anyone who doesn't know it: *"Received N registry entries that are unknown to
this client."*

Register them all with `PolymerComponent.registerDataComponent(...)` — **all**,
not just the new ones. The consequence is that the component's value is then
hidden from clients, so **anything predicting from it client-side will fail.**

Items can also exist with **no component at all** (creative tab, `/give`,
anything minted earlier). Readers that default a missing value make the item
behave correctly while predicates requiring presence reject it. Hand out
creative entries as real stacks: `entries.add(stack)`, not `add(item)`.

### Block sounds and particles come from the carrier

Break, hit and step sounds — and break particles — are predicted client-side
from `getPolymerBreakEventBlockState`, which defaults to the Polymer carrier: a
block picked for having spare blockstates, not for sounding like anything.
Override it to a vanilla block matching the material, and pick the server's
`BlockSoundGroup` to match, since that only covers the place sound.

The **place** sound never reaches the placer at all when `getPolymerItem`
returns something non-placeable: vanilla calls `world.playSound(player, ...)`,
which excludes that player on the assumption their client predicts it. Send it
to them explicitly (`RackItem.place`).

### FULL_BLOCK carriers must be closed shells

The client treats the volume as solid, so any see-through gap shows a world lit
as though the block were still there. Build closed shells with recessed detail
— see `rack_model`, `press_model`, `refiner_model`.

---

## Minecraft 1.21.8

- **`SoundEvents` mixes raw `SoundEvent` and `RegistryEntry.Reference`** with no
  pattern. `BLOCK_CHEST_OPEN` is raw, `AMBIENT_CAVE` is a Reference. `javap`
  each one; guessing cost four compile errors in a single session.
- **`ClickEvent` is a sealed interface**: `new ClickEvent.RunCommand(str)`.
- **`LodestoneTrackerComponent`**, not `LodestoneTracker`. Pass `tracked=false`
  or the needle hunts a lodestone that was never placed.
- **Written book pages truncate silently** at ~14 lines of ~19 chars. Run
  `tools/check_pages.py`.
- **3D item models need their own `display` block**, and its scales should be
  expressed *relative to vanilla's* (gui `0.625`). Absolute values make items
  burst out of their slots.
- **Pick the right `ScreenHandlerType`.** Filler panes are the tell that the
  type is wrong: `HOPPER` is five in a row, `CRAFTING` draws its own arrow and
  result and makes empty slots look intentional.

---

## Debugging, in cost order

Every rung here is cheap, and each one would have ended the eight-round bug
immediately.

1. **`javap -c` the method you're assuming.** Bytecode takes two seconds and
   cannot lie.
2. **Read live state** — `rcon-cli "data get entity <player> Inventory"`.
3. **Log the actual comparison**, not its inputs. `matches=true` is what proved
   the server was right and moved the search to the client.
4. **Search the upstream issue tracker.** When the symptom is outside your own
   code, do this *early*.

### And three that aren't about Minecraft

- **A code path that has never executed hides a cluster of bugs.** Once the
  no-op setter was fixed, three more surfaced in a row behind it.
- **A workaround for bug A becomes bug B once A is fixed.** Delete the
  scaffolding when you fix the root cause.
- **Never delete code with a greedy multi-line regex.** One `re.sub` ate 500
  lines of a 606-line file. Print boundaries, delete by exact line range,
  rebuild. Commit first.
