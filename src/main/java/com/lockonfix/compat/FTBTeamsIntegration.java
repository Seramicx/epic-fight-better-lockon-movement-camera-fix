package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;

public final class FTBTeamsIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Method ftbApiMethod = null;           // FTBTeamsAPI.api() -> API
    private static Method isClientManagerLoadedMethod = null;
    private static Method getClientManagerMethod = null;
    private static Method selfTeamMethod = null;
    private static Method getRankForPlayerMethod = null;
    private static Method isAllyOrBetterMethod = null;
    private static boolean resolved = false;
    private static boolean resolvedOk = false;

    private FTBTeamsIntegration() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!IntegrationRegistry.isFtbTeams()) return;

        try {
            Class<?> ftbApi = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            ftbApiMethod = ftbApi.getMethod("api");
            Class<?> apiInterface = ftbApiMethod.getReturnType();
            isClientManagerLoadedMethod = apiInterface.getMethod("isClientManagerLoaded");
            getClientManagerMethod = apiInterface.getMethod("getClientManager");

            Class<?> clientMgr = getClientManagerMethod.getReturnType();
            selfTeamMethod = clientMgr.getMethod("selfTeam");

            Class<?> team = selfTeamMethod.getReturnType();
            getRankForPlayerMethod = team.getMethod("getRankForPlayer", UUID.class);

            Class<?> rank = getRankForPlayerMethod.getReturnType();
            isAllyOrBetterMethod = rank.getMethod("isAllyOrBetter");

            resolvedOk = true;
            LOGGER.info("FTB Teams integration resolved successfully");
        } catch (Throwable t) {
            LOGGER.warn("FTB Teams integration reflection failed: {}", t.getMessage());
        }
    }

    public static boolean isAllyOrSameTeam(Player target) {
        if (target == null) return false;
        if (!IntegrationRegistry.isFtbTeams()) return false;
        resolve();
        if (!resolvedOk) return false;

        try {
            Object api = ftbApiMethod.invoke(null);
            if (api == null) return false;

            Boolean loaded = (Boolean) isClientManagerLoadedMethod.invoke(api);
            if (loaded == null || !loaded) return false;

            Object clientMgr = getClientManagerMethod.invoke(api);
            if (clientMgr == null) return false;

            Object selfTeam = selfTeamMethod.invoke(clientMgr);
            if (selfTeam == null) return false;

            Object rank = getRankForPlayerMethod.invoke(selfTeam, target.getUUID());
            if (rank == null) return false;

            Boolean isAlly = (Boolean) isAllyOrBetterMethod.invoke(rank);
            return isAlly != null && isAlly;
        } catch (Throwable t) {
            return false;
        }
    }
}
