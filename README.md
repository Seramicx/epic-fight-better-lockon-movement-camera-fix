# Epic Fight x Better Lock On: Movement Fixes

A companion mod for [Better Lockon](https://github.com/ShelMarow/Better-Lockon) and [Epic Fight](https://github.com/Antikythera-Studios/epicfight) on Forge 1.20.1.

Fixes movement direction, dodge direction, and attack/aim targeting while locked on. Souls-like feel without the jank.

## Features

- 360 movement during lock-on. W goes toward, S goes away, A/D strafe, all relative to the camera. No more being dragged into the enemy.
- Body smoothing that survives Epic Fight's per-tick yRot rewrites, so the body doesn't snap.
- Auto lock-on when you hit an unlocked enemy. Configurable range, optional player and team filter (vanilla scoreboard teams + FTB Teams).
- Mouse-flick target switching, with controller right-stick support via Controllable.
- Dodge roll direction follows your WASD relative to the camera, not just toward the locked target. So you can actually roll away.
- Attack lunge fix. The vanilla lunge motion can go off-target if `modelYRot` is stale at the moment the attack starts. Snapping it at the right point fixes that.
- Bow / crossbow / trident: charges and releases hit the crosshair.
- Use-item aim fix for buckets, spawn eggs, fishing rods, and Iron's Spells instant items.
- Auto-face during cast or guard. Body smoothly rotates toward the locked target when you're holding a guard or casting a spell.
- Bosses' Rise compat: roll direction follows WASD camera-relatively, camera stays decoupled during the roll, vanilla render is preserved.

## Requirements

- Minecraft 1.20.1 + Forge 47+
- [Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight) 20.14.1+

## Optional companions

These are auto-detected and the relevant features turn on if they're installed:

- [Better Lockon](https://www.curseforge.com/minecraft/mc-mods/better-lockon)
- [Iron's Spells 'n Spellbooks](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks)
- [Bosses' Rise](https://www.curseforge.com/minecraft/mc-mods/bosses-rise)
- [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge)
- [Controllable](https://www.curseforge.com/minecraft/mc-mods/controllable)

## Companion mods

The camera, mount, ship, and F5 features that used to live here are now their own standalone mods:

- [Shoulder Surfing Reloaded: Camera Fixes & Additions](https://github.com/Seramicx/ssr-camera-fixes)
- [Seramicx's Better Mount Steering](https://github.com/Seramicx/better-mount-steering)
- [Shoulder Surfing Reloaded x Valkyrien Skies Compat](https://github.com/Seramicx/ssr-vs-compat)
- [Seramicx's Smooth F5](https://github.com/Seramicx/seramicx-smooth-f5)

Pick the ones you want.

## Config

`config/lockonmovementfix-client.toml` (generated on first launch):

- `turnSpeed` - body lerp factor while moving (default 0.45).
- `idleTurnSpeed` - body lerp factor when standing still or aiming or blocking (default 0.7).
- `autoFaceTarget` - whether the body auto-rotates toward the target during idle/guard/cast.
- `lockOnRange` - max lock-on range in blocks (default 64).
- `filterPlayersFromAutoLockOn` - skip players when auto-picking targets.
- `flickSensitivity` - mouse degrees needed to trigger directional target switch (default 8).
- `filterTeamAllies` - skip vanilla scoreboard teammates and FTB Teams allies.

## Keybinds

- Toggle Auto Lock-On (unbound by default).

## v2.0.0

The previous all-in-one shipped a built-in shoulder cam, mount steering, VS compat, and an F5 fork. v2.0.0 strips all that out into separate mods (linked above). Lock-on movement, dodge, lunge, aim, and auto-lockon stay here.

## License

MIT
