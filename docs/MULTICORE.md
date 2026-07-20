# MULTICORE.md — Region multi-core design (P2)

Status: DESIGN ACCEPTED for M0/M1; M2+ pending owner review of the OPEN
QUESTIONS section. Owner-reviewed pass demanded by MASTERPLAN §"P2 — Region
multi-core (the moat)".
Prereq reading: docs/MASTERPLAN.md §P2, docs/PERSISTENCE.md §"Why this
survives multi-core", docs/COMMUNITY-INTEL.md (multi-core intel), MASTERPLAN
§2.2 (differential fixtures).

This is an engineering document with decided positions, not a menu of
options. Genuinely open calls are quarantined in the final OPEN QUESTIONS
section for owner review; everything above it is the plan we are building.

---

## 0. Thesis

The intel is unambiguous [PERF §2, VRI §3.5, DIR]: Minestom's acquirable API
is a dead end (global lock, ownership holes, zero adoption), and naive
cross-thread locking killed every prior community attempt — "the redstone
thread and the entity tick thread would constantly lock." The only shape the
community has ever validated is **Folia/MCHPRS-style region ownership**:
single-threaded islands that own their state outright, no shared mutable
state between islands, and all cross-island interaction expressed as explicit
messages applied at tick boundaries.

Minecom's twist that no prior attempt had to carry: every seam introduced by
regionization must stay **observably vanilla**. A parallelized server that is
5% faster but 0.5% different is worthless to this project — the parity
scorecard is the product. So every message-boundary decision below is
justified against vanilla's own update order, and every timing skew a message
introduces is either (a) proven to be below observability, or (b) written
into a *difference sheet* and covered by a cross-border differential fixture
(MASTERPLAN §2.2).

Design-first, code-second. The migration path is **region=world**: the
scheduler ships first as a single region owning the entire world, provably
identical to today's behavior, and regions are subdivided only once the
scheduler abstraction, the message layer, and the fixtures that police them
all exist.

---

## 1. Ownership unit

### 1.1 The region

A **region** is the unit of both runtime ownership and persistence. It owns a
contiguous set of chunks and everything anchored to them:

- every loaded chunk's block data and block entities,
- every entity whose current chunk belongs to the region,
- every scheduled block tick, random tick, and fluid/redstone update queued
  for those chunks,
- the region's slice of any subsystem's per-position state (redstone tracked
  positions, hopper cooldowns, crop positions, fire countdowns — all of which
  are *already* keyed by `Point`/chunk in the current code).

A region ticks on **exactly one thread at a time**. Within a region there is
zero concurrency: it is the same single-threaded world the code assumes
today, just one of N such worlds. This is the invariant the whole design
protects — code inside a region tick never sees a partially-updated
neighbour, never takes a lock, and never reads another region's mutable
state.

The persistence layer was built for this day: the RegionStore shard
(`entities/r.<rx>.<rz>.json.gz`, 32×32 chunks) is already the save/load unit,
and PERSISTENCE.md §"Why this survives multi-core" states the runtime
ownership unit is the same region. The persistence region grid (Anvil's
32×32) is the *coarsest* legal ownership grid; runtime regions may be finer
(see §1.3) but never straddle a persistence-shard boundary in a way that
forces two threads to write one shard. **Decided:** a runtime region's chunk
set is always a union of whole persistence regions in M2's static grid, and
in M4's dynamic model a runtime region owns whole chunks whose shards it
flushes alone — the coordinator never has two threads touch one shard file.

### 1.2 Ownership is total and exclusive

**Decided positions:**

- Every entity, block entity, and scheduled tick belongs to exactly one
  region at any instant. There is no "shared" or "borrowed" state. This is
  the MCHPRS precedent and the property the persistence design already
  demands.
- Ownership is derived from position: `regionOf(point)` is the single source
  of truth. An entity's owning region is `regionOf(entity.position)`,
  recomputed at the tick boundary when it moves (see §3, entity crossing).
- The player list, connection I/O, and the persistence coordinator are
  **global**, not region-owned (see §4). A player's *gameplay* entity is
  region-owned like any other entity; its *connection* is not.

### 1.3 Region size and the merge policy

