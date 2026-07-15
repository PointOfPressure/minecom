# Minecom Master Plan

Written 2026-07-13 (Fable), at the project owner's request: a full-depth plan
to take minecom from "verified vanilla-parity server" to **the single biggest
feat of Minecraft performance** — covering bugs, architecture, verification,
performance, dependency strategy, distribution, and innovation options.

Grounding: the complete project inventory (HANDOFF.md "State of the project",
same date), all standing docs, and three fresh deep-mining passes over the
1.2 GB Minestom Discord exports (docs/intel/performance.md,
docs/intel/vri-history.md, docs/intel/minestom-direction.md — read those for
sources; this plan cites them as [PERF], [VRI], [DIR]).

Decisions marked **OWNER** need Sayer's call — they end this doc as a
question list. Everything else is executable by agent sessions as scheduled.

---

## 0. Executive summary

**Where we are.** 677/677 gameplay checks, 210/210 data checks, 99.38%
bit-exact worldgen, ~49.5k lines, 102 commits in 4 days. The overworld
gameplay surface is close to first-pass complete; nether generation and
enchanting are the two big parity holes; multi-core — the project's entire
durable performance thesis — is 100% unbuilt (stock single-tick-thread
Minestom).

**The insight that reframes everything** [PERF][VRI]: *nobody has ever
published a rigorous benchmark of a Minestom server carrying real vanilla
logic.* Minestom's own founder says its speed comes "mostly from the fact
that our code has less tasks to do." Every prior vanilla-reimpl died before
producing one. The "biggest feat of Minecraft performance" is therefore not
just an engineering goal, it's an *empty podium*: the first project to show
**full verified vanilla behavior at multiples of vanilla's player count, with
published, reproducible numbers** owns the story — against Paper (undocumented
divergence), against Folia (needs 16+ cores, breaks plugins, players must
spread out), against empty-Minestom claims (does nothing), and against
hyperion-style Rust records (custom everything, not Minecraft-the-game).

**The three moats, in order of defensibility:**
1. **Verified parity** — already real, nobody else has it, and it's what makes
   every performance claim credible ("same behavior, faster" needs proof of
   "same behavior").
2. **Performance-with-parity** — buildable now: benchmark harness, single-
   thread engine pass, lighting, network posture. Headroom is documented and
   large.
3. **Region multi-core with vanilla semantics** — the unclaimed hill everyone
   (Glowstone, VRI's 2022 redstone thread, andree's private attempt) died on.
   Hardest, most valuable, must come last and be built on 1+2.

