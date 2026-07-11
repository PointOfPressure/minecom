# Minecom Code Conventions

Standing rules for every agent (Sonnet / Opus / Fable) writing code in this
repo. Derived 2026-07-11 from a full survey of the 113-file tree: these
codify the DOMINANT existing patterns. Follow them for all new and modified
code. The deviations listed in §11 are targets for the planned unification
pass — do not imitate them, and do not mass-fix them outside a dedicated
unification task.

## 1. Canonical class shape

The default unit is a stateless static-holder class, one per feature domain:

```java
/**
 * Anvil fall damage, renaming costs and repair mechanics.
 * Behavior verified against decompiled AnvilBlock / AnvilMenu (26.1.2).
 */
public final class Anvils {
    private Anvils() {}

    private static final Map<Point, Integer> DAMAGE = new ConcurrentHashMap<>();

    public static void register(GlobalEventHandler events) { ... }
}
```

- `public final class`, private constructor, static methods and state.
- Do NOT introduce `Manager` / `Handler` / `Impl` / `Service` / `Util` /
  `Helper` / `Registry` suffixes. Name classes as plural domain nouns
  (`Anvils`, `Hoppers`, `Villagers`).
- `V*` prefix is reserved for faithful ports of decompiled vanilla code
  (`VDensity`, `VBrain`, `VJigsaw`). `*Gen` suffix for generators.
- Instantiated OOP (objects, inheritance) is allowed ONLY inside the
  `mobs/ai/` + `mobs/path/` vanilla-port island where it mirrors vanilla's
  own structure. Everywhere else, static holders.

## 2. Packages

Two levels max under `dev.pointofpressure.minecom`: feature-domain packages,
flat within each (`blocks/`, `data/`, `mobs/` (+`ai/`, `path/`),
`redstone/`, `survival/`, `worldgen/` (+`vanilla/`), `playtest/`, root for
lifecycle only). New code goes in the existing package that owns the domain;
create a new package only for a genuinely new domain, never `impl`/`api`
splits.

## 3. Subsystem lifecycle

The canonical entry point is:

- `public static void register(GlobalEventHandler events)` — event-driven
  subsystems (the ~46-file majority), or
- `public static void register(Instance instance)` — instance-scoped ones.

`start(...)`, `index()`, `load()` are legacy verbs (§11.4) — do not use for
new subsystems. Wire-up happens in `Bootstrap` using an **imported simple
name** (`Anvils.register(events);`), not a fully-qualified call.

## 4. Errors and null

- Return `null` for "not found / absent". Never `java.util.Optional` — it
  appears nowhere in this codebase and must stay that way.
- No nullability annotations (no JetBrains/JSpecify/@Nullable). State
  nullness in Javadoc when a public method can return null.
- No custom exception types. Catch blocks are rare and deliberate
  (generation/persistence edges only); never catch-and-ignore silently in
  gameplay logic — let it propagate to the tick scheduler instead.

## 5. Logging

- The only logger is SLF4J, declared exactly as:
  `private static final Logger LOGGER = LoggerFactory.getLogger(X.class);`
- Gameplay/worldgen code does not log in the steady state. Log only
  startup/persistence problems (`Main`, `Persist` precedent).
- `System.out` is allowed only in `Main`, `SelfTest`, `PlayTest` report
  output.

## 6. Concurrency

- Shared mutable state lives in `ConcurrentHashMap` /
  `ConcurrentHashMap.newKeySet()`. That is the project's threading
  discipline: share via concurrent maps, mutate on tick/scheduler threads.
- Never mix a plain `HashMap` with concurrent maps for parallel
  per-position state in the same class (existing cases are §11 targets).
- `synchronized` is acceptable in the generation/persistence layer only.
  Do not introduce `ReentrantLock`, `ExecutorService`, `VarHandle`, or
  `CopyOnWrite*` without a HANDOFF.md entry explaining why.
- Randomness on hot paths: `ThreadLocalRandom`.
- Every field touched off the tick thread gets a `// thread:` comment
  stating who reads/writes it (see `VBrain` line ~92, `VNaturalSpawner`
  line ~202 for the style).

## 7. Value types

- Records for value/DTO types, including nested `private record` for local
  structs: `public record Smelt(ItemStack result, int cookTicks, float xp) {}`.
- Classes and fields `final` wherever possible. Builders only where vanilla
  itself has one (`VDensity.Builder`).

## 8. Documentation

- **Every file carries a class-level Javadoc block** (3–8 lines): what the
  mechanic is, and which decompiled vanilla class/data file it was verified
  against. This is currently at 100% coverage (113/113) — keep it there.
- Method Javadoc on notable public API; `register(...)` does not need it.
- Inline `//` comments explain vanilla-parity decisions (why a constant has
  that value), not what the next line does.
- A deliberate simplification is stated in the source and tracked in
  AUDIT.md — never silently faked.

## 9. Formatting

- 4-space indent, no tabs; continuation lines 8 spaces.
- Explicit imports, no wildcards. Order: third-party (kyori/minestom/gson +
  project), blank line, `java.*` last. Don't fully-qualify types inline —
  import them.
- `var` is idiomatic for locals with obvious types (`new` calls, loop and
  lambda locals).
- No hard line cap, but stay reasonable (~120); don't write 180-char lines.
- Constants and ALL `static final` state: `UPPER_SNAKE_CASE`. Enum
  constants likewise.

## 10. Testing

- No JUnit; no `src/test` tree. Tests extend the two hand-rolled harnesses:
  - `data/SelfTest.java` — server-less data-engine checks (`--selftest`).
  - `playtest/PlayTest.java` — headless full-server integration with a fake
    player (`--playtest`; port overridable via `MINECOM_TEST_PORT`).
- Assertion idiom: `check(String description, boolean condition)` against
  the shared `passed`/`failed`/`REPORT` counters. New checks follow it.
- Every behavior change ships with checks in the appropriate harness.
- Test logs go to `test-logs/` in the repo (durable), never `/tmp`.

## 11. Known seams — unification-pass targets (do NOT imitate)

Recorded so nobody mistakes them for precedent. Fix only in the dedicated
unification pass (see docs/STRATEGY.md §5/§6).

1. **Two mob-AI systems coexist.** `mobs/Mobs.java` wires Minestom built-in
   AI (`EntityAIGroupBuilder` goals); `mobs/ai/` is the from-scratch
   decompiled-vanilla brain/goal/pathfinder system. Per the 100%-parity
   goal, **new mob work uses `mobs/ai/`**; the Minestom-AI wiring is legacy
   pending migration.
2. **camelCase static-final collections** in `redstone/Redstone.java` and
   `blocks/Fluids.java` (`dirty`, `scheduled`, `pendingWater`, …) — rename
   to `UPPER_SNAKE` at unification.
3. **Mixed plain/concurrent maps** for parallel state in one class (e.g.
   `redstone/Hoppers.java`'s `COOLDOWN`).
4. **Lifecycle verb drift**: `start(Instance)` in ~11 files, `index()` in
   `Recipes`, one `load()` — converge on `register(...)`.
5. **`Bootstrap.java` mixes fully-qualified and imported calls** (42 FQN vs
   10 imported) — converge on imports.
6. **God-class candidates** for splitting: `PlayTest.java` (5318 lines),
   `VStructureManager.java` (3395 — also the lone "Manager" name),
   `VanillaMobs.java` (1712), `VMonumentGen.java` (1532),
   `SelfTest.java` (1284).
7. **`VBrain`'s public mutable fields** — acceptable inside the vanilla-port
   island (mirrors vanilla), but the island boundary must stay sharp.
