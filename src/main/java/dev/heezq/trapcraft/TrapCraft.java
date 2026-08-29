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
        TrapCity.register();
        TrapPayroll.register();
        TrapHomes.register();
        TrapHospitals.register();
        // Police before crime: the force reads nothing from the crime book at
        // load, but every case that opens asks the force whether anybody is on
        // the street, and a first roll against an unloaded register would
        // report a town with no police to a town that has three stations.
        TrapPolice.register();
        TrapCrime.register();
        TrapCourt.register();
        TrapShops.register();
        // After the shops and the houses: a fire picks its address out of both
        // registers, and a first roll against an unloaded one is a town where
        // nothing can catch because nothing exists yet.
        TrapFires.register();
        TrapClubs.register();
        TrapLaw.register();
        TrapTables.register();
        TrapRaid.register();
        TrapCrew.register();
        TrapDealers.register();
        TrapHouse.register();
        TrapFloor.register();
        TrapVisitors.register();
        TrapHeat.registerCommands();
        TrapStickup.registerCommands();
        TrapFloor.registerCommands();
        TrapVisitors.registerCommands();
        CannabisCropBlock.registerTilling();
        TrapCough.register();
        TrapAddiction.register();
        TrapContent.register();
        TrapPolymer.logCarriers();
        TrapTrades.register();
        TrapDealing.register();
        TrapGuide.register();
        TrapLoot.register();
        // After TrapContent: the chest pools it injects name the case and key
        // items directly, so they have to exist by the time it registers.
        TrapCases.register();
        TrapEssentials.register();

        // Ships our textures/models inside the server-generated pack so vanilla
        // clients see real art instead of the base items we ride on.
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerResourcePackUtils.markAsRequired();

        LOGGER.info("TrapCraft loaded: everything is somebody's business");
    }
}
