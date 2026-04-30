package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/**
 * Mod-detection layer. Every companion mod is optional.
 */
public final class IntegrationRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean betterLockOn;
    private static boolean epicFightExtra;
    private static boolean ironsSpells;
    private static boolean controllable;
    private static boolean ftbTeams;
    private static boolean valkyrienSkies;
    private static boolean bossesRise;
    private static boolean resolved = false;

    private IntegrationRegistry() {}

    public static void resolve() {
        if (resolved) return;
        resolved = true;

        ModList mods = ModList.get();
        betterLockOn    = mods.isLoaded("betterlockon");
        epicFightExtra  = mods.isLoaded("epicfight_extra");
        ironsSpells     = mods.isLoaded("irons_spellbooks");
        controllable    = mods.isLoaded("controllable");
        ftbTeams        = mods.isLoaded("ftbteams");
        valkyrienSkies  = mods.isLoaded("valkyrienskies");
        bossesRise      = mods.isLoaded("block_factorys_bosses");

        LOGGER.info(
            "Companion mods: BLO:{} epicfight_extra:{} IronsSpells:{} Controllable:{} FTBTeams:{} VS2:{} BossesRise:{}",
            betterLockOn, epicFightExtra, ironsSpells, controllable, ftbTeams, valkyrienSkies, bossesRise);
    }

    public static boolean isBetterLockOn()     { return resolved && betterLockOn; }
    public static boolean isEpicFightExtra()   { return resolved && epicFightExtra; }
    public static boolean isIronsSpells()      { return resolved && ironsSpells; }
    public static boolean isControllable()     { return resolved && controllable; }
    public static boolean isFtbTeams()         { return resolved && ftbTeams; }
    public static boolean isValkyrienSkies()   { return resolved && valkyrienSkies; }
    public static boolean isBossesRise()       { return resolved && bossesRise; }
}
