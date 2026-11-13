package de.example.whold;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class WHoldClient implements ClientModInitializer {
    private enum Move {
        W, A, D, SNEAK
    }

    private static Move activeMove = null;
    private static long startAtNanos = 0L;
    private static long releaseAtNanos = 0L;
    private static boolean waiting = false;
    private static boolean holding = false;

    // ---- Barrier Boom (aus der Quoi-Mod) ----
    private static final int BOOM_HOTBAR_SLOT = 8;   // TNT liegt immer auf Hotbar-Slot 8 (wird nicht erkannt)
    private static final int BOOM_CLICK_INTERVAL_TICKS = 2;   // alle 2 Ticks ein Rechtsklick (10x pro Sekunde)
    private static final String GATE_DESTROYED_MESSAGE = "The gate has been destroyed!";
    private static boolean boomEnabled = false;
    private static boolean boomDone = false;      // Nachricht kam -> nicht mehr klicken
    private static int boomCooldownTicks = 0;

    private static int pendingHotbarSlot = -1;
    private static long switchSlotAtNanos = 0L;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                literal("move")
                    .then(moveArgument("w", Move.W))
                    .then(moveArgument("a", Move.A))
                    .then(moveArgument("d", Move.D))
                    .then(moveArgument("sneak", Move.SNEAK))
            );

            // /boom          -> an/aus umschalten
            // /boom on|off   -> gezielt an oder aus
            dispatcher.register(
                literal("boom")
                    .executes(context -> setBoom(context.getSource(), !boomEnabled))
                    .then(literal("on").executes(context -> setBoom(context.getSource(), true)))
                    .then(literal("off").executes(context -> setBoom(context.getSource(), false)))
            );

            dispatcher.register(
                literal("slot")
                    .then(argument("hotbar_nummer", IntegerArgumentType.integer(1, 9))
                        .executes(context -> scheduleSlotSwitch(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "hotbar_nummer"),
                            0.0f
                        ))
                        .then(argument("verzoegerung", FloatArgumentType.floatArg(0.0f, 3600.0f))
                            .executes(context -> scheduleSlotSwitch(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "hotbar_nummer"),
                                FloatArgumentType.getFloat(context, "verzoegerung")
                            ))
                        )
                    )
            );
        });

        // Gate zerstoert -> Klicken beenden
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && boomEnabled && message.getString().contains(GATE_DESTROYED_MESSAGE)) {
                boomDone = true;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.nanoTime();

            tickBoom(client);

            if (pendingHotbarSlot >= 0 && now >= switchSlotAtNanos) {
                if (client.player != null) {
                    client.player.getInventory().setSelectedSlot(pendingHotbarSlot);
                }
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
        });
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

                        if (activeMove != null) {
                            release(client, activeMove);
                        }

                        activeMove = move;
                        startAtNanos = System.nanoTime() + (long) (delay * 1_000_000_000L);
                        releaseAtNanos = startAtNanos + (long) (duration * 1_000_000_000L);
                        waiting = delay > 0.0f;
                        holding = false;

                        if (!waiting) {
                            press(client, activeMove);
                            holding = true;
                        }

                        context.getSource().sendFeedback(Component.literal(
                            "Halte " + displayName(move) + " für " + duration +
                            " Sekunden" + (delay > 0.0f ? " nach " + delay + " Sekunden Verzögerung." : ".")
                        ));
                        return 1;
                    })
                )
            );
    }


    private static int scheduleSlotSwitch(
        net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
        int hotbarNumber,
        float delaySeconds
    ) {
        long delayNanos = (long) (delaySeconds * 1_000_000_000L);
        switchSlotAtNanos = System.nanoTime() + delayNanos;
        pendingHotbarSlot = hotbarNumber - 1;

        if (delaySeconds <= 0.0f) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.getInventory().setSelectedSlot(pendingHotbarSlot);
            }
            pendingHotbarSlot = -1;
        }

        source.sendFeedback(Component.literal(
            "Wechsle zu Hotbar-Slot " + hotbarNumber +
            (delaySeconds > 0.0f ? " in " + delaySeconds + " Sekunden." : ".")
        ));
        return 1;
    }

    private static int setBoom(
        net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
        boolean enabled
    ) {
        boomEnabled = enabled;
        boomDone = false;
        boomCooldownTicks = 0;
        source.sendFeedback(Component.literal(
            "Barrier Boom " + (enabled ? "an (TNT auf Hotbar-Slot " + BOOM_HOTBAR_SLOT + ")." : "aus.")
        ));
        return 1;
    }

    /**
     * Schaust du auf eine Barrier oder Nether Bricks: Slot 8 waehlen und mit dem TNT rechtsklicken - immer wieder,
     * bis "The gate has been destroyed!" im Chat kommt. Danach wird nicht mehr geklickt, bis du das Ziel
     * aus dem Fadenkreuz verlierst (dann ist der Boom fuer das naechste Gate wieder bereit).
     */
    private static void tickBoom(Minecraft client) {
        if (!boomEnabled) return;
        if (client.player == null || client.level == null || client.gameMode == null) {
            boomDone = false;
            boomCooldownTicks = 0;
            return;
        }
        if (client.gui.screen() != null) return;

        if (boomCooldownTicks > 0) boomCooldownTicks--;

        boolean onTarget = false;
        HitResult result = client.hitResult;
        if (result instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            Block block = client.level.getBlockState(hit.getBlockPos()).getBlock();

            if (block == Blocks.BARRIER || block == Blocks.NETHER_BRICKS) {
                onTarget = true;

                if (!boomDone && boomCooldownTicks <= 0) {
                    client.player.getInventory().setSelectedSlot(BOOM_HOTBAR_SLOT - 1);

                    // Rechtsklick wie in Vanilla: erst auf den Block, wenn das nichts bewirkt, ins Leere
                    var interaction = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
                    if (!interaction.consumesAction()) {
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    }
                    client.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

                    boomCooldownTicks = BOOM_CLICK_INTERVAL_TICKS;
                }
            }
        }

        if (!onTarget) boomDone = false;
    }

    private static String displayName(Move move) {
        return switch (move) {
            case W -> "W";
            case A -> "A";
            case D -> "D";
            case SNEAK -> "Sneak";
        };
    }

    private static void press(Minecraft client, Move move) {
        switch (move) {
            case W -> {
                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
            }
            case A -> client.options.keyLeft.setDown(true);
            case D -> client.options.keyRight.setDown(true);
            case SNEAK -> client.options.keyShift.setDown(true);
        }
    }

    private static void release(Minecraft client, Move move) {
        if (move == null) return;
        switch (move) {
            case W -> {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
            }
            case A -> client.options.keyLeft.setDown(false);
            case D -> client.options.keyRight.setDown(false);
            case SNEAK -> client.options.keyShift.setDown(false);
        }
    }
}
