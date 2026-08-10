package dev.heezq.trapcraft;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrapCraft implements ModInitializer {
    public static final String MOD_ID = "trapcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        TrapPolymer.logPools();
        TrapComponents.register();
        TrapNet.register();
        TrapPhantom.register();
        TrapParanoia.register();
        LedgerItem.register();
        TrapContracts.register();
        TrapLedger.register();
        TrapMarket.register();
        TrapStalls.register();
        TrapTables.register();
        TrapRaid.register();
        TrapCrew.register();
        TrapDealers.register();
        TrapHouse.register();
        TrapFloor.register();
        TrapHeat.registerCommands();
        TrapStickup.registerCommands();
        TrapFloor.registerCommands();
        CannabisCropBlock.registerTilling();
        TrapCough.register();
        TrapContent.register();
        TrapTrades.register();
        TrapDealing.register();
        TrapGuide.register();
        TrapLoot.register();
        TrapEssentials.register();

        // Ships our textures/models inside the server-generated pack so vanilla
        // clients see real art instead of the base items we ride on.
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerResourcePackUtils.markAsRequired();

        LOGGER.info("TrapCraft loaded: everything is somebody's business");
    }
}