Folia uses dynamic merging around an ~8-chunk radius: regions that come
within a threshold distance of each other merge into one, and split back
apart when the activity that joined them subsides. The reason is correctness
under load, not raw parallelism — two mobs fighting across what would
otherwise be a region border must be in the same region so their interaction
stays single-threaded and vanilla-ordered.

**Decided positions:**

- **M2 ships a static grid** (region = a fixed block of persistence regions,
  configurable, default 1 persistence region = 32×32 chunks). Static first
  because it is trivially verifiable — the boundaries are known, so the
  cross-border fixtures know exactly where to aim.
- **M4 adds dynamic merge/split**, Folia-style: a region grows to absorb a
  neighbour when an *interaction that must not cross a border* is detected
  approaching the border (an entity within the merge radius of the border, a
  redstone/piston update queued against a border cell, an explosion whose
  radius reaches the border). Merge radius default = 8 chunks, matching
  Folia's validated figure and the tick-view distance below which vanilla
  itself guarantees adjacency effects.
- **Merge is always safe, split is conditional.** Merging two regions is
  always correct (it just serializes more work onto one thread). Splitting is
  only legal when no in-flight interaction spans the proposed boundary — the
  same predicate that triggered the merge, now false for a full settle
  window. When in doubt, stay merged. Correctness dominates parallelism.
- **The degenerate size is region=world** (M1): one region owns everything,
  the merge policy never fires, and behavior is bit-identical to today. This
  is both the migration path and the permanent A/B baseline (§5.2).

---

## 2. What crosses a region border, and how

Every vanilla mechanic that can act across a chunk boundary must be
inventoried, because at some region size that chunk boundary *is* a region
boundary. Each becomes a **message** delivered at a tick boundary. This
section is the difference sheet's source of truth.

### 2.0 The message model

- A region never mutates another region's state directly. Instead it enqueues
  a `RegionMessage` into the *target* region's inbox.
- Messages are **applied at the next-tick boundary**, before the target
  region's own tick body runs, in a deterministic order (see §2.7). This is
  Folia's teleport model generalized: the classic Folia case (an entity
  teleporting to another region) is applied on the target thread at the start
  of its next tick.
- A message is a pure value (record). It carries no live references into the
  source region's mutable state — only copied/serialized data or stable
  identifiers (UUID, `Point`, block state, ItemStack snapshot). This is what
  makes the hand-off allocation-free of shared mutability.

The key parity question for every mechanic below: **does deferring the effect
to the next tick change what a player observes vs. vanilla, which applies it
this tick?** The answer is mechanic-specific and recorded per row.

### 2.1 Entity border crossing

- **Mechanic:** an entity's movement this tick carries it from a chunk owned
  by region A into a chunk owned by region B.
