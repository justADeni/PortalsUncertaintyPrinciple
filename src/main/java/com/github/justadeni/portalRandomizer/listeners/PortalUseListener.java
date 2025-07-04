package com.github.justadeni.portalRandomizer.listeners;

import com.github.justadeni.portalRandomizer.PortalsUncertaintyPrinciple;
import com.github.justadeni.portalRandomizer.crypto.Feistel24Bit;
import com.github.justadeni.portalRandomizer.events.NetherPortalPairCreateEvent;
import com.github.justadeni.portalRandomizer.events.NetherPortalUseEvent;
import com.github.justadeni.portalRandomizer.generation.PortalFrameBuilder;
import com.github.justadeni.portalRandomizer.location.EmptyCubeFinder;
import com.github.justadeni.portalRandomizer.location.PortalFinder;
import com.github.justadeni.portalRandomizer.location.Result;
import com.github.justadeni.portalRandomizer.util.LocationUtil;
import org.bukkit.*;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.function.Function;

public class PortalUseListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPortalUse(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL)
            return;

        Player player = event.getPlayer();

        event.setCancelled(true);
        Thread.ofVirtual().start(() -> {
            Location updatedLocation = LocationUtil.copy(event.getFrom());

            // Find one corner of portal
            // so that location of entering individual
            // portal blocks within one portal
            // doesn't change destination
            while (LocationUtil.alter(updatedLocation, -1, 0, 0).getBlock().getType() == Material.NETHER_PORTAL)
                updatedLocation.add(-1,0,0);
            while (LocationUtil.alter(updatedLocation, 0, -1, 0).getBlock().getType() == Material.NETHER_PORTAL)
                updatedLocation.add(0,-1,0);
            while (LocationUtil.alter(updatedLocation, 0, 0, -1).getBlock().getType() == Material.NETHER_PORTAL)
                updatedLocation.add(0,0,-1);

            Destination destination = Destination.values()[event.getTo().getWorld().getEnvironment().ordinal()];
            Location searchCenter = new Location(destination.world, destination.feistel.apply(updatedLocation.blockX()), destination.world.getSeaLevel(), destination.feistel.apply(updatedLocation.blockZ()));
            Result portalSearchAttempt = portalFinder.find(searchCenter);

            Result portalCreateAttempt = new Result.Failure();
            // Found existing Nether portal nearby to destination, using it
            if (portalSearchAttempt instanceof Result.Success) {
                portalCreateAttempt = portalSearchAttempt;
            } else {
                // No existing Nether portal found, need to find a suitable place and make it
                Result spaceSearchAttempt = cubeFinder.find(searchCenter);
                if (spaceSearchAttempt instanceof Result.Success success) {
                    NetherPortalPairCreateEvent pluginPortalEvent = new NetherPortalPairCreateEvent(player, event.getFrom(), LocationUtil.copy(success.location()));
                    Bukkit.getPluginManager().callEvent(pluginPortalEvent);
                    if (!pluginPortalEvent.isCancelled()) {
                        // Wait until the portal is made, only then proceed
                        PortalFrameBuilder.create(success.location()).join();
                        portalCreateAttempt = new Result.Success(success.location());
                    }
                } else {
                    portalCreateAttempt = new Result.Failure();
                }
            }

            if (portalCreateAttempt instanceof Result.Success success) {
                Orientable blockData = ((Orientable) success.location().getBlock().getBlockData());
                success.location().setYaw(blockData.getAxis() == Axis.X ? 0 : 90f);
                success.location().add(0.5,0,0.5);

                NetherPortalUseEvent pluginPortalEvent = new NetherPortalUseEvent(player, event.getFrom(), LocationUtil.copy(success.location()));
                Bukkit.getPluginManager().callEvent(pluginPortalEvent);
                if (!pluginPortalEvent.isCancelled()) {
                    player.teleportAsync(success.location());
                    return;
                }
            }

            Bukkit.getScheduler().runTask(PortalsUncertaintyPrinciple.getInstance(), () -> event.getFrom().getBlock().breakNaturally());
        });
    }

    private final static Feistel24Bit feistel24Bit = new Feistel24Bit(
            PortalsUncertaintyPrinciple.getInstance().getConfig().getIntegerList("keys")
                    .stream().mapToInt(i->i).toArray());
    private final static EmptyCubeFinder cubeFinder = new EmptyCubeFinder(5);
    private final static PortalFinder portalFinder = new PortalFinder();

    public enum Destination {
        OVERWORLD(feistel24Bit::encrypt, Bukkit.getWorld("world")),
        NETHER(feistel24Bit::decrypt, Bukkit.getWorld("world_nether"));
        // this the capital of Amsterdam?

        private final Function<Integer, Integer> feistel;
        private final World world;

        Destination(Function<Integer, Integer> ref, World world) {
            this.feistel = ref;
            this.world = world;
        }
    }

}
