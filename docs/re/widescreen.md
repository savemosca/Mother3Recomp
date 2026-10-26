# Widescreen: reveal more of the world instead of stretching

Status: **284x160 on the RG556** since 2026-10-25, with the performance
fix, sky and weather layers, and actors culled against the widened view
(josiahg2502-glitch/Mother3Recomp#33). Details and open items:
[../RG556.md](../RG556.md). The original assessment (2026-10-15) follows
the section below.
It also covers a later voxel 3D overworld.

## Update 2026-10-24: upstream implemented it

The upstream port moved to josiahg2502-glitch/Mother3Recomp, which ships 16:9
widescreen: `src/m3_widescreen.cpp`, 354 lines, PolyForm Noncommercial (the
same licence as this fork).
- **Field:** the PPU asks for tilemap entries outside 240 px
  (`g_ws_tilemap_provider`), and they are answered from the game's own
  decompressed map in EWRAM at the live camera.
  - English v1.3 addresses: camera 0x02001004, layer pointers 0x02005C5C,
    16 px tile definitions 0x02009C88, map id 0x020281E4.
  - The model is validated against the visible VRAM before it is trusted.
- **Battles:** extended; the BG ring is reused.
- **Sprites:** placed through `g_ws_obj_attr_x_provider`.
- **Other screens:** pillarboxed (`g_ws_pillarbox`).

The mstan engine has the same three hooks with identical signatures, so the
file is ported unchanged, with a provenance notice. Wiring in this fork:
- `src/main.cpp`:
  - `max_view_width` and `widescreen_view_width` are 284;
  - `--view-width 284` is added when config.ini [Launcher]
    `m3_widescreen = 1` (upstream's key);
  - `m3widescreen::tick()` runs from the per-frame observer, which now
    chains RetroAchievements' frame.
- Launcher Options: a "Widescreen" checkbox, applied at the next launch.

On the RG556:
- With the default height-filling layout, 284x160 at 6.75x is about
  1917x1080, which nearly fills the panel.
- With integer scaling (6x) it is 1704x960, with 108 px bars at the sides.
- 320x180 would fill the panel at 6x, but upstream validated 284 only
  (field model, battles, cutscene bars).

To check on the device: the field, rooms, battles, cutscenes, map edges,
and that RetroAchievements still runs (chained observer).

## Original assessment (2026-10-15)

## Goal

Fill a 16:9 handheld screen by showing scenery beyond the native 240x160
view, at the original pixel scale. The image is not stretched. This is
project goal 2 in `CLAUDE.md`.

On the Anbernic RG556 (1920x1080):

| View (GBA pixels) | Scale | On screen | Margins |
|---|---|---|---|
| 240x160 (today) | 6x integer | 1440x960 | 240 px left/right, 60 px top/bottom |
| 284x160 | 6.75x | 1917x1080 | none; not an integer scale |
| 320x160 | 6x integer | 1920x960 | 60 px top/bottom |
| 320x180 | 6x integer | 1920x1080 | none: 40 GBA px more per side, 10 above/below |

320x180 at 6x fills the panel exactly and stays sharp.

## What the engine already has

- An expanded PPU. Its maximum is 896x864 (`gba_ppu.h` `kMaxRenderWidth` and
  `kMaxRenderHeight`). It renders tile graphics, palettes, priority and
  blending from the guest's live state. Vertical expansion is opt-in per
  game.
- Per-game opt-in through `RunOptions`. MOTHER 3 opts out today in
  `src/main.cpp`:
  - `max_view_width = 240`
  - `max_resize_view_height = 160`
  - `launcher_expose_widescreen = false`
- View-mode rows in the runtime menu (Native, 16:9 fixed, Adaptive). They
  appear only when the game opts in.
- `GBARECOMP_VIEW_WIDTH=<n>` requests a width. `GBARECOMP_WS_WIP=1` forces
  the generic expanded renderer in a game that has not opted in. Both are
  development switches and can be set through `files/debug-env.txt` on
  Android.
- A complete reference implementation for Pokémon Emerald, inherited in this
  repo until the 2026-10-24 merge with the upstream port, which removed
  it: `docs/WIDESCREEN_EXPERIMENT.md` and `src/emerald_*_view.cpp` at commit
  `d008c2b`.

## Why widening the view is not enough

The GBA only prepares what a 240x160 screen needs:

1. **Background maps.** A text BG tilemap is usually 256x256 px (32x32
   tiles) and wraps around. The game streams new tile columns and rows as
   the camera moves, a little beyond the visible edge. Past that, the
   margin would show stale or wrapped tiles.
   *Hypothesis for MOTHER 3; to be measured.*
2. **Objects.** NPCs and other sprites outside the screen are culled by the
   game, so they are missing from OAM.
3. **Interface.** Dialogue boxes, menus and the status bar are placed for
   240x160.
4. **Other scenes.** Battles have animated full-screen backgrounds. Those,
   cutscenes and special screens should stay a centred 240x160.

## Approach (the Emerald pattern)

All of it is host presentation only. The guest runs unchanged: nothing is
written to guest RAM, VRAM, palettes, OAM, saves or ROM, and no guest code is
called.

1. **Map margin.** Read the camera and the live map data (RAM), plus tileset
   and map data (ROM). Feed the tile entries for the margins to the
   expanded PPU.
2. **Object margin.** Decode live object state into a margin-only sprite
   layer. Bind each part to the hardware OAM where it is on screen. Do not
   spawn actors or run AI.
3. **UI.** Anchor dialogue and menus to the viewport edges, or leave them
   centred.
4. **Scene gate.** Expand only in verified overworld scenes; everything else
   stays native with black margins.
5. **Verification.** Every frame, compare sampled native tiles and objects
   with what the expanded view predicts. On any mismatch drop the margins and
   log `DEGRADED`. The native 240x160 centre must stay byte-identical.

Emerald needed many iterations for this: camera lag, door animations, map
connections, dormant NPCs and window masks. For MOTHER 3, expect several
sessions of reverse engineering before the first visible result.

## Reverse engineering needed (MOTHER 3)

- Camera position variables, and how they map to BG scroll registers.
- Map format in RAM and ROM: layers, metatiles or tiles, tilesets, animated
  tiles, map transitions.
- Object system: actor table, sprite/OAM building, culling bounds.
- Scene identification: overworld vs battle, menus, cutscenes, and fades.
- Which BG layers the overworld uses, and for what (scenery, overlays, text
  window).

The Kurausukun/mother3 decomp (`third_party/mother3`, not checked out in this
clone) can supply names and structure; it is a reference only.

## Suggested first step

Run the development switch on the device, without a rebuild:

```sh
adb shell "su -c 'printf \"GBARECOMP_WS_WIP=1\nGBARECOMP_VIEW_WIDTH=320\n\" > /data/data/com.ylports.mother3recomp.en.dev/files/debug-env.txt'"
```

Then capture screenshots in a few overworld scenes, a menu and a battle.
These show how much valid scenery MOTHER 3 already keeps outside the view,
and where the wrap-around starts. Restart the session to apply the change.
Since engine patch 0010, a session ended in the background resumes where it
was. Delete `debug-env.txt` afterwards.

## Voxel 3D (later)

Reference: [ZallaxDev/pokeemerald-3Ds-dualscreen](https://github.com/ZallaxDev/pokeemerald-3Ds-dualscreen),
read 2026-10-15. It has an optional voxel overworld:

- It is a native 3DS source port of the pret/pokeemerald decompilation, not
  an emulator or a recompilation. Its renderer reads `gMapHeader` and the
  metatiles directly.
- Buildings, trees, signposts and terrain relief are generated offline from
  each map's own art (`3ds_port/scripts/gen_voxel_*.py`) and loaded from
  `voxel/*.bin`.
- Sprites stay 2D in the 3D scene. A fixed sun gives baked shade and sprite
  shadows, and the camera angle and zoom are adjustable.
- Its README says it is incomplete: "most of the map and nearly all
  interiors are still shown flat". Pokémon is the easy case, with a regular
  grid of 16x16 metatiles.

For MOTHER 3 in this port:

1. **No source.** The decomp only supplies names here; the build
   recompiles the binary. Camera, map format and objects have to be
   reverse-engineered first. That is the same work as the widescreen map and
   object margins above, so widescreen comes first.
2. **Harder art.** *Hypothesis, to be checked on real maps:* MOTHER 3 draws
   its maps in an EarthBound-style oblique perspective, with large
   hand-drawn buildings and objects. That makes generating heights from the
   art harder than for Pokémon's metatiles.
3. **Partial result.** The overworld would be partly 3D. Battles and special
   screens stay 2D.
4. **Same guarantees.** It is host presentation only: the guest runs
   unchanged and the renderer falls back to the native image when
   unverified. The RG556 GPU is stronger than the 3DS one, so performance is
   not the limit.

Realistic order:
1. Map, camera and object reverse engineering (this note).
2. Widescreen.
3. A voxel experiment on a single map (for example Tazmily), to see whether
   generating models from MOTHER 3's art works at all.

## Open questions

- Is 320x180 (vertical expansion too) worth the extra work over 320x160?
- Should the status/HP UI in the overworld move to the edges or stay centred?