- **Message:** `EntityHandoff{uuid, snapshot, targetRegion}`. A removes the
  entity from its world at the tick boundary; B re-adds it at the start of its
  next tick from the snapshot (Folia's exact model).
- **Vanilla timing:** vanilla moves the entity and continues ticking it the
  *same* tick in the same single thread. Under regions, the entity is ticked
  by A up to the boundary, hands off, and is next ticked by B one tick later.
  **Difference sheet:** an entity gets 0 ticks of "double processing" and, in
  the worst case, its first B-tick is one tick after it would have been in
  vanilla for the frame in which it crossed. This is sub-observable for
  movement (position is carried exactly in the snapshot; velocity integrates
  identically) but must be pinned for **AI decisions that read cross-border
  state** — an entity crossing into B cannot, on its crossing tick, path
  toward a mob still owned by A. **Decided:** handoff happens at the boundary
  *after* A's full tick, so the entity completes a whole vanilla tick under A
  and a whole vanilla tick under B with no tick skipped or doubled; the only
  skew is *which thread* ran a given tick, which is unobservable as long as
  the snapshot is complete. The snapshot completeness requirement is a
  fixture (§6).

### 2.2 Pistons spanning a border

- **Mechanic:** a piston in region A pushes/pulls a block structure that
  extends into region B (the piston fixture format already supports arbitrary
  rigs; the reorder-at-collision family is the hard case).
- **Message:** `BlockStructureMove{cells[], direction, isPull}` applied to B.
- **Vanilla timing:** vanilla resolves the *entire* push as one atomic
  BlockEvent within a single tick — moved blocks, the 12-block limit, honey/
  slime drag, glazed-terracotta non-stick, and the reorder-at-collision
  ordering are all computed against a consistent snapshot. A message that
  applies B's half one tick late would let a player observe a piston head
  extended into A while the pushed block in B has not yet moved — **directly
  observable and non-vanilla.**
- **Decided:** a piston push is an *atomic multi-region transaction*, not a
  fire-and-forget message. **A push that spans a border forces a merge**
  (§1.3) so the whole rig resolves single-threaded in one region, exactly as
  today. This is the correctness-dominates-parallelism rule applied to the
  one mechanic where deferral is unambiguously observable. The static-grid
  M2 handles this by refusing to split any region with a piston within
  push-reach (12 + 1) of its border; the dynamic M4 merges on the same
  predicate. **The difference sheet records: pistons never observe a border —
  zero timing skew, at the cost of temporarily serializing the two regions.**
  The piston differential fixture gets a cross-border variant that asserts
  the merged result is cell-identical to the single-region result.

### 2.3 Hoppers across an edge

- **Mechanic:** a hopper in A pointing into / pulling from a container in B.
- **Message:** `ItemTransfer{fromPos, toPos, slot, stackSnapshot}` applied to
  B (or A) at the boundary.
- **Vanilla timing:** vanilla hoppers move one item per 8-tick cooldown, and
  the *order* in which multiple hoppers touch a shared container within a tick
  is deterministic (insertion before extraction, position order). A one-tick
  deferral across a border shifts a single item's arrival by one 8-tick
  cycle's fraction. **Difference sheet:** a cross-border hopper transfer lands
  one tick later than an all-in-one-region transfer. **Decided:** this is
  *within* the observability floor for hoppers **only if** the 8-tick
  cooldown is preserved and no double-move can occur (the item must leave the
  source atomically when the message is enqueued, not when it is applied — the
  source debits at enqueue, the target credits at apply, so the item is never
  in two places and never in zero places across the boundary). Enqueue-debits/
  apply-credits is the decided invariant for **all** item-flow messages.
  Because a one-tick skew *is* measurable by a comparator-clock contraption,
  the honest position is: **cross-border hopper timing is documented as +1
  tick in the difference sheet, and the dynamic merge policy keeps
  tightly-coupled hopper arrays (item sorters, the classic border case) inside
  one region** so real builds don't hit the skew. Static M2 keeps them
  together by grid alignment; a build straddling the static grid is the
  documented exception until M4.

### 2.4 Explosions

- **Mechanic:** a TNT/creeper/bed/anchor explosion centered in A whose blast
  radius and knockback reach into B (crater blocks, entity damage+knockback,
  drops).
- **Message:** split into `BlockRemove{cells[]}` + `EntityImpulse{uuid,
  damage, knockback}` messages to each region the blast touches.
- **Vanilla timing:** vanilla computes the whole explosion atomically — ray
  traces from the center, resistance per block, exposure per entity — then
  applies. Blocks and entities in B would, under naive messaging, update one
  tick after those in A. **Difference sheet:** the crater and knockback in B
  land one tick after A's. This is **borderline observable** (a player
  standing at the border sees the near half of the crater form, then the far
  half next tick) and, worse, the *ray-trace exposure calculation* for a B
  entity depends on A blocks that may already be gone. **Decided:** explosions
  compute their **entire effect against a consistent snapshot in the
  originating region** (the ray traces read block state, which the message
  carries as a snapshot), then dispatch pure result messages (which blocks
  become air, which entities take how much impulse). The *computation* is
  atomic and single-region; only the *application* of already-decided results
  crosses. Result-application skew of one tick for the far half is recorded in
  the difference sheet; because the computation used a consistent snapshot,
  the *outcome* (which blocks, how much damage) is vanilla-identical — only
  the visual application frame differs by one tick at the border. A blast
  large enough to reach a border also trips the merge predicate in M4, so in
  practice large explosions serialize the affected regions. Static M2:
  documented +1 tick far-half application.

