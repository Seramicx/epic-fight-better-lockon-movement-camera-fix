package com.lockonfix;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Configuration for the Lock-On Movement Fix mod.
 * Config file: lockonmovementfix-client.toml
 */
public class FixConfig {

    public static final ForgeConfigSpec CLIENT_CONFIG;

    public static final ForgeConfigSpec.DoubleValue TURN_SPEED;
    public static final ForgeConfigSpec.DoubleValue IDLE_TURN_SPEED;

    public static final ForgeConfigSpec.BooleanValue AUTO_FACE_TARGET;

    public static final ForgeConfigSpec.IntValue LOCK_ON_RANGE;

    public static final ForgeConfigSpec.BooleanValue FILTER_PLAYERS_FROM_AUTO_LOCKON;
    public static final ForgeConfigSpec.DoubleValue FLICK_SENSITIVITY;

    public static final ForgeConfigSpec.BooleanValue FILTER_FTB_ALLIES_FROM_AUTO_LOCKON;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Movement Settings").push("movement");

        TURN_SPEED = builder
                .comment(
                    "How fast the player turns while moving (per-tick interpolation factor).",
                    "Lower = smoother/slower turns, Higher = snappier/faster turns.",
                    "0.1 = very smooth drift, 0.5 = balanced, 1.0 = instant snap (no smoothing)."
                )
                .defineInRange("turnSpeed", 0.45, 0.05, 1.0);

        IDLE_TURN_SPEED = builder
                .comment(
                    "How fast the player turns to face the target when standing still (for attacks).",
                    "Should be higher than turnSpeed so attacks aim at the enemy quickly.",
                    "0.5 = smooth, 0.8 = fast, 1.0 = instant."
                )
                .defineInRange("idleTurnSpeed", 0.7, 0.05, 1.0);

        builder.pop();

        builder.comment("Lock-On Range Settings").push("lockOnRange");

        LOCK_ON_RANGE = builder
                .comment(
                    "Maximum lock-on range in blocks.",
                    "Epic Fight's default is around 16-20 blocks, which is too short",
                    "for flying bosses like the Wither and Ender Dragon.",
                    "This overrides Epic Fight's internal lock-on range."
                )
                .defineInRange("lockOnRange", 64, 8, 256);

        builder.pop();

        builder.comment("Behavior Settings").push("behavior");

        AUTO_FACE_TARGET = builder
                .comment(
                    "Whether the player automatically faces the locked-on target when idle or blocking.",
                    "When true (default), the player smoothly rotates toward the locked-on enemy",
                    "while standing still, and auto-faces during blocking (guard) and parrying.",
                    "When false, the lock-on camera still tracks the target, but the player",
                    "holds their last movement direction and does not auto-rotate during guard/parry."
                )
                .define("autoFaceTarget", true);

        builder.pop();

        builder.comment("Auto Lock-On Settings").push("autoLockOn");

        FILTER_PLAYERS_FROM_AUTO_LOCKON = builder
                .comment(
                    "Exclude other players from automatic target switching.",
                    "When true (default), auto lock-on will never pick a player as the next target.",
                    "Useful in multiplayer to avoid accidentally locking onto allies."
                )
                .define("filterPlayersFromAutoLockOn", true);

        FLICK_SENSITIVITY = builder
                .comment(
                    "Degrees of mouse/camera movement needed to trigger a directional target switch.",
                    "Lower = more sensitive (easier to switch), Higher = harder to trigger.",
                    "Only applies when auto lock-on is active."
                )
                .defineInRange("flickSensitivity", 8.0, 3.0, 45.0);

        builder.pop();

        builder.comment("Auto Lock-On: team / ally filter (vanilla Scoreboard teams + FTB Teams when installed)").push("teamFilter");

        FILTER_FTB_ALLIES_FROM_AUTO_LOCKON = builder
                .comment(
                    "Never auto-lock onto teammates or allies. Covers vanilla Scoreboard teams (/team) and",
                    "FTB Teams (when installed). For FTB, anyone ranked Ally or higher is excluded.",
                    "This runs in addition to filterPlayersFromAutoLockOn. With that flag off, non-ally players can still be targeted."
                )
                .define("filterTeamAllies", true);

        builder.pop();

        CLIENT_CONFIG = builder.build();
    }
}
