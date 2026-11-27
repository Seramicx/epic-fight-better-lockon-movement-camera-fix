package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public final class ShoulderSurfingIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Object instance = null;
    private static Method isShoulderSurfing = null;
    private static boolean resolved = false;

    private ShoulderSurfingIntegration() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!IntegrationRegistry.isShoulderSurfing()) return;

        for (String name : new String[] {
                "com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing",
                "com.github.exopandora.shouldersurfing.api.client.ShoulderSurfing"}) {
            try {
                Method getInstance = Class.forName(name).getMethod("getInstance");
                instance = getInstance.invoke(null);
                isShoulderSurfing = getInstance.getReturnType().getMethod("isShoulderSurfing");
                return;
            } catch (Throwable ignored) {
            }
        }
        LOGGER.warn("Shoulder Surfing found but its API could not be resolved");
        instance = null;
        isShoulderSurfing = null;
    }

    public static boolean isShoulderSurfing() {
        resolve();
        if (instance == null || isShoulderSurfing == null) return false;
        try {
            return (boolean) isShoulderSurfing.invoke(instance);
        } catch (Throwable t) {
            return false;
        }
    }
}
