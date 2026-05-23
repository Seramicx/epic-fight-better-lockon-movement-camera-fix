# Epic Fight x Better Lock On: Movement Fixes

![Showcase](assets/showcase.gif)

Souls-style movement, dodge, and aim fixes for Epic Fight. Better Lock On integration when installed.

**This branch:** NeoForge 1.21.1 (latest: [neoforge-1.0.0](https://github.com/Seramicx/epic-fight-better-lockon-movement-camera-fix/releases/tag/neoforge-1.0.0))
**Forge 1.20.1:** see the [`main`](https://github.com/Seramicx/epic-fight-better-lockon-movement-camera-fix/tree/main) branch.

## Features

- 360-degree movement during lock-on. WASD always sends you in the right direction relative to the camera in every perspective (first person, vanilla third person, SSR), without dragging you toward the locked target.
- Body smoothly rotates to face the camera direction while moving, instead of snapping every tick.
- Dodge rolls go in the WASD direction relative to the camera, in every perspective.
- Attack lunges connect with the locked target. (Vanilla Epic Fight sometimes starts the lunge from a stale facing angle and misses.)
- Bow, crossbow, and trident shots release toward the crosshair in third person, not the player's body angle.
- Use items (buckets, spawn eggs, fishing rod, Iron's Spells instant-cast items) fire toward the crosshair in third person, not the player's body angle.
- Auto lock-on. Swinging at an unlocked enemy locks onto them. Toggleable via keybind.
- Mouse-flick target switching. A quick mouse flick left or right cycles to the next target in that direction. Right-stick flick works with Controllable.
- Body auto-faces the locked target while blocking or holding a charged spell.
- Bosses' Rise compat: the BR roll uses WASD direction relative to the camera, the camera stays put during the roll, and the vanilla render is preserved.

## Optional integrations

Auto-detected if installed: Better Lockon, Iron's Spells 'n Spellbooks, Bosses' Rise, FTB Teams, Controllable. Each integration only turns on if the relevant mod is loaded.

## Companion mods

Camera, mount, ship, and F5 features live in separate mods. The companion mods listed below are currently Forge 1.20.1 only; NeoForge 1.21.1 ports are not yet available.

- [SSR: Camera Fixes & Additions](https://github.com/Seramicx/ssr-camera-fixes)
- [Better Mount Steering](https://github.com/Seramicx/better-mount-steering)
- [SSR x Valkyrien Skies Compat](https://github.com/Seramicx/ssr-vs-compat)
- [Smooth F5](https://github.com/Seramicx/seramicx-smooth-f5)

## Config

`config/lockonmovementfix-client.toml` (generated on first launch):

- `turnSpeed` - body lerp factor while moving (default `0.45`). Higher = body turns faster.
- `idleTurnSpeed` - body lerp factor when standing still, blocking, or aiming (default `0.7`). Slightly faster than `turnSpeed` so the body settles quickly when you stop.
- `autoFaceTarget` - whether the body auto-rotates toward the locked target while idle, blocking, or casting (default `true`).
- `lockOnRange` - max lock-on distance in blocks (default `64`).
- `filterPlayersFromAutoLockOn` - skip players when auto-lockon picks a target (default `true`).
- `flickSensitivity` - mouse degrees needed to trigger a directional target switch (default `8`).
- `filterTeamAllies` - skip vanilla scoreboard teammates and FTB Teams allies in target selection (default `true`).

## Keybinds

- Toggle Auto Lock-On - unbound by default. Set it under Controls > Lock-On Movement Fix.

## Requires

- Minecraft 1.21.1
- NeoForge 21.1.0+ (tested on 21.1.176)
- Epic Fight (1.21.1 build)

## Install

1. Install NeoForge 21.1+ for Minecraft 1.21.1.
2. Install Epic Fight (and any optional mods you want integrations for).
3. Download the jar from the [neoforge-1.0.0 release](https://github.com/Seramicx/epic-fight-better-lockon-movement-camera-fix/releases/tag/neoforge-1.0.0).
4. Drop it into your `mods/` folder.

## License

MIT
