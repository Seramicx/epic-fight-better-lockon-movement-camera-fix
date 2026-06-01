# Epic Fight x Better Lock On: Movement Fixes

![Showcase](assets/showcase.gif)

Movement, dodge, and aim fixes for Epic Fight lock-on. Better Lock On hooks in when that mod is present. Forge 1.20.1 and NeoForge 1.21.1.

## Features

- WASD during lock-on moves relative to the camera in first person, vanilla third person, and SSR, without pulling you toward the target
- Body turns toward camera direction while moving instead of snapping
- Dodge rolls use WASD relative to the camera
- Attack lunges hit the locked target (vanilla EF sometimes lunges from a stale facing and whiffs)
- Auto lock-on when you swing at someone unlocked (toggle keybind)
- Flick the mouse left/right to swap targets; Controllable right-stick flick works too
- Body faces the locked target while blocking or charging a spell
- Bosses' Rise: roll direction follows WASD vs camera; camera stays put; vanilla roll render kept
- Bows, crossbows, and tridents shoot toward the crosshair in third person, not where the body is facing
- Use items (buckets, eggs, fishing rod, Iron's instant casts) aim at the crosshair in third person
- Valkyrien Skies 2 (Forge 1.20.1 only): lock-on facing still tracks the target on a moving ship

## Optional integrations

Better Lockon, Bosses' Rise, FTB Teams, Controllable, Valkyrien Skies 2 (Forge 1.20.1 only).

## Companion mods

Camera, mounts, ships, and F5 are separate downloads. Full set on Forge 1.20.1. NeoForge 1.21.1 has SSR Camera Fixes and Better Mount Steering so far.

- [SSR: Camera Fixes & Additions](https://www.curseforge.com/minecraft/mc-mods/ssr-camera-fixes)
- [Better Mount Steering](https://www.curseforge.com/minecraft/mc-mods/better-mount-steering)
- SSR x Valkyrien Skies Compat (Forge 1.20.1 only)
- Smooth F5 (Forge 1.20.1 only)

## Config

`config/lockonmovementfix-client.toml` (created on first launch):

- `turnSpeed` - how fast the body turns while moving (default `0.45`)
- `idleTurnSpeed` - turn speed when idle, blocking, or aiming (default `0.7`)
- `autoFaceTarget` - face the locked target while idle/blocking/casting (default `true`)
- `lockOnRange` - max lock distance in blocks (default `64`)
- `filterPlayersFromAutoLockOn` - skip players for auto lock-on (default `true`)
- `flickSensitivity` - mouse degrees to swap targets (default `8`)
- `filterTeamAllies` - skip teammates and FTB allies (default `true`)

## Keybinds

- Toggle Auto Lock-On - unbound by default (Controls > Lock-On Movement Fix)

## Requires

Forge 1.20.1: Minecraft 1.20.1, Forge 47+, Epic Fight 20.14.1+

NeoForge 1.21.1: Minecraft 1.21.1, NeoForge 21.1.0+ (tested on 21.1.176), Epic Fight (1.21.1 build)

## Install

Forge: jar from [releases](https://github.com/Seramicx/epic-fight-better-lockon-movement-camera-fix/releases) on `main`.

NeoForge: [neoforge-1.0.0](https://github.com/Seramicx/epic-fight-better-lockon-movement-camera-fix/releases/tag/neoforge-1.0.0) on branch `1.21.1-neoforge`.

## License

MIT
