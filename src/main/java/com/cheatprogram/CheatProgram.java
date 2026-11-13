package com.cheatprogram;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.util.CapeTextures;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheatProgram implements ClientModInitializer {
	public static final String MOD_ID = "cheatprogram";
	public static final Logger LOGGER = LoggerFactory.getLogger("CaeDuex");
	private static volatile boolean initialized;
	/** Forge may call vanilla render/input methods before its client-setup event. */
	public static boolean initialized() { return initialized; }

	@Override
	public void onInitializeClient() {
		com.cheatprogram.util.RepositoryIntegrity.verifyInstalled();
		ClientSettings.INSTANCE = new ClientSettings();
		ModuleManager.init();
		Config.loadAll();
		CapeTextures.initialize();
		Runtime.getRuntime().addShutdownHook(new Thread(Config::saveAll, "CaeDuex config save"));
		initialized = true;
		LOGGER.info("CaeDuex loaded {} modules", ModuleManager.all().size());
	}
}
