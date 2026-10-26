# Reverse-engineering notes

These notes come from the RG556 Android port (savemosca/Mother3Recomp-Android,
2026-10), which this branch replaces; see [../RG556.md](../RG556.md). Paths
such as `tools/build-android.sh`, `variants/mother3en/` and
`patches/gbarecomp/00NN-*.patch` refer to that repository. Here the engine
changes live in `patches/gbarecomp-runtime.patch` and the game config in
`game.toml`.

One file per topic. Agents (`.claude/agents/`) read these before starting and update
them after. Each note records addresses (and whether JP == EN), what the code does,
the evidence, and open questions. Unverified claims are marked as hypotheses.

| Note | Topic | Status |
|---|---|---|
| [combo.md](combo.md) | Battle rhythm combos, hit-window check, latency offset | Hit check located; units unknown |
| [flash-stack-routines.md](flash-stack-routines.md) | FLASH512 routines copied to the stack (IWRAM 0x03006C1C..0x03006D80) | Resolved there with a RAM dispatch hook; this repo has its own (`dispatch_flash_ram_variant`), also FULLY_STATIC |
| [hq-audio-patch.md](hq-audio-patch.md) | MOTHER 3 HQ Audio patch (code changes, RetroAchievements hash) vs the engine's MP2K shadow mixer | Both kept: Android setup offers Enhanced (shadow mixer, default), HQ or Original |
| [widescreen.md](widescreen.md) | Reveal more scenery instead of stretching (320x180 fills the RG556 at 6x): engine support, work needed, first step; voxel 3D assessment | On the RG556: performance fix, sky/weather layers, actor culling (also PR #33) |
| [android-thumb-return-crash.md](android-thumb-return-crash.md) | Android crash: return to Thumb code in ARM state (0x08010832); pixel glitches; FLASH512 "unable to save" | Resolved: engine patch 0006 (upstream PR mstan/gbarecomp#44), BIOS config in build-android.sh; flash ID fixed upstream |

## Workflow
re-scout (find, write note) → decomp-matcher (optional: readable source in the fork)
→ hook-writer (implement) → verifier (test, report).
