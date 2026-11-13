package de.example.whold;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * WHold client features. Auto-Invincibility proc messages and priority are
 * ported from quoi's GPL-3.0 implementation; see THIRD_PARTY_NOTICES.md.
 */
public final class WHoldClient implements ClientModInitializer {
    private enum Move { W, A, D, SNEAK }
    private enum Invincibility { BONZO, PHOENIX, SPIRIT }

    private static Move activeMove;
    private static long startAtNanos;
    private static long releaseAtNanos;
    private static boolean waiting;
    private static boolean holding;

    private static int pendingHotbarSlot = -1;
    private static long switchSlotAtNanos;

    // Quoi-compatible proc cooldowns (ticks, 20 ticks per second).
    private static int bonzoCooldown;
    private static int phoenixCooldown;
    private static int spiritCooldown;

    // Fixed user-requested hotbar positions: Minecraft slots 6 and 8.
    private static final int PHOENIX_ROD_SLOT = 5;
    private static final int SUPERBOOM_SLOT = 7;
    private static boolean boomTriggered;
    private static Object lastLevel;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("move")
                .then(moveArgument("w", Move.W))
                .then(moveArgument("a", Move.A))
                .then(moveArgument("d", Move.D))
                .then(moveArgument("sneak", Move.SNEAK)));

            dispatcher.register(literal("slot")
                .then(argument("hotbar_nummer", IntegerArgumentType.integer(1, 9))
                    .executes(context -> scheduleSlotSwitch(context.getSource(),
                        IntegerArgumentType.getInteger(context, "hotbar_nummer"), 0.0f))
                    .then(argument("verzoegerung", FloatArgumentType.floatArg(0.0f, 3600.0f))
                        .executes(context -> scheduleSlotSwitch(context.getSource(),
                            IntegerArgumentType.getInteger(context, "hotbar_nummer"),
                            FloatArgumentType.getFloat(context, "verzoegerung"))))));
        });

        // Keep Quoi's exact proc-message matching rather than using a timer to guess procs.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleInvincibilityMessage(message.getString()));
        ClientReceiveMessageEvents.SYSTEM.register((message, overlay) -> handleInvincibilityMessage(message.getString()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.nanoTime();
            if (pendingHotbarSlot >= 0 && now >= switchSlotAtNanos) {
                if (client.player != null) client.player.getInventory().setSelectedSlot(pendingHotbarSlot);
                pendingHotbarSlot = -1;
            }

            if (waiting && now >= startAtNanos) {
                press(client, activeMove);
                waiting = false;
                holding = true;
            }
            if (holding && now >= releaseAtNanos) {
                release(client, activeMove);
                holding = false;
                activeMove = null;
            }

            if (client.level != lastLevel) {
                lastLevel = client.level;
                bonzoCooldown = phoenixCooldown = spiritCooldown = 0;
                boomTriggered = false;
            }
            if (client.player == null || client.level == null) return;

            if (bonzoCooldown > 0) bonzoCooldown--;
            if (phoenixCooldown > 0) phoenixCooldown--;
            if (spiritCooldown > 0) spiritCooldown--;

            handleBarrierBoom(client);
        });
    }

    private static void handleInvincibilityMessage(String rawMessage) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        String message = rawMessage.replaceAll("§.", "").trim();
        Invincibility proc = null;
        if (message.matches("Your (?:. )?Bonzo's Mask saved your life!")) proc = Invincibility.BONZO;
        else if (message.equals("Your Phoenix Pet saved you from certain death!")) proc = Invincibility.PHOENIX;
        else if (message.equals("Second Wind Activated! Your Spirit Mask saved your life!")) proc = Invincibility.SPIRIT;
        if (proc == null) return;

        // Match Quoi's base cooldowns. Bonzo's custom lore cooldown is server-dependent;
        // the standard cooldown is used when no live SkyBlock cooldown API is available.
        switch (proc) {
            case BONZO -> bonzoCooldown = 3600;
            case PHOENIX -> phoenixCooldown = 1200;
            case SPIRIT -> spiritCooldown = 600;
        }
        equipNextAvailable(client);
    }

    private static void equipNextAvailable(Minecraft client) {
        // Requested priority: Bonzo mask -> Phoenix (rod on slot 6) -> Spirit mask.
        if (bonzoCooldown <= 0 && !isMaskEquipped(client, "Bonzo") && findMask(client, "Bonzo") >= 0) {
            equipMask(client, "Bonzo");
            return;
        }
        if (phoenixCooldown <= 0) {
            client.player.getInventory().setSelectedSlot(PHOENIX_ROD_SLOT);
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            return;
        }
        if (spiritCooldown <= 0 && !isMaskEquipped(client, "Spirit") && findMask(client, "Spirit") >= 0) {
            equipMask(client, "Spirit");
        }
    }

    private static boolean isMaskEquipped(Minecraft client, String name) {
        ItemStack helmet = client.player.getInventory().getArmor(3);
        return !helmet.isEmpty() && helmet.getHoverName().getString().toLowerCase().contains(name.toLowerCase());
    }

    private static int findMask(Minecraft client, String name) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase().contains(name.toLowerCase())
                && stack.getHoverName().getString().toLowerCase().contains("mask")) return i;
        }
        return -1;
    }

    private static void equipMask(Minecraft client, String name) {
        int inventoryIndex = findMask(client, name);
        if (inventoryIndex < 0 || client.player == null || client.gameMode == null) return;
        int sourceMenuSlot = inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
        int containerId = client.player.inventoryMenu.containerId;
        // Pickup the mask, place it in the helmet slot, and return the old helmet
        // to the mask's former inventory slot.
        client.gameMode.handleInventoryMouseClick(containerId, sourceMenuSlot, 0, ClickType.PICKUP, client.player);
        client.gameMode.handleInventoryMouseClick(containerId, 5, 0, ClickType.PICKUP, client.player);
        client.gameMode.handleInventoryMouseClick(containerId, sourceMenuSlot, 0, ClickType.PICKUP, client.player);
    }

    private static void handleBarrierBoom(Minecraft client) {
        if (client.screen != null || client.player == null || client.gameMode == null) {
            boomTriggered = false;
            return;
        }
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            boomTriggered = false;
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        var block = client.level.getBlockState(pos).getBlock();
        boolean target = block == Blocks.BARRIER || block == Blocks.NETHER_BRICKS;
        if (!target) {
            boomTriggered = false;
            return;
        }
        if (boomTriggered) return;

        // User-configured fixed slot 8; no inventory search.
        client.player.getInventory().setSelectedSlot(SUPERBOOM_SLOT);
        client.gameMode.startDestroyBlock(pos, blockHit.getDirection());
        client.player.swing(InteractionHand.MAIN_HAND);
        boomTriggered = true;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
    moveArgument(String name, Move move) {
        return literal(name)
            .then(argument("laufzeit", FloatArgumentType.floatArg(0.05f, 3600.0f))
                .then(argument("verzoegerung", FloatArgumentType.floatArg(0.0f, 3600.0f))
                    .executes(context -> {
                        float duration = FloatArgumentType.getFloat(context, "laufzeit");
                        float delay = FloatArgumentType.getFloat(context, "verzoegerung");
                        Minecraft client = Minecraft.getInstance();
                        if (activeMove != null) release(client, activeMove);
                        activeMove = move;
                        startAtNanos = System.nanoTime() + (long) (delay * 1_000_000_000L);
                        releaseAtNanos = startAtNanos + (long) (duration * 1_000_000_000L);
                        waiting = delay > 0.0f;
                        holding = false;
                        if (!waiting) { press(client, activeMove); holding = true; }
                        context.getSource().sendFeedback(Component.literal("Halte " + displayName(move) + " für " + duration + " Sekunden" + (delay > 0 ? " nach " + delay + " Sekunden Verzögerung." : ".")));
                        return 1;
                    })));
    }

    private static int scheduleSlotSwitch(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, int hotbarNumber, float delaySeconds) {
        switchSlotAtNanos = System.nanoTime() + (long) (delaySeconds * 1_000_000_000L);
        pendingHotbarSlot = hotbarNumber - 1;
        if (delaySeconds <= 0.0f) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) client.player.getInventory().setSelectedSlot(pendingHotbarSlot);
            pendingHotbarSlot = -1;
        }
        source.sendFeedback(Component.literal("Wechsle zu Hotbar-Slot " + hotbarNumber + (delaySeconds > 0 ? " in " + delaySeconds + " Sekunden." : ".")));
        return 1;
    }

    private static String displayName(Move move) {
        return switch (move) { case W -> "W"; case A -> "A"; case D -> "D"; case SNEAK -> "Sneak"; };
    }
    private static void press(Minecraft client, Move move) {
        if (client.player == null || move == null) return;
        switch (move) {
            case W -> { client.options.keyUp.setDown(true); client.options.keySprint.setDown(true); }
            case A -> client.options.keyLeft.setDown(true);
            case D -> client.options.keyRight.setDown(true);
            case SNEAK -> client.options.keyShift.setDown(true);
        }
    }
    private static void release(Minecraft client, Move move) {
        if (client.player == null || move == null) return;
        switch (move) {
            case W -> { client.options.keyUp.setDown(false); client.options.keySprint.setDown(false); }
            case A -> client.options.keyLeft.setDown(false);
            case D -> client.options.keyRight.setDown(false);
            case SNEAK -> client.options.keyShift.setDown(false);
        }
    }
}