**The plan in one line:** finish the parity first pass → harden verification
into a machine (it's the safety net for everything after) → benchmark
publicly → optimize single-thread → design and land region multi-core →
launch on the numbers.

---

## 1. Current defects & debt (fix-soon list, independent of phases)

Bugs/risks known right now, none blocking, all cheap:

1. **Silk-touch flake** — armed with DIAG self-diagnosis; on next firing in
   any full run, save the log; that closes it. No action until then.
2. **BubbleColumns entity scan** — `applyEntityEffects` calls
   `getNearbyEntities` per column cell per tick: O(cells × entities). Fine
   today, wrong shape for the perf pass; fold into the tick-pipeline
   consolidation (§4 P1).
3. **38 independent `tick(1)` scheduler tasks + 85 instance-wide entity scans**
   (16 tasks in VanillaMobs alone) — each subsystem sweeps `getEntities()`
   separately. Biggest single-thread win available; P1's first target.
4. **`Pistons.pushEntities` teleports** instead of applying vanilla collision
   push — visible desync for players standing on moving contraptions. Small,
   land with the next pistons touch.
5. **Persistence keys are overworld-only** — position keys collide across
   dimensions. Cheap now (prefix shard keys with dimension), painful later.
6. **Fluids spread is not vanilla-exact** (even spread vs nearest-hole
   weighting within 4) — a *visible* parity gap farms depend on; promote to
   the parity backlog (§3), it's differential-testable with the piston-fixture
   method.
7. **QC approximation** ("power at block above, re-checked on updates") —
   known divergence class; document in the difference sheet until the
   update-order work (§5) replaces it.
8. **Stale-comment sweep** — AUDIT lists them; do opportunistically.

---

## 2. Verification hardening (STRATEGY §6 step 2 — concrete proposal, OWNER sign-off)

This step gates the unification pass and *everything* in the performance
program (you cannot refactor a tick pipeline or thread the world on top of a
suite you only half-trust). Proposed scope — each item is independently
landable:

1. **[DONE 2026-07-13 — scripts/worldgen_region_diff.py, 99.2805% full-state baseline; see HANDOFF]** **Commit the region-diff harness.** The 99.38% number's tooling was never
   committed; only logs survive. Rebuild it exactly like the piston harness
   (scripts/, real server, committed NBT/Anvil reader — that reader already
   exists in scripts/piston_vanilla_capture.py), so the north-star number is
   one command to reproduce. *(S/M — the pattern is proven now.)*
2. **[FACTORY DONE 2026-07-13 — scripts/vanilla_oracle.py; per-subsystem fixtures still open]** **Generalize vanilla-as-oracle differential fixtures.** The piston fixture
   proved the method: drive the real server, capture ground truth, replay
   in-process, compare cell-by-cell. Extend to: hopper item-flow timing,
   comparator/container signals, fluids spread shapes (fixes §1.6 verifiably),
   fire spread footprints, random-tick outcome distributions (statistical
   comparison), and mob pathfinding traces (same start/goal, compare paths).
   Each is a committed script + committed fixture + a replay scenario.
   *(M per subsystem; this is the suite's growth engine from now on.)*
3. **[DONE 2026-07-13 — CONVENTIONS §10 + harness footers]** **Flake SLO.** A full suite run is *clean* — any FAIL is a bug (in code or
   in test) and gets root-caused, never re-run-until-green. Already de facto
   true; write it into CONVENTIONS §10 and add a suite-level retry-forbidden
   note to the harness output. *(S)*
4. **[DONE 2026-07-13 — tick-counted waits, all sites at once; see HANDOFF]** **Determinism pass on remaining real-time waits.** The villager pass fixed
   the worst; grep the suite for remaining bare `waitFor` on AI behavior and
   convert to driven ticks or state gates. Kills the load-sensitivity that
   makes this laptop's runs 25+ minutes of anxiety. *(M)*
5. **CI.** GitHub Actions on the private repo: build + selftest on every
   push; nightly full playtest with uploaded logs; the suites are the
   product — they should run somewhere that isn't one laptop. Full playtest
   needs ~2 GB heap + ~25 min: fits free runners. *(S to stand up; OWNER:
   free-runner minutes are fine while private repo usage is low.)*
6. **[DONE 2026-07-13 — scripts/parity_scorecard.py -> docs/SCORECARD.md]** **Parity scorecard generator.** Machine-generate, from the suites
   themselves, the per-subsystem coverage/divergence sheet (checks passed,
   documented simplifications from AUDIT, fixture counts). This is STRATEGY
   §5's "unique weapon" turned into an artifact — later it becomes the Lite
   profile's difference sheet and the README's centerpiece. *(M)*
7. **Perf regression gate (lands with §4 P0).** Bench numbers per commit in
   CI; a >X% MSPT regression fails the build. Nobody in this ecosystem does
   this [PERF §5]. *(rides P0)*

---

## 3. Parity completion (finish the first pass)

Ordered by (player impact × unlock value), sizes from AUDIT:

**Tier 1 — the two systems everything else waits on: BOTH DONE 2026-07-14**
1. ~~**Enchanting engine**~~ — DONE (v0.18.0): table + anvil + grindstone,
   data-driven, incl. the loot-table enchant functions unlock
   (enchant_randomly/enchant_with_levels — LootTables.java).
2. ~~**Classic `minecraft:spawner` block entities**~~ — DONE (v0.19.0,
   `ClassicSpawners.java`): mineshaft/fortress/stronghold wired; dungeons
   closed by v0.20.0's MonsterRoomFeature port (region-diff rose to
   99.3613%).

**Tier 2 — visible-daily survival gaps: ALL DONE 2026-07-15.** ~~taming (wolf/cat/horse + saddles,
leads, name tags)~~ DONE v0.21.0; ~~villager zombie-conversion/curing~~ DONE
v0.22.0; ~~mob equipment drops~~ DONE v0.22.0 (killedByPlayer memory window —
the base drop-chance mechanic already existed); ~~ender pearls~~ DONE v0.22.0;
~~elytra+firework flight~~, ~~attack-cooldown model~~, ~~raid scaling~~,
~~phantom spawner~~ all DONE v0.23.0 (Sonnet 5) — closes Tier 2.

**Tier 3 — the long tail** from the inventory list (bees, allay, maps,
beacons*, conduits*, armor stands, signs, banners…). *Beacons/conduits are
missing from AUDIT itself — added here; both are S/M with existing patterns.

**Tier 4 — the Nether** (XL). The one remaining approximate *dimension*.
Treat like the overworld was treated: density-function port first (the engine
exists — VDensity is dimension-agnostic and already runs the End), then
biomes, then structures (fortress/bastion via the jigsaw machinery that
already places bastions as stand-ins). Gate: region-diff harness (§2.1) must
exist first so nether parity is *measured* from day one, not asserted.

**Explicit non-goals for v1.0** (documented, not silent): advancements,
scoreboards beyond basics, spectator polish, world border, datapack loading
(bundled data is the datapack), Bukkit compat (never — [VRI §4]).

---

## 4. The performance program (the headline)

### P0 — Benchmark truth (do first, ~a week of sessions)

Nothing in this program is real until it's measured, and the measurement
itself is a deliverable nobody else has [PERF Top-10 #1].

- **[LANDED 2026-07-15 — v0.24.0, harness scaffolding + 3/5 scenarios real, 2/5 correctly blocked; see HANDOFF]**
  **Harness**: committed `scripts/bench/` — `rust-mc-bot` vendored + bumped
  to protocol 776 (`scripts/bench/rust-mc-bot/VENDOR.md`), `run_scenario.py`
  + `bench_common.py` orchestrator, scenarios as TOML configs (a) `spawn`,
  (b) `spread10k`, (c) `redstone`, (d) `mobfarm`, (e) `chunkgen`. One
  command: `python3 scripts/bench/run_scenario.py <scenario> --server
  <minecom|vanilla|paper>` → one JSON in `scripts/bench/results/`. (c)/(d)/
  (e) produced real first-run numbers on this laptop (docs/BENCHMARKS.md);
  (a)/(b) need the bot swarm, which has an open connection-drop bug
  (docs/HANDOFF.md, 2026-07-15) — running them correctly **fails loudly**
  rather than reporting numbers from an unverified population, which is
  itself the harness-level check this bullet asks for ("must fail loudly").
  JFR capture wired (`--jfr` flag) but not yet auto-parsed into results
  (deliberately coarse per this section's own instrumentation-surface
  bullet — the per-system breakdown is P1's job).
- **[PARTIAL 2026-07-15]** **Baselines**: `chunkgen` runs against vanilla
  26.2 and Paper 26.2 (build 60, downloaded via PaperMC's `fill` API) —
  same box, same seed, real numbers in docs/BENCHMARKS.md (minecom is
  currently ~7.5-8x slower at raw chunk generation than either, the exact
  gap P1 item 4 exists to close). The other four scenarios' vanilla/Paper
  baselines aren't wired yet — needs either a console-driven
  `players_online` probe (vanilla/Paper have no `/metrics`) or, for
  redstone/mobfarm, a `BenchSetup.java`-equivalent world-setup mechanism.
  Threadripper headline numbers (§11.1) not run — not reachable this
  session.
- **[LANDED 2026-07-15]** **Instrumentation surface**: `bench/Metrics.java`
  — Prometheus `/metrics` backed by Minestom's `ServerTickMonitorEvent`
  (true per-tick MSPT as `quantile="0.5"/"0.95"/"0.99"`), TPS, GC
  collections/time, heap, uptime; `POST /metrics/reset` scopes a result to
  one scenario's window. Packet bytes out has no real Netty-layer counter
  yet (P1 territory) — `/proc/<pid>/io` wchar is the honest coarse stand-in,
  labelled as such in every result. Per-system tick breakdown is
  unchanged/deferred to P1 as this section already says.
- **OWNER — hardware — ANSWERED, see §11.1** (this laptop is deliberately
  the low-end/regression datapoint, not the headline; a Threadripper run is
  the headline path once reachable).

### P1 — Single-thread engine pass (the big free win)

The codebase was written correctness-first; nothing has ever been profiled.
Known shapes to fix (each measured before/after via P0):

1. **One tick pipeline.** Collapse the 38 independent `tick(1)` tasks into a
   single ordered per-tick dispatch with per-system timers (instant
   observability + kills redundant sweeps). Mirrors vanilla's own tick
   ordering — which *also* removes a class of update-order divergence.
2. **Spatial entity index.** One shared per-chunk entity index maintained on
   move; every "entities near X" query (85 call sites) hits the index
   instead of scanning the instance. This is what makes mob AI, bubble
   columns, sculk, breeding scans all O(local) instead of O(world).
3. **Allocation audit** on the hot paths (VDensity graph evaluation, Vec/Pos
   churn in physics and AI, packet-adjacent code) with async-profiler alloc
   mode; target: flat steady-state allocation per tick. Aikar-G1 as default
   flags; publish memory-per-player and per-chunk [PERF Top-10 #8].
4. **Worldgen throughput**: VDensity graph → array-form interpreter or
   MethodHandle-compiled evaluators per noise-settings (the graph is static
   per world); chunk-gen is embarrassingly parallel and is the one benchmark
   where beating *vanilla by 5-10x* is realistic and marketable ("pregen your
   world in minutes").
5. **Network posture** [PERF Top-10 #2]: mostly upstream's job (io_uring
   landed 2025-07), ours is to not amplify it — cache serialized packets for
   broadcast paths (Minestom supports this), avoid per-player component
   re-serialization (the 10k-bot profile said chat components were the wall),
   and measure bytes/player in P0 scenarios.
6. **Lighting**: watch [DIR §4] — upstream engine is ~0.4x Starlight per
   thread with lock-contention incidents. If P0 shows lighting in the tick
   profile, a Starlight-*technique* engine (ideas, not code — license) is a
   contained, high-visibility win. Don't start it on spec.

### P2 — Region multi-core (the moat)

The intel is unambiguous [PERF §2, VRI §3.5, DIR]: Minestom's acquirable API
is a dead end (global lock, ownership holes, zero adoption, author distanced);
naive cross-thread locking killed every prior attempt ("the redstone thread
and entity tick thread would constantly lock"); the community-validated shape
is **Folia/MCHPRS-style region ownership** — single-threaded islands, no
shared mutable state, explicit cross-region messaging.

Design-first, code-second. The design doc (docs/MULTICORE.md, to be written
as its own owner-reviewed pass) must answer:
- **Ownership unit**: region = group of chunks (Folia uses dynamic merging
  ~8-chunk radius); every entity/block-entity/scheduled tick belongs to
  exactly one region; regions tick in parallel on a worker pool.
- **Cross-region effects**: piston structures spanning borders, hoppers
  across edges, entity border crossing, explosions, redstone wires crossing —
  all become *messages applied at next-tick boundaries* (Folia's teleport
  model). The vanilla-parity twist nobody else has to face: those seams must
  stay *observably vanilla* (the difference sheet documents any timing skew,
  and the differential fixtures from §2.2 get cross-border variants — the
  piston fixture format already supports arbitrary rigs).
- **What stays global**: player list, persistence coordinator (shards are
  already region-keyed — PERSISTENCE.md §"Why this survives multi-core" was
  designed for exactly this day), world time/weather.
- **Verification story**: the entire existing suite must pass with regions
  enabled at every size (region=world degenerates to today's behavior —
  that's the migration path and the A/B switch); plus jcstress-style tests
  for the messaging layer; plus a "chaos mode" that randomizes region
  boundaries per run so seams get fuzzed by the whole 677-check suite.
- **Sub-tick semantics**: the reason to sequence update-order work (HANDOFF
  redstone item 4) *inside* this design — vanilla 26.x's own experimental
  Orientation system is the port target, and it must be region-aware from
  birth or it gets built twice.

Milestones: M0 design doc reviewed → M1 region scheduler with region=world
(zero behavior change, suite green) → M2 entities+blocks region-ticked,
static grid → M3 cross-region messaging + fixtures → M4 dynamic regions +
chaos mode → M5 published scaling charts (players vs MSPT vs cores).

### P3 — The public feat

The number that makes people look: **"N players, full verified vanilla
survival, 20 TPS, on an M-core box"** where N is 10-20x what vanilla manages
on the same box, with the parity scorecard proving "full vanilla" isn't
marketing. Realistic ceiling per process is 1-3k (client density and
bandwidth wall long before the server [PERF Top-10 #10]) — the *feat* framing
is players-per-core-at-parity, not raw N. Then: a public SalC1-style stress
event (the only kind of test that ever convinced this community [PERF Top-10
#7]) — event = marketing = validation in one. Cross-check numbers with a
second harness (azalea bots) before publishing anything.

---

## 5. Architecture & maintainability

1. **Unification pass** (blocked on §2, unchanged scope from CONVENTIONS
   §11): naming/lifecycle/map-idiom convergence, Bootstrap imports, god-class
   splits. PlayTest.java is 7,348 lines — split *by section* along the
   scenario registry (the section filter already defines the seams). Do it as
   the first act of the P1 tick-pipeline work: both touch every subsystem's
   wiring once; don't touch everything twice.
2. **Module boundaries with intent** [VRI Top-10 #7]: the community's stated
   winning form factor is libraries. Don't Maven-modularize now; DO keep the
   package seams library-shaped (worldgen/, redstone/, mobs/ai/ already are),
   and plan to publish 2-3 modules at launch (candidates: worldgen — the
   crown jewel and safest; loot — Trove is archived, the slot is open;
   blocks). Each published module is a contributor funnel VRI never had.
3. **Minestom abstraction seams** [DIR Top-8 #2/#4/#5]: wrap chunk IO (Anvil
   today, Polar-style later), lighting access, and passenger/vehicle calls
   behind thin minecom-owned interfaces. Not speculative architecture — these
   three surfaces are upstream's documented unstable/slow zones, and the
   seams are what make Minestom-version bumps and the P2 threading work
   local instead of tree-wide.
4. **Native plugin/extension API — design sketch only pre-launch** [VRI §4]:
   the community is unanimous (Bukkit compat kills; native API wins). v1
   needs: event bus exposure (Minestom's is good), a stable registration
   surface for blocks/items/mechanics, and the profile system (§6). Full API
   stability promises come after real third-party interest exists.

## 6. Dependency strategy (Minestom)

Per [DIR Top-8], reversing the previous lean toward staying pinned:

- **Do the 26.2 bump soon** (it will never be smaller: ~5 call sites), BUT
  strictly sequenced: (1) write + commit the data-extraction script against
  26.1.2 where output is known-good (also unblocks nether re-parity and every
  future bump), (2) bump + migrate, (3) sulfur-cube mob + passenger-
  positioning reconciliation as immediate followups, (4) full re-verify.
  **OWNER: go/no-go** — this commits the project to the 26.2 data surface.
- **Then track tags** (~5-8/yr, each a contained sprint) rather than
  accumulate a cliff; the registry/packet rework wave (#3179 et al.) is
  coming and will be bigger than 26.2 — leave headroom.
- JDK 25 toolchain now (upstream requires it); be ready to build Minestom
  from a commit hash (Maven Central 3-release/month cap).
- **Protocol hardening is our job** (no upstream disclosure channel, ≥1 known
  silent DoS): packet size/rate/id validation at the listener layer + the
  STRATEGY §5 exploit-catalog sweep, promoted to pre-launch requirement.

## 7. Distribution & growth (STRATEGY refresh)

STRATEGY.md's licensing/monetization stands (AGPL+CLA, add-ons, hosting
partnership, build-in-public). What the new intel sharpens:

1. **Lead with the benchmark, frame with the scorecard.** The README's spine:
   scaling chart + parity scorecard + "every claim reproducible with one
   command." Rewrite in a human voice (STRATEGY §4 rules) — benchmarks answer
   "slop" better than prose ever will.
2. **Positioning** [VRI]: respectful heir to VRI ("built on their
   foundation, finishing what they scoped"), the anti-Paper wedge for
   survival ("vanilla farms actually work"), speed-of-generation for the
   worldgen crowd, and quoting predecessors' own post-mortems as the answer
   to "you'll give up too" — verification converts motivation into a ratchet.
3. **Redstone is the flagship claim** [VRI Top-10 #2]: three named
   abandonments, a production user whose only blocker it was, tech-MC
   actively shopping. When update-order parity lands (inside P2), minecom is
   the first to ever credibly claim it — that's a launch headline on its own.
4. **Watch the two competitors**: invokegs/"vinestom" (closest live analog;
   will open-source "when stable") and vibenilla (Apache 2.0, AI-provenance-
   flagged — the same flag lands on us; the suite is the counter). Being
   *first to publish numbers* matters more than being first to exist.
4b. **First adopter integration requirement (2026-07-14, from Minestom
   Discord — wildmaster84/Lavender)**: the standing worldgen lib people
   actually plug in is `rocks.minestom.worldgen` — pattern is
   `new WorldGenerators(path, seed)` → `.overworld()/.nether()/.end()`
   returning a Minestom `Generator` (see Lavender.java's
   createDimensionWorld). When minecom's worldgen ships as a module, expose
   a facade matching that exact shape so migration is an import swap; data
   ships in-jar (their datapack-extract step disappears). Until nether/end
   are verified (Tier 4), the facade should let unverified dimensions
   delegate to the caller's existing generator rather than overclaim —
   "verified overworld now, other dims fall through" was the public promise
   made in-channel. Adopters care about migration friction first, parity
   second.
5. **Community presence**: continue as a named regular in Minestom Discord
   (already happening per the exports); contribute something upstream
   (a lighting fix, a passenger fix — both are wanted [DIR §4]) before
   asking for attention; adversarial review (STRATEGY §6.5) from a named
   community figure pre-launch.
6. **Launch sequence** stays STRATEGY §6 but reordered by this plan:
   hardening (§2) → 26.2 (§6) → unification+P1 → P0 numbers public with
   devlog #1 → parity tiers 1-2 → security sweep → human review → **launch**
   → P2 multi-core as the post-launch arc (in public, with the community
   watching the charts move).

## 8. Innovation options (ranked: value ÷ effort)

1. **Perf-regression CI + public per-commit MSPT charts** — nobody does it;
   cheap once P0 exists; turns performance into a visible ratchet.
2. **Parity scorecard site** (auto-generated from the suites) — the unique
   weapon, doubles as Lite's difference sheet.
3. **Vanilla-as-oracle fixture factory** (§2.2) — turns the piston method
   into an industrialized test generator; every fixture is also a marketing
   proof-point ("we diff against the real game").
4. **Deterministic replay mode** — the suites already pin RNG; a tick-input
   log + replay harness gives TAS-grade bug repro ("attach the replay to the
   issue") and makes multi-core races reproducible. Moderate effort, huge
   debugging leverage for P2.
5. **Per-region profiles** (strict spawn, fast farms) — the Lite mechanism
   generalized; pairs naturally with region ownership; nobody has it.
6. **Chunk-gen-as-a-product**: expose the 5-10x parallel worldgen as a
   standalone pregen CLI ("pregen a 10k-block world in minutes, bit-exact") —
   the speedrun/practice community is a ready audience [VRI §7].
7. **Starlight-technique lighting engine** — contained, benchmarkable,
   upstreamable (goodwill play) — only if P0 shows it matters.
8. **ViaVersion front** for old clients (documented supported path, zero
   code); **Geyser** for Bedrock later (bigger, defer).
9. **Web dashboard w/ per-region profiling** — the paid add-on (STRATEGY §2)
   — after P2 exists to give it something to show.
10. **Anticheat lane** ("cancel movement, rarely ban" per COMMUNITY-INTEL) —
    only post-launch, only if a real server runs minecom.

## 9. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Mojang ships a big worldgen change | Med | Committed extraction+diff harnesses make re-parity a checklist (§2.1, §6); this killed Cuberite — it's priced in here |
| Minestom breakage wave (#3179 etc.) | High | Seams (§5.3), track-don't-freeze, suite as tripwire |
| Multi-core correctness (silent world corruption) | High if rushed | Design-first, region=world migration path, chaos mode, replay mode, differential fixtures — this is why P2 is last |
| Single-maintainer motivation cliff (killed every predecessor) | Real | The ratchet: suites + docs mean any session (human or agent) resumes losslessly; public numbers create pull |
| AI-provenance criticism reprise | Certain | Own it: verification methodology front and center; commits under Sayer's name w/ co-author; §4 conduct rules |
| Legal (vanilla-src lineage) | Low-Med | Behavior-port discipline + never publish vanilla-src; the community's clean-room line is documented [VRI]; revisit at dual-license time |
| Benchmark credibility attack | Med | Publish methodology + raw logs + one-command repro; two independent bot harnesses; compare against Paper tuned, not strawman |

## 10. The one sequence (merges STRATEGY §6 with this plan)

1. ~~First pass~~ → **remaining: Tier 1-2 parity (§3) + fix-soon list (§1)**
2. **Verification hardening (§2)** ← current gate, needs OWNER sign-off
3. **26.2 bump, properly sequenced (§6)** ← OWNER go/no-go
4. **Unification + P1 engine pass (one combined tree-wide era)**
5. **P0 benchmarks public** (potato first, rented box for the real charts)
6. Security sweep + protocol hardening
7. Adversarial human review
8. **Open-source launch** (AGPL+CLA+SECURITY.md+scorecard+charts README)
9. Lite profile + first published module(s)
10. **P2 multi-core arc, built in public** → P3 the feat + stress event
11. Nether bit-exactness (Tier 4) woven through 9-10 as the parity flagship
12. Version tracking cadence forever after (§6)

## 11. OWNER questions — ALL ANSWERED 2026-07-13

1. **Benchmark hardware — ANSWERED**: "I'm not hosting the server on my
   laptop for people to play, it's for other people to use." Read: minecom is
   software for *other people's* hardware, so the benchmark deliverable is
   the **reproducible harness** (anyone runs it on their own box, one
   command), with the laptop producing relative/regression numbers only. No
   dedicated bench hardware from the owner; a rented box for headline launch
   charts remains an option to re-raise at launch time (no spend approved
   yet).
   **UPDATE 2026-07-13 (later same day)**: the owner has a Threadripper
   tower available. That replaces the rented-box idea for headline numbers
   AND is the natural testbed for the P2 region multi-core arc (high core
   count is exactly what region ownership needs to show scaling). Publish
   headline charts from the Threadripper with full specs disclosed; keep
   the laptop as the low-end/regression datapoint — a two-point hardware
   spread (potato + many-core) is *stronger* than one rented mid-tier box.
   Note the bias honestly in the methodology: a Threadripper flatters a
   multi-core design, which is why the harness stays one-command
   reproducible on anyone's hardware.
2. **§2 hardening scope — APPROVED as written.** Items 1-7 ARE the definition
   of STRATEGY §6 step 2. Unification sequencing unblocked once they land.
   (CI, item 5, is approved as part of this.)
3. **26.2 — GO, sequenced.** Extraction script against 26.1.2 first, then the
   bump, then sulfur-cube + passenger reconciliation as followups.
4. **Priority fork — PARITY FIRST.** Tier 1-2 parity before the P1 perf pass,
   as the plan recommends.
5. **Launch ambition — ANSWERED: no date, quality-gated.** "Ship when
   perfect on our end, then hit the Minestom community with it and seek
   reviews." Launch trigger is a quality bar, not a calendar: the parity
   tiers + hardening + benchmark harness all land BEFORE reveal, and launch
   day leads with review requests, not marketing. This means the parity
   long-tail is pre-launch work, and the launch checklist (AGPL/CLA,
   repo-public, STRATEGY steps) should be prepared but not scheduled.
6. **CI** — approved via #2.
7. **Community conduct — ANSWERED: visible, already active.** Owner is
   presently active in the Minestom Discord. Community perception right now
   is that this is "another AI-coded slop project" — strategically useful:
   expectations are floor-level, so the reveal (49.5k lines, 887-check
   suite, bit-exact worldgen, one-command reproducible benchmarks) lands
   with maximum contrast. Consequence: launch credibility must come
   entirely from the verification numbers and reproducibility, since no
   reputation precedes it — which the plan already builds. The 1-2
   pre-launch upstream contributions remain recommended and are now viable.
