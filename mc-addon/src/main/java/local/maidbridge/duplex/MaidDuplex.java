package local.maidbridge.duplex;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;

@Mod("maidduplex")
public final class MaidDuplex {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> BINDING, TOKEN, WORLD, PLAYER, MAID;
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        ENABLED = b.comment("Enable only after configuring the exact binding and UUIDs.").define("enabled", false);
        BINDING = b.define("binding", "");
        TOKEN = b.comment("Local bridge capability token. Never share this config.").define("token", "");
        WORLD = b.comment("Bridge worldId; unique per server/save, not a dimension name.").define("worldId", "");
        PLAYER = b.define("playerUuid", "");
        MAID = b.define("maidUuid", "");
        SPEC = b.build();
    }
    public MaidDuplex() {
        // SERVER config is per save: an unrelated save does not inherit this binding.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "maidduplex-server.toml");
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> DuplexRuntime.clear());
    }
}
