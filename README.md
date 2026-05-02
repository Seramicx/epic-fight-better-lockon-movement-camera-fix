# Epic Fight x Better Lock On: Movement Fixes

![Showcase](assets/showcase.gif)

A bunch of small fixes that make Epic Fight + Better Lockon feel like a real souls-style combat setup. Forge 1.20.1.

## Features

- 360-degree movement during lock-on. WASD always sends you in the right direction relative to the camera in every perspective (first person, vanilla third person, SSR), instead of dragging you toward the locked target.
- Body smoothly rotates to face the camera direction while moving, instead of snapping every tick.
- Dodge rolls now always go in the intended direction in all perspectives, regardless of where the locked target is.
- Attack lunges reliably connect with the locked target. Fixes a vanilla Epic Fight issue where the lunge motion would sometimes start with a stale facing angle and miss.
- Bow, crossbow, and trident shots release toward the crosshair in third person, not toward the player's body angle.
- Use items (buckets, spawn eggs, fishing rod, and Iron's Spells instant-cast items) place / fire toward the crosshair in third person, not toward the player's body angle.
- Auto lock-on. Swinging at an unlocked enemy locks you onto them automatically. Toggleable via keybind.
- Mouse-flick target switching. While locked on, a quick flick of the mouse left or right cycles to the next target in that direction. Right-stick flick works too if you have Controllable.
- Body auto-faces the locked target while blocking or holding a charged spell, so you actually face what you're guarding against / casting at.
- Bosses' Rise compat: the BR roll uses your WASD direction relative to the camera, the camera stays put during the roll, and the vanilla render is preserved.

## Optional integrations

Auto-detected if installed: Better Lockon, Iron's Spells 'n Spellbooks, Bosses' Rise, FTB Teams, Controllable. Each integration's features only turn on if the relevant mod is loaded.

## Companion mods

Camera, mount, ship, and F5 features that used to be bundled in are now their own standalone mods. Pick whichever ones you want:

- [SSR: Camera Fixes & Additions](https://github.com/Seramicx/ssr-camera-fixes)
- [Better Mount Steering](https://github.com/Seramicx/better-mount-steering)
- [SSR x Valkyrien Skies Compat](https://github.com/Seramicx/ssr-vs-compat)
- [Smooth F5](https://github.com/Seramicx/seramicx-smooth-f5)

## Config

`config/lockonmovementfix-client.toml` (generated on first launch):

- `turnSpeed` - body lerp factor while moving (default `0.45`). Higher = body turns faster.
- `idleTurnSpeed` - body lerp factor when standing still, blocking, or aiming (default `0.7`). Slightly faster than `turnSpeed` so the body settles quickly when you stop.
- `autoFaceTarget` - whether the body auto-rotates toward the locked target while idle / blocking / casting (default `true`).
- `lockOnRange` - max lock-on distance in blocks (default `64`).
- `filterPlayersFromAutoLockOn` - skip players when auto-lockon picks a target (default `true`).
- `flickSensitivity` - mouse degrees needed to trigger a directional target switch (default `8`).
- `filterTeamAllies` - skip vanilla scoreboard teammates and FTB Teams allies in target selection (default `true`).

## Keybinds

- Toggle Auto Lock-On - unbound by default. Set it under Controls -> Lock-On Movement Fix.

## Requires

- Minecraft 1.20.1
- Forge 47+
- Epic Fight 20.14.1+

## Manual install

1. Install Forge 47+ for Minecraft 1.20.1.
2. Install Epic Fight (and any optional mods you want integrations for).
3. Download the jar from the [latest release](https://github.com/Seramicx/epic-fight-better-lockon-souls-fix/releases/latest).
4. Drop it into your `.minecraft/mods/` folder (or your mod-pack instance's mods folder).

## License

MIT
