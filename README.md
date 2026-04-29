# Better Lock-On Movement Fix

A companion mod for [Better Lockon](https://github.com/ShelMarow/Better-Lockon) and [Epic Fight](https://github.com/Antikythera-Studios/epicfight) on Minecraft Forge 1.20.1.

![Mod Showcase](https://raw.githubusercontent.com/Seramicx/Epic-Fight-Better-Lockon-Souls-Fix/assets/lockon_showcase_small.gif)

Better Lockon's movement has quite a few issues out of the box. In 1st person, trying to walk backwards or strafe just forces you straight into the enemy. In 3rd person, dodge rolls only go towards whoever you're locked onto, so you can never actually roll away. This mod fixes all of that and makes lock-on movement feel natural in third-person combat.

## What it does

### Movement
- **Full 360 movement and dodging** in any direction while locked on, in both 1st and 3rd person
- **Fixes the 1st person magnetism bug** where pressing back/sideways drags you into the enemy
- **Smooth turning** instead of the rigid 8-direction snapping
- **Decoupled camera/body movement**: face your camera whatever direction you want while running in a completely different direction, like a normal 3rd person MMO/soulslike game
- **Mount-rotate steering**: same decoupled camera behavior on horses and other mounts, with smooth body lerp toward the camera direction

### Combat
- **Stops camera drag** from Epic Fight constantly pulling your rotation toward the target
- **Guard and parry compatibility**: auto-faces the locked-on enemy when blocking with a shield
- **Bow/crossbow/trident aim correction**: arrows fire where the 3rd person crosshair actually points, accounting for shoulder offset parallax. No adaptive crosshair needed
- **Iron's Spells aim correction**: spells fire toward the crosshair (free aim) or the locked-on target, for instant cast, held cast, quick cast, and spell book keybinds. No adaptive crosshair needed
- **Quick cast auto-face**: when casting via quick cast or tapping right click while locked on, the spell fires at the lock-on target instead of wherever the body happens to be facing

### Camera
- **Over-the-shoulder camera**: lateral + vertical camera offset in 3rd person, with shoulder swap keybind and wall collision so the camera doesn't clip through blocks
- **Overhead crosshair preset**: a second camera preset that puts the crosshair above the player's head instead of over the shoulder, for people who prefer centered aiming
- **Forward/back camera offset (zoom)**: adjust camera distance in addition to the lateral/vertical offsets
- **Crosshair correction**: when the camera is offset, block/entity interaction aligns with where the crosshair actually points on screen
- **Adaptive player hiding**: the player model disappears when the camera is pushed too close (e.g., backed against a wall) to prevent clipping into the model
- **Look-down camera centering**: camera offset collapses to centered when looking straight down, so you can pillar up and build downward without the offset getting in the way

### Lock-On
- **Extended lock-on range** (configurable) so you can actually lock onto flying bosses
- **Auto lock-on**: automatic target switching when your current target dies, with directional mouse flick or controller right-stick flick to manually switch between targets

### Compatibility
- **[Controllable](https://github.com/MrCrayfish/Controllable)**: full analog stick 360 movement, 360 dodge roll with precise stick angle, and right-stick camera control during mount-rotate
- **[Valkyrien Skies 2](https://github.com/ValkyrienSkies/Valkyrien-Skies-2)**: camera offset works correctly when riding mounts on ships
- **[FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge)**: auto lock-on won't target teammates or allies (Ally rank or higher)
- **[Iron's Spells 'n Spellbooks](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks)**: aim correction for all cast types
- **[epicfight_extra](https://github.com/GodGun968/epicfight_extra)**: defers to its dodge direction logic on keyboard, uses precise analog on controller

## Keybinds

All of these live under the **Lock-On Movement Fix** category in Controls.

- **Toggle Auto Lock-On**: unbound by default (bind it if you want the feature).
- **Swap Shoulder**: defaults to **O**. Cycles through RIGHT -> LEFT -> OVERHEAD -> RIGHT.

## Auto Lock-On

Bind the **Toggle Auto Lock-On** key in your controls menu (unbound by default). When enabled:

1. Lock onto a target normally with your existing lock-on key
2. When that target dies, you hop to someone else that still feels fair: usually whoever's in front of you, without totally ignoring a dude glued to your back
3. **Mouse flick** (or controller right-stick flick) left or right to manually switch targets in that direction
4. If no valid targets are in range, lock-on releases; you'll need to manually lock on again to resume auto-switching

The target selection uses a forward-facing camera cone. Enemies in front of you are strongly preferred, but very close enemies slightly behind you can still be selected if their proximity outweighs the angular penalty.

## Config

The mod creates a `lockonmovementfix-client.toml` in your config folder. Here's what you can change:

| Option | Default | What it does |
|--------|---------|-------------|
| `turnSpeed` | 0.45 | How fast you turn while moving. Lower = smoother, wider arcs |
| `idleTurnSpeed` | 0.70 | How fast you turn to face the target when standing still |
| `mountTurnSpeed` | 0.25 | How fast the mount rotates. Lower than foot speed since the model is bigger |
| `autoFaceTarget` | true | Auto-face the target when idle and during guard/parry. Set to false for full manual control |
| `lockOnRange` | 64 | Max lock-on distance in blocks. Vanilla Epic Fight caps out around 16-20 |
| `filterPlayersFromAutoLockOn` | true | Exclude other players from auto target switching (good for co-op) |
| `filterTeamAllies` | true | Exclude FTB Teams allies and vanilla scoreboard teammates from auto lock-on |
| `flickSensitivity` | 8.0 | Degrees of mouse/camera movement to trigger a directional target switch (3-45) |
| `cameraOffsetX` | 0.75 | Horizontal camera offset in blocks. Negative = left shoulder, positive = right. 0 = centered |
| `cameraOffsetY` | 0.15 | Vertical camera offset in blocks |
| `cameraOffsetZ` | 0.0 | Forward/backward camera offset (zoom). Negative = further away |
| `cameraOffsetSmoothing` | 0.5 | How fast the camera transitions when swapping shoulders (0.05 to 1.0) |
| `cameraOverheadOffsetY` | 1.2 | Vertical offset for the overhead preset (3rd tap of Swap Shoulder) |
| `defaultShoulderPreset` | RIGHT | Which shoulder preset to start on. RIGHT, LEFT, or OVERHEAD |
| `cameraLookDownCenterAngle` | 5.0 | Angle from straight down where camera offset collapses to centered. 0 = disabled |
| `hidePlayerWhenClose` | true | Hide the player model when the camera is pushed very close |
| `hidePlayerDistance` | 0.8 | Distance threshold for hiding the player model |

## Requirements

- Minecraft Forge 1.20.1
- Epic Fight
- Better Lockon

## Optional Compatibility

- Controllable (controller support)
- Valkyrien Skies 2 (ship-mounted camera)
- FTB Teams (ally filtering)
- Iron's Spells 'n Spellbooks (aim correction)

## Install

Grab the latest jar from the [Releases](../../releases) page and drop it in your mods folder.

<details>
<summary>Building from source</summary>

1. Clone the repo
2. Run `gradlew build`
3. Jar ends up in `build/libs`
</details>

## License

MIT. See [LICENSE](LICENSE) for details.
