package com.lockonfix;

import com.lockonfix.compat.IntegrationRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(LockOnMovementFix.MOD_ID)
public class LockOnMovementFix {
    public static final String MOD_ID = "lockonmovementfix";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String KEY_CATEGORY = "key.categories.lockonfix";
    public static KeyMapping TOGGLE_AUTO_LOCKON;

    public LockOnMovementFix(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, FixConfig.CLIENT_CONFIG, "lockonmovementfix-client.toml");

        context.getModEventBus().addListener(this::onCommonSetup);
        context.getModEventBus().addListener(this::onRegisterKeyMappings);

        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Epic Fight x Better Lock On: Movement Fixes v2.0.0 loaded.");
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
