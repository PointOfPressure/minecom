# Parity scorecard

*Generated 2026-07-13 21:02 by `python3 scripts/parity_scorecard.py` — do not hand-edit. Sources: `playtest_262_bump_3.log`, `selftest_262_final.log`, `regiondiff_seed20260708_r18_20260713-201246.log`.*

## Headline

- **PlayTest (headless full server, fake player): 678 passed, 0 failed** across 120 scenario groups
- **SelfTest (server-less data engine): 210 passed, 0 failed**
- **Worldgen region diff: 99.3554% bit-exact** vs a real vanilla 26.2 server — 1296 chunks / 127,401,984 blocks, seed 20260708 (`scripts/worldgen_region_diff.py`, full-state comparison incl. block properties)
- **Differential fixtures: 40 piston extend/retract cases** captured from a real vanilla server, replayed cell-by-cell every run (`scripts/piston_vanilla_capture.py`)
- Documented simplifications/gaps (docs/AUDIT.md): 78 across 9 areas

## PlayTest coverage by scenario group

| scenario group | checks | failed |
|---|---:|---:|
| redstone | 67 | 0 |
| piston | 45 | 0 |
| random ticks | 22 | 0 |
| persistence | 19 | 0 |
| village | 17 | 0 |
| vanilla-ai | 15 | 0 |
| cauldron | 13 | 0 |
| end | 13 | 0 |
| enchant | 12 | 0 |
| minecart | 12 | 0 |
| trial chambers | 12 | 0 |
| trident | 11 | 0 |
| chiseled bookshelf | 11 | 0 |
| warden | 9 | 0 |
| silverfish | 9 | 0 |
| admin commands | 9 | 0 |
| item frame | 9 | 0 |
| lectern | 9 | 0 |
| nether | 9 | 0 |
| creaking | 8 | 0 |
| slime sizes | 8 | 0 |
| difficulty | 8 | 0 |
| copper waxing | 8 | 0 |
| structure loot | 8 | 0 |
| farming full cycle + sapling/grass bonemeal | 7 | 0 |
| anvil combines durability + enchants | 7 | 0 |
| raid | 7 | 0 |
| cake | 7 | 0 |
| blast furnace and smoker | 6 | 0 |
| crossbow | 6 | 0 |
| bed sleep skips night | 6 | 0 |
| double chest | 6 | 0 |
| shulker box | 6 | 0 |
| bubble columns | 6 | 0 |
| mobs | 6 | 0 |
| jukebox | 6 | 0 |
| tripwire | 6 | 0 |
| respawn anchor | 6 | 0 |
| target block | 6 | 0 |
| channeling | 5 | 0 |
| trapped chest | 5 | 0 |
| vibrations | 5 | 0 |
| happy ghast | 5 | 0 |
| enderman | 5 | 0 |
| boat | 5 | 0 |
| scaffolding | 5 | 0 |
| decorated pot | 5 | 0 |
| ender chest | 5 | 0 |
| barrel | 5 | 0 |
| furnace smelts + lit + xp | 4 | 0 |
| combat | 4 | 0 |
| drowning | 4 | 0 |
| bell | 4 | 0 |
| brewing | 4 | 0 |
| fishing loot from real tables | 4 | 0 |
| stronghold | 4 | 0 |
| chest boat | 4 | 0 |
| shearing | 4 | 0 |
| fire spread | 4 | 0 |
| harvesting | 4 | 0 |
| note block | 4 | 0 |
| candle | 4 | 0 |
| iron golem | 4 | 0 |
| crafting table via interact | 3 | 0 |
| armor reduction + durability | 3 | 0 |
| snow | 3 | 0 |
| player bow | 3 | 0 |
| thrown potions | 3 | 0 |
| sculk shrieker | 3 | 0 |
| hopper | 3 | 0 |
| pumpkin carving | 3 | 0 |
| lodestone | 3 | 0 |
| composter | 3 | 0 |
| shulker | 3 | 0 |
| cave spider | 3 | 0 |
| piglin | 3 | 0 |
| ghast fireball | 3 | 0 |
| natural spawn | 3 | 0 |
| break+drops+durability | 2 | 0 |
| 2x2 crafting via clicks | 2 | 0 |
| mob xp | 2 | 0 |
| skeleton bow + arrows | 2 | 0 |
| bow/crossbow | 2 | 0 |
| water spread + decay | 2 | 0 |
| door placement + toggle | 2 | 0 |
| death drops + respawn | 2 | 0 |
| tnt | 2 | 0 |
| saturation fast regen | 2 | 0 |
| potions | 2 | 0 |
| shield blocks frontal attack | 2 | 0 |
| lava hurts, fire resistance saves | 2 | 0 |
| fishing | 2 | 0 |
| lightning charges a creeper; its explosion drops the victim's head | 2 | 0 |
| end portal frame comparator | 2 | 0 |
| phantom | 2 | 0 |
| pillager | 2 | 0 |
| guardian | 2 | 0 |
| elder guardian | 2 | 0 |
| wither | 2 | 0 |
| endermite | 2 | 0 |
| illusioner | 2 | 0 |
| piglin brute | 2 | 0 |
| witch | 2 | 0 |
| zoglin | 2 | 0 |
| giant | 2 | 0 |
| snow golem | 2 | 0 |
| join | 1 | 0 |
| tool gating | 1 | 0 |
| ore xp | 1 | 0 |
| item pickup | 1 | 0 |
| eating | 1 | 0 |
| fall damage | 1 | 0 |
| mob drops + xp orbs | 1 | 0 |
| wither skeleton | 1 | 0 |
| bucket place | 1 | 0 |
| breeding | 1 | 0 |
| leaf decay after logging | 1 | 0 |
| brewing stand comparator | 1 | 0 |
| piston pushes entities | 1 | 0 |
| campfire | 1 | 0 |

## Known divergence (top region-diff mismatch classes, minecom<-vanilla)

| blocks | class |
|---:|---|
| 159,138 | `minecraft:deepslate<-minecraft:sculk` |
| 71,846 | `minecraft:air<-minecraft:sculk_vein` |
| 46,594 | `minecraft:oak_leaves<-minecraft:air` |
| 43,239 | `minecraft:air<-minecraft:oak_leaves` |
| 40,074 | `minecraft:spruce_leaves<-minecraft:spruce_leaves (props)` |
| 40,003 | `minecraft:spruce_leaves<-minecraft:air` |
| 29,977 | `minecraft:coal_ore<-minecraft:stone` |
| 29,538 | `minecraft:stone<-minecraft:coal_ore` |
| 23,393 | `minecraft:air<-minecraft:spruce_leaves` |
| 19,074 | `minecraft:air<-minecraft:leaf_litter` |

## Documented simplifications by area (docs/AUDIT.md)

| area | entries |
|---|---:|
| Cross-cutting | 13 |
| blocks/ | 20 |
| mobs/ | 18 |
| survival/ + data/ | 10 |
| top-level / infra | 5 |
| worldgen (documented deferrals only — core is verified elsewhere) | 8 |
| 26.2 bump — deliberate simplifications (2026-07-13, Fable) | 2 |
| stale comments to clean up when touched | 2 |
| Top 10 by player impact | 0 |

