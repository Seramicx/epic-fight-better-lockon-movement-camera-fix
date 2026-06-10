package com.lockonfix;

import net.minecraftforge.common.ForgeConfigSpec;

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
                .comment("Per-tick turn interpolation factor while moving (0.05-1.0, higher = snappier).")
                .defineInRange("turnSpeed", 0.45, 0.05, 1.0);

        IDLE_TURN_SPEED = builder
                .comment("Per-tick turn interpolation factor while idle/attacking (0.05-1.0, higher = snappier).")
                .defineInRange("idleTurnSpeed", 0.7, 0.05, 1.0);

        builder.pop();

        builder.comment("Lock-On Range Settings").push("lockOnRange");

        LOCK_ON_RANGE = builder
                .comment("Maximum lock-on range in blocks (overrides EpicFight's default).")
                .defineInRange("lockOnRange", 64, 8, 256);

        builder.pop();

        builder.comment("Behavior Settings").push("behavior");

        AUTO_FACE_TARGET = builder
                .comment("Auto-rotate the player to face the locked-on target while idle or guarding.")
                .define("autoFaceTarget", true);

        builder.pop();

        builder.comment("Auto Lock-On Settings").push("autoLockOn");

        FILTER_PLAYERS_FROM_AUTO_LOCKON = builder
                .comment("Exclude other players from auto-target switching.")
                .define("filterPlayersFromAutoLockOn", true);

        FLICK_SENSITIVITY = builder
                .comment("Degrees of camera movement required to flick to the next target (3-45).")
                .defineInRange("flickSensitivity", 8.0, 3.0, 45.0);

        builder.pop();

        builder.comment("Auto Lock-On: team / ally filter (vanilla Scoreboard teams + FTB Teams when installed)").push("teamFilter");

        FILTER_FTB_ALLIES_FROM_AUTO_LOCKON = builder
                .comment("Exclude vanilla Scoreboard team allies and FTB Teams allies (Ally rank or higher) from auto-target switching.")
                .define("filterTeamAllies", true);

        builder.pop();

        CLIENT_CONFIG = builder.build();
    }
}
