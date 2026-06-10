package com.lockonfix;

import com.lockonfix.client.EpicFightClientHooks;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.handler.AutoLockOnHandler;
import com.lockonfix.handler.LockOnMovementHandler;
import com.lockonfix.handler.QuickCastAimHandler;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(LockOnMovementFix.MOD_ID)
public class LockOnMovementFix {
    public static final String MOD_ID = "lockonmovementfix";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String KEY_CATEGORY = "key.categories.lockonfix";
    public static KeyMapping TOGGLE_AUTO_LOCKON;

    public LockOnMovementFix(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, FixConfig.CLIENT_CONFIG, "lockonmovementfix-client.toml");

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.register(AutoLockOnHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(LockOnMovementHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(QuickCastAimHandler.INSTANCE);
        NeoForge.EVENT_BUS.register(EpicFightClientHooks.INSTANCE);

        LOGGER.info("Lock-on movement fix loaded.");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        IntegrationRegistry.resolve();
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        TOGGLE_AUTO_LOCKON = new KeyMapping(
            "key.lockonfix.toggle_auto_lockon",
            InputConstants.UNKNOWN.getValue(),
            KEY_CATEGORY
        );
        event.register(TOGGLE_AUTO_LOCKON);
    }
}
