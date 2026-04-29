package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Iron's Spellbooks integration. Every public method is a no-op when Iron's
 * Spells is not installed (checked via {@link IntegrationRegistry}).
 * Reflection is resolved lazily on first call and cached.
 */
public final class IronSpellsIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private IronSpellsIntegration() {}

    // =====================================================================
    // ClientMagicData reflection
    // =====================================================================

    private static Method isCastingMethod = null;
    private static Method castDurationRemainingMethod = null;
    private static Method clientGetCastTypeMethod = null;
    private static boolean cmdResolved = false;

    private static void resolveCMD() {
        if (cmdResolved) return;
        cmdResolved = true;
        if (!IntegrationRegistry.isIronsSpells()) return;

        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.player.ClientMagicData");

            try {
                Method m = cmd.getMethod("isCasting");
                if (m.getReturnType() == boolean.class) isCastingMethod = m;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method m = cmd.getMethod("getCastDurationRemaining");
                Class<?> r = m.getReturnType();
                if (r == int.class || r == long.class || r == float.class || r == double.class) {
                    castDurationRemainingMethod = m;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                clientGetCastTypeMethod = cmd.getMethod("getCastType");
            } catch (NoSuchMethodException ignored) {}

            LOGGER.info("IronSpells CMD resolved: isCasting={} durationRemaining={} castType={}",
                    isCastingMethod != null,
                    castDurationRemainingMethod != null,
                    clientGetCastTypeMethod != null);
        } catch (Throwable t) {
            LOGGER.warn("IronSpells ClientMagicData reflection failed: {}", t.getMessage());
        }
    }

    public static boolean isCasting() {
        if (!IntegrationRegistry.isIronsSpells()) return false;
        resolveCMD();
        if (isCastingMethod != null) {
            try {
                if ((boolean) isCastingMethod.invoke(null)) return true;
            } catch (Throwable ignored) {}
        }
        if (castDurationRemainingMethod != null) {
            try {
                Object v = castDurationRemainingMethod.invoke(null);
                if (v instanceof Number && ((Number) v).doubleValue() > 0) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static boolean currentCastIsInstant() {
        if (!IntegrationRegistry.isIronsSpells()) return false;
        resolveCMD();
        if (clientGetCastTypeMethod == null) return false;
        try {
            Object t = clientGetCastTypeMethod.invoke(null);
            return t != null && "INSTANT".equals(t.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =====================================================================
    // Selected-spell CastType (for INSTANT snap on right-click)
    // =====================================================================

    private static Method getSpellSelectionManagerMethod = null;
    private static Method getSelectionMethod = null;
    private static Field  selectionSpellDataField = null;
    private static Method spellDataGetSpellMethod = null;
    private static Method spellGetCastTypeMethod = null;
    private static boolean selectedResolved = false;

    private static void resolveSelectedSpell() {
        if (selectedResolved) return;
        selectedResolved = true;
        if (!IntegrationRegistry.isIronsSpells()) return;

        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.player.ClientMagicData");
            getSpellSelectionManagerMethod = cmd.getMethod("getSpellSelectionManager");
            Class<?> mgrClass = getSpellSelectionManagerMethod.getReturnType();
            getSelectionMethod = mgrClass.getMethod("getSelection");
            Class<?> optClass = getSelectionMethod.getReturnType();
            selectionSpellDataField = optClass.getField("spellData");
            Class<?> spellDataClass = selectionSpellDataField.getType();
            spellDataGetSpellMethod = spellDataClass.getMethod("getSpell");
            Class<?> spellClass = spellDataGetSpellMethod.getReturnType();
            spellGetCastTypeMethod = spellClass.getMethod("getCastType");
        } catch (Throwable t) {
            LOGGER.warn("IronSpells selected-spell reflection failed: {}", t.getMessage());
        }
    }

    public static boolean isSelectedSpellInstant() {
        if (!IntegrationRegistry.isIronsSpells()) return false;
        resolveSelectedSpell();
        if (getSpellSelectionManagerMethod == null) return false;
        try {
            Object mgr = getSpellSelectionManagerMethod.invoke(null);
            if (mgr == null) return false;
            Object sel = getSelectionMethod.invoke(mgr);
            if (sel == null) return false;
            Object spellData = selectionSpellDataField.get(sel);
            if (spellData == null) return false;
            Object spell = spellDataGetSpellMethod.invoke(spellData);
            if (spell == null) return false;
            Object castType = spellGetCastTypeMethod.invoke(spell);
            return castType != null && "INSTANT".equals(castType.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =====================================================================
    // Cast keybinds (SPELLBOOK_CAST_ACTIVE_KEYMAP + 15 QUICK_CAST_MAPPINGS)
    // =====================================================================

    private static Field activeCastKeymapField = null;
    private static Field quickCastKeymapsField = null;
    private static boolean keymapsResolved = false;

    private static void resolveKeymaps() {
        if (keymapsResolved) return;
        keymapsResolved = true;
        if (!IntegrationRegistry.isIronsSpells()) return;

        try {
            Class<?> km = Class.forName("io.redspace.ironsspellbooks.player.KeyMappings");
            activeCastKeymapField = km.getField("SPELLBOOK_CAST_ACTIVE_KEYMAP");
            quickCastKeymapsField = km.getField("QUICK_CAST_MAPPINGS");
        } catch (Throwable t) {
            LOGGER.warn("IronSpells keymap reflection failed: {}", t.getMessage());
        }
    }

    public static boolean anyCastKeymapDown() {
        if (!IntegrationRegistry.isIronsSpells()) return false;
        resolveKeymaps();
        try {
            if (activeCastKeymapField != null) {
                Object v = activeCastKeymapField.get(null);
                if (v instanceof KeyMapping km && km.isDown()) return true;
            }
            if (quickCastKeymapsField != null) {
                Object v = quickCastKeymapsField.get(null);
                if (v instanceof List<?> list) {
                    for (Object k : list) {
                        if (k instanceof KeyMapping km && km.isDown()) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // =====================================================================
    // Stack-item check
    // =====================================================================

    /** True if the item's class is from the Iron's Spells package. */
    public static boolean isIronsItem(net.minecraft.world.item.Item item) {
        if (!IntegrationRegistry.isIronsSpells() || item == null) return false;
        return item.getClass().getName().startsWith("io.redspace.ironsspellbooks");
    }

}