### 2.5 Redstone wire / signals crossing a border

- **Mechanic:** a redstone wire, repeater, comparator, or observer signal
  propagating across a region border; and quasi-connectivity/BUD effects
  reading a block on the other side.
- **Message:** `RedstoneUpdate{pos, newSignal}` scheduled into the target.
- **Vanilla timing:** redstone is the *most* timing-sensitive system in the
  game — a repeater's 1–4 tick delay, torch burnout, the sub-tick update order
  of the new Orientation system (§7) — and a redstone contraption is exactly
  the thing speedrunners and technical players build to detect a one-tick
  divergence. Deferring a wire update across a border by a tick would change
  clock speeds and break tick-perfect machines. **Decided:** **redstone never
  crosses a region border as a deferred message.** A redstone update queued
  against a cell within propagation reach of a border forces a merge (M4) /
  forbids a split (M2), identical to the piston rule. Redstone dust
  propagates instantly within a tick in vanilla (it is not a scheduled tick),
  so it *cannot* be made a next-tick message without a visible divergence;
  the only correct treatment is to keep any connected redstone network inside
  one region. **The redstone network is thus a merge unit:** the connected
  component of a signal graph is never split across regions. Difference sheet:
  **zero redstone timing skew — connected networks are always single-region.**
  This is the single most important correctness decision in the document and
  the reason update-order work (§7) lives inside P2.

### 2.6 The rest of the border-crossing inventory

Recorded so nothing is discovered late. Each maps to the model above:

- **Fluids (water/lava spread across a border):** scheduled ticks; spread is
  already a scheduled update in vanilla, so a `FluidUpdate` message at the
  next boundary matches vanilla's own scheduled-tick cadence. **Difference
  sheet: zero skew** (vanilla already defers fluid spread to a scheduled
  tick; the message *is* that scheduled tick, just owned by B). Requires the
  §2.2 fluid-slope fixture to gain a cross-border variant.
- **Fire spread across a border:** same as fluids — scheduled random tick,
  becomes a message on B's schedule, zero skew.
- **Block updates / neighbour notifications (e.g. sand falling, a broken
  support toppling a tower across a border):** `NeighbourUpdate{pos}` message.
  Vanilla propagates neighbour updates within a tick; a cross-border neighbour
  update is +1 tick. **Decided:** neighbour-update chains that reach a border
  are rare and mostly cosmetic (a gravity block one tick late to fall); pinned
  in the difference sheet as +1 tick, not a merge trigger, *except* when the
  neighbour update is part of a redstone or piston resolution (those already
  force a merge per §2.2/§2.5).
- **Entity AI targeting across a border:** a mob in A targeting a player in B.
  The mob reads a **stale (last-tick) snapshot** of B's entities exposed
  read-only at the boundary (see §3). +1 tick of targeting latency, sub-
  observable for AI (vanilla AI already runs on multi-tick decision cadences).
- **Portals / dimension changes:** already cross *instances* today via a
  scheduled teleport; becomes an `EntityHandoff` to a region in another
  instance's region set. Instances are independent region sets (§4.1).
