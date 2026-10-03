# Published upgrade fixtures

Exact schema blobs copied from Git, not generated from the merged entities.

- Fork source: `1358240396e16d3864d4715901a5c9a55c75ff2b`
- Official source: `cdd82053255c00ada1f58ae63372576b519867b8`
- Original path: `app/schemas/app.gamenative.db.PluviaDatabase/<version>.json`

| Fixture | SHA-256 |
| --- | --- |
| common-17.json | `f3886803a2c78c641854f3b66bb3d638f4d1d7284d8ca8d5497736d4aaeaf098` |
| common-18.json | `6bfb8142d550a0cd90ff2d68a5a7bac9cb43786ae2c69adde971a74f44470770` |
| common-19.json | `b9c5d9716980b6c7b9b97778fa9541b2487eb4d1fed778de63ae94e7e5949382` |
| common-20.json | `6b2884ff73e39bd7b029504609441839a2bddba7513f5643f1852385770c0c0e` |
| common-21.json | `f582566e43b24f6d4cc6419754d34123f9de0e976b79de88f5507acd1778d546` |
| common-22.json | `8cdece098c5ec96362f4a7ac224503abae0c164bcd130f19def11506b5f4b6aa` |
| common-23.json | `9e94b3485f3c7217c6d39d2d851f7858aa2dcfe43a32e0105354d6b687398ae2` |
| common-24.json | `e7c2ca1ec562e93f2a5c9e34d6c410f60db5eb8351758ac7413d3db134b31f29` |
| common-25.json | `9af2801913457f438c4dc0d076ce0e10acd664b6b9c4f03aac28bedfe3238dcb` |
| fork-26.json | `188555af94d7fc96d481477f213b8e98607a3df1843ec5b68476bbceaf5fa67e` |
| fork-27.json | `7f948c030e609bd4834c64aeb19201a5c6c059e21aa72363118e2c85fa402116` |
| official-26.json | `9a30c4a28e852e6043c719261d3a71c9794a5967128342a1e77b0d94da47bfae` |
| official-27.json | `81d3459e3c829ac4edcee4c0a967ee4d6d1702a118706536a3ff0a4014116fad` |
| official-28.json | `ed257e68c1b4d9b692817d3b9ccabb05ccd5ab121bfa69617f34ec28badf551f` |
| fork-29.json | `d8e20ef0d845595aa39b66174fe01ef2bd069f436a48ee3540ea710d1c97bf20` |

`fork-29.json` is the published merged schema from fork checkpoint `b22e53075d67210b34684ddca29935c368b13598`; it exercises the subsequent resolver-history 29→30 migration. It is not an official-history version-29 fixture.

Versions 17–25 are byte-identical in both histories and exercise the registered auto/explicit migration chains through the current target 30 (including the published merged version 29). Versions 26 and 27 collide between official and fork histories. Upgrade tests must cover both shapes rather than substitute one history's schema for the other. `MergedRoomMigrationTest` creates each exact historical schema, seeds every existing table, opens it through the application's registered Room builder, and checks row preservation plus Room's complete target-schema validation. It also checks foreign keys and deleted recipe/overwrite-manifest ID high-watermarks, including empty tables across 24→25 and the merged recipe-table rebuild. The unsupported 7–16 destructive-recovery boundary is unchanged.

Published repository schemas 26/27 remain the fork history; these fixtures separately retain the official history.