- **Sound/particle/vibration events (sculk sensor hearing across a border):**
  event broadcast, not state mutation; delivered as a message, +1 tick, sub-
  observable (matches sculk's own delay-line behavior).
- **Beacon/conduit AoE, spawner activation radius, mob AoE effects:** read the
  read-only neighbour snapshot (§3); +1 tick, sub-observable.

### 2.7 Message ordering and determinism

- Each region's inbox is drained at the tick boundary in a **deterministic
  total order**: `(messageClassOrdinal, sourceRegionId, sequenceWithinSource)`.
  This removes any dependence on thread-scheduling order, which is the
  property jcstress-style tests (§5.3) verify and chaos mode (§5.4) fuzzes.
- A message never spawns a message applied in the *same* boundary; a message
  produced while applying the inbox is enqueued for the *next* boundary. This
  bounds a single boundary's work and prevents cross-region cascades from
  collapsing back into a hidden global lock.

---

## 3. Sub-tick semantics and the read-only neighbour snapshot

Two regions tick in parallel. Region A's tick may *read* region B's state for
the mechanics in §2.6 that are allowed a one-tick staleness (AI targeting,
AoE, sound). To make those reads safe without a lock:

**Decided:** at the start of each parallel tick phase, each region publishes
an immutable **last-tick snapshot** of the small surface other regions are
allowed to read (entity positions/health near its borders, border block
states). During the parallel phase a region reads *only* its own mutable
state and *only* the immutable snapshots of others. No region reads another
region's live mutable state — ever. This is the discipline CONVENTIONS §6
already gestures at ("share via concurrent maps, mutate on tick/scheduler
threads"), tightened: cross-region reads go through an immutable published
snapshot, never a live map.

### 3.1 The Orientation system must be region-aware from birth

Vanilla 26.x ships an experimental **Orientation** system that makes redstone
update order deterministic and direction-aware (the sub-tick ordering that
makes tick-perfect machines behave). MASTERPLAN §P2 and HANDOFF redstone item
4 are explicit: this port must be sequenced *inside* P2, because an
Orientation implementation that assumes a single global update queue would
have to be rebuilt when regions land.

**Decided position:** the Orientation port targets a **per-region update
queue** from the first line of code. Because §2.5 guarantees a connected
redstone network is never split across regions, a region's Orientation queue
is *complete* for every network it owns — there is no such thing as a partial
network needing cross-region ordering, which would have been unsolvable while
staying vanilla. The Orientation queue is thus region-local, deterministic,
and identical to vanilla's for any network, at any region size, including
region=world (where it is trivially the single global queue vanilla has).
This is why merging on the redstone-network component (§2.5) is load-bearing
for the update-order work, not just for wire timing.

---

## 4. What stays global

Region threads own gameplay state. A small, explicitly enumerated set of
concerns stays global and is *never* owned by a region thread:

- **Player list / connection I/O.** Connections, the login pipeline
  (Connections.java admission control), packet read/write, and the online-
  player roster are global. A player's gameplay entity is region-owned; its
  network connection is serviced by the global I/O layer, which posts input as
  messages into the owning region and drains outgoing packets the region
  produces. **Critically (MASTERPLAN §5.0): region threads must never share
  their execution substrate with the network layer** — the P0 read-stall bug
  proved that coupling generation to connection carriers starves reads
  globally. Region worker threads are a dedicated bounded pool, disjoint from
  Netty's I/O threads and from the worldgen executor.
- **Persistence coordinator.** Per PERSISTENCE.md: the coordinator gathers
  finished shards; each region serializes *its own* shard from *its own* data
  with zero cross-thread reads. The coordinator only orchestrates *when*
  (5-minute cadence, chunk unload, shutdown) and collects the resulting
  files. It owns no gameplay state.
- **World time and weather.** A single global clock and weather state,
  read-only to region ticks. Vanilla has one clock; time/weather are pure
  broadcasts, not per-region state. Decided: the clock advances once per
  global tick barrier, before regions tick, and regions read it immutably —
  identical to reading a snapshot. Weather transitions (WeatherCycle) run on
  the global barrier and publish an immutable current-weather value.
- **The tick barrier / scheduler itself** (§5), the difficulty tracker
  (global scalar), and registry/vanilla-data (immutable after load).

### 4.1 Instances are independent region sets

Overworld, nether, and end are separate instances today. **Decided:** each
instance has its own region set and its own region grid; regions never span
instances. Cross-instance travel (portals, end-gateways) is an
`EntityHandoff` to a region in the target instance's set, routed through the
global coordinator — the same message model, one level up. This falls out for
free from ownership-by-position because position is already instance-scoped.

---

## 5. Verification story

Verification is the product (CLAUDE.md rule 4). The region layer is only
allowed to exist because it can be proven transparent.

### 5.1 The whole suite passes at every region size

The existing suite (SelfTest + PlayTest, the north-star parity checks) must
pass **with regions enabled at every size**. The suite is run in a matrix:
region=world (degenerate), region=one-persistence-shard, region=1-chunk
(maximally adversarial — every chunk border is a region border), and a
handful of mid sizes. A behavior that passes at region=world but fails at
region=1-chunk is a message-layer bug, localized to the seam by the size that
first breaks it. **This is the core regression contract: same suite, more
region sizes.**

### 5.2 region=world degeneracy is the A/B switch

region=world **is** today's behavior — one region, one thread, no messages
ever sent, the merge policy never firing. It is the migration path (M1 ships
exactly this) and the permanent baseline: every scaling number in M5 is
reported against the region=world run of the same suite on the same box, so
"faster" is always measured against "provably identical." M1's gate is
precisely this: the full suite green with the scheduler active and
region=world, proving the abstraction is transparent before any subdivision
exists.

### 5.3 jcstress-style message-layer tests

The message layer is the only genuinely concurrent code in the design, so it
gets concurrency tests in the spirit of jcstress: two regions on two threads
exchanging messages under contention, asserting the deterministic-order
invariant (§2.7), the enqueue-debits/apply-credits invariant (§2.3), and the
no-live-cross-region-read invariant (§3) hold under every interleaving. These
extend the existing hand-rolled harnesses (no JUnit — CONVENTIONS §10); a
`SelfTest` section drives the message queue directly with racing producer
threads and asserts outcome-determinism across many runs. (Introducing real
worker threads / an executor for the region pool needs the CONVENTIONS §6
HANDOFF note — logged.)

### 5.4 Chaos mode

A **chaos mode** randomizes region boundaries per run (seeded, so a failure
reproduces from its seed): the region grid is jittered so that any given
contraption in the 1030-check suite lands with its cells split across a
different, randomized set of region borders each run. The whole existing
suite then fuzzes the seams for free — every piston/hopper/redstone/explosion
fixture becomes a cross-border fixture at some seed. A chaos-mode failure
prints its seed and the region layout, so it replays deterministically (the
flake SLO, CONVENTIONS §10, applies unchanged: a chaos failure is a bug, not
a re-run).

### 5.5 Cross-border differential fixtures (MASTERPLAN §2.2)

The vanilla-oracle differential fixtures (scripts/vanilla_oracle.py, the
piston fixture format that "already supports arbitrary rigs") gain
**cross-border variants**: the same rig captured from real vanilla, replayed
in-process with a region boundary deliberately drawn *through* the rig, and
compared cell-by-cell against the vanilla ground truth. Because vanilla has no
regions, any divergence is exactly the region layer's fault and is exactly the
difference sheet's ±tick, which the fixture asserts is what we documented — a
fixture that fails because the skew is *larger* than documented is a bug; a
fixture that passes confirms the difference sheet is honest. The difference
sheet (the per-mechanic ±tick table distilled from §2) is generated from
these fixtures, not hand-maintained.

---

## 6. Migration path and milestones

Restating MASTERPLAN §P2's milestones with the decisions above:

- **M0 — this document, owner-reviewed.**
- **M1 — region scheduler with region=world.** The scheduler abstraction
  (region → single-threaded executor, tick barrier, message-queue interface)
  wired so the whole world is one region. Zero behavior change; the
  message-queue interface exists but nothing uses it yet. Gate: full suite
  green with the scheduler active (§5.2).
- **M2 — entities + blocks region-ticked on a static grid.** Ownership by
  position goes live; regions tick in parallel on a bounded worker pool
  (dedicated, disjoint from network + worldgen per §4). Static grid, no
  dynamic merge yet; the border rules that require a merge (pistons §2.2,
  redstone §2.5) are enforced as *no-split constraints on the static grid*
  (the grid is chosen so those rigs don't straddle it in the suite; the
  documented exceptions are the straddling builds M4 fixes).
- **M3 — cross-region messaging + fixtures.** The message layer goes live for
  the deferrable mechanics (§2.1, §2.3–2.6), jcstress tests (§5.3), and
  cross-border differential fixtures (§5.5). This is where the message-queue
  interface from M1 gets its first users.
- **M4 — dynamic regions + chaos mode.** Merge/split (§1.3), the merge
  predicates for pistons/redstone/explosions, and chaos mode (§5.4).
- **M5 — published scaling charts** (players vs MSPT vs cores), each measured
  against the region=world baseline (§5.2), cross-checked with a second
  harness (azalea bots) per MASTERPLAN §P3.

Snapshot completeness (§2.1, §3) is itself a fixture: an entity handed off
across a border must round-trip every field its persistence adapter saves
(the RegionStore snapshot is the proven-complete surface — reuse it, don't
invent a second one).

---

## 7. The difference sheet (summary)

Distilled from §2; the machine-generated authoritative version comes from the
§5.5 fixtures. "Skew" = observable timing difference vs. vanilla at a region
border.

| Mechanic | Treatment | Border skew |
|---|---|---|
| Entity crossing | handoff at boundary | 0 (thread changes, not tick) |
| Piston spanning border | force merge, atomic | 0 |
| Redstone network across border | force merge (network = merge unit) | 0 |
| Explosion | atomic snapshot compute, result messages | 0 in outcome; +1 tick far-half *application* |
| Hopper across border | enqueue-debit/apply-credit message | +1 tick (M4 merges tight arrays) |
| Fluid spread | message on target's scheduled tick | 0 (vanilla already defers) |
| Fire spread | message on target's random tick | 0 |
| Neighbour update (non-redstone) | message | +1 tick |
| AI targeting across border | read-only last-tick snapshot | +1 tick (sub-observable) |
| AoE (beacon/conduit/spawner) | read-only snapshot | +1 tick (sub-observable) |
| Sound/vibration | broadcast message | +1 tick (sub-observable) |
| Time / weather | global, read-only to regions | 0 |

The design's stance: every mechanic a technical player uses to *detect* a
one-tick divergence (piston, redstone, explosion outcome) is 0-skew by
forcing a merge; the +1-tick rows are all mechanics whose vanilla behavior
already tolerates a tick of latency, and each is policed by a fixture that
asserts the skew is exactly +1 and no more.

---

## OPEN QUESTIONS (for owner review)

1. **Hopper cross-border +1 tick vs. universal merge.** §2.3 documents a +1
   tick skew for cross-border hoppers when a build straddles the static grid
   before M4. The alternative is to treat *any* connected container/hopper
   graph as a merge unit like redstone (0 skew, always). That is stricter and
   safer but reduces parallelism wherever large item-sorter arrays exist
   (exactly the high-hopper-count builds where parallelism would help most).
   **Decision needed:** accept the documented +1 tick for straddling hopper
   builds until M4, or make hopper graphs a merge unit from M2 (0 skew,
   less parallel)? Current lean: accept +1 tick, because unlike redstone a
   hopper's 8-tick cadence makes the skew far less detectable, and M4's merge
   policy removes it for real builds.

2. **Merge radius (8 chunks) vs. the project's tick-view distance.** Folia's
   8-chunk figure assumes Folia's view/tick distances. Minecom's should be
   derived from *our* configured tick distance and the maximum reach of any
   0-skew mechanic (piston 12 blocks, explosion radius, redstone signal 15).
   **Decision needed:** owner sign-off on deriving the merge radius from
   max-mechanic-reach rather than adopting Folia's constant verbatim.

3. **Worker pool sizing and its relationship to the worldgen executor.** §4
   mandates region workers be disjoint from network and worldgen threads. On
   the low-core target hosts (the laptop), three disjoint pools may oversubscribe.
   **Decision needed:** a global core budget that partitions cores across
   {network, worldgen, regions} vs. independent fixed pools. This interacts
   with P1's chunk-pipeline rebuild (MASTERPLAN §5.0) and should be decided
   jointly with it.

4. **Chaos-mode default in CI.** Chaos mode (§5.4) multiplies the suite's
   value but also its runtime and its flake surface. **Decision needed:** run
   chaos mode as a separate nightly matrix leg (cheap, isolates its own
   flakes) vs. folding a random seed into every playtest run (maximal fuzzing,
   but a chaos failure now blocks an unrelated PR). Current lean: separate
   nightly leg until the message layer has a few weeks of green.

5. **Cross-instance region routing ownership.** §4.1 routes cross-instance
   handoffs through the global coordinator. Whether the coordinator or a
   per-instance sub-coordinator owns that routing is an M3+ structural call,
   deferred until the message layer exists.
