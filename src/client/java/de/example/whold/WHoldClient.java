package de.example.whold;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
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
    private static boolean userOwned = false;   // Taste wurde schon von Hand gehalten, als der Auto-Move startete

    // ---- Barrier Boom (aus der Quoi-Mod) ----
    private static final int BOOM_HOTBAR_SLOT = 8;   // TNT liegt immer auf Hotbar-Slot 8 (wird nicht erkannt)
    private static final int BOOM_CLICK_INTERVAL_TICKS = 2;   // alle 2 Ticks ein Linksklick (10x pro Sekunde)
    private static final String GATE_DESTROYED_MESSAGE = "The gate has been destroyed!";
    private static boolean boomEnabled = false;
    private static final double BOOM_PAUSE_SECONDS = 10.0;   // nach der Gate-Nachricht so lange nicht klicken
    private static volatile long boomPauseUntilNanos = 0L;
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
                boomPauseUntilNanos = System.nanoTime() + (long) (BOOM_PAUSE_SECONDS * 1_000_000_000L);
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

            if (waiting && activeMove != null) {
                if (now >= startAtNanos) {
                    startHold(client, activeMove);
                } else {
                    consumePhysicalPress(keyOf(client, activeMove));   // alte Tastendruecke verwerfen
                }
            }

            if (holding && activeMove != null) {
                if (consumePhysicalPress(keyOf(client, activeMove))) {
                    // Du hast die Taste selbst gedrueckt: deine Eingabe uebernimmt, der Auto-Move endet,
                    // ohne die Taste loszulassen (bei W nur der automatische Sprint).
                    release(client, activeMove, true);
                    holding = false;
                    activeMove = null;
                } else if (now >= releaseAtNanos) {
                    release(client, activeMove, userOwned);
                    holding = false;
                    activeMove = null;
                }
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

                        if (activeMove != null && holding) {
                            release(client, activeMove, userOwned);
                        }

                        activeMove = move;
                        startAtNanos = System.nanoTime() + (long) (delay * 1_000_000_000L);
                        releaseAtNanos = startAtNanos + (long) (duration * 1_000_000_000L);
                        waiting = delay > 0.0f;
                        holding = false;

                        if (!waiting) {
                            startHold(client, activeMove);
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
        boomPauseUntilNanos = 0L;
        boomCooldownTicks = 0;
        source.sendFeedback(Component.literal(
            "Barrier Boom " + (enabled ? "an (TNT auf Hotbar-Slot " + BOOM_HOTBAR_SLOT + ")." : "aus.")
        ));
        return 1;
    }

    /**
     * Schaust du auf eine Barrier oder Nether Bricks: Slot 8 waehlen und mit dem TNT linksklicken - immer wieder,
     * bis "The gate has been destroyed!" im Chat kommt. Danach wird BOOM_PAUSE_SECONDS lang nicht geklickt.
     */
    private static void tickBoom(Minecraft client) {
        if (!boomEnabled) return;
        if (client.player == null || client.level == null || client.gameMode == null) {
            boomPauseUntilNanos = 0L;
            boomCooldownTicks = 0;
            return;
        }
        if (client.gui.screen() != null) return;

        if (boomCooldownTicks > 0) boomCooldownTicks--;

        boolean paused = System.nanoTime() < boomPauseUntilNanos;

        HitResult result = client.hitResult;
        if (result instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            Block block = client.level.getBlockState(hit.getBlockPos()).getBlock();

            if (block == Blocks.BARRIER || block == Blocks.NETHER_BRICKS) {
                if (!paused && boomCooldownTicks <= 0) {
                    client.player.getInventory().setSelectedSlot(BOOM_HOTBAR_SLOT - 1);

                    // Linksklick auf den Block
                    client.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection());
                    client.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

                    boomCooldownTicks = BOOM_CLICK_INTERVAL_TICKS;
                }
            }
        }
    }

    private static String displayName(Move move) {
        return switch (move) {
            case W -> "W";
            case A -> "A";
            case D -> "D";
            case SNEAK -> "Sneak";
        };
    }

    private static KeyMapping keyOf(Minecraft client, Move move) {
        return switch (move) {
            case W -> client.options.keyUp;
            case A -> client.options.keyLeft;
            case D -> client.options.keyRight;
            case SNEAK -> client.options.keyShift;
        };
    }

    /**
     * Wurde die Taste physisch gedrueckt, seit zuletzt nachgesehen wurde? Programmatisches setDown() zaehlt hier
     * nicht, nur echte Tastendruecke (KeyMapping-Klickzaehler).
     */
    private static boolean consumePhysicalPress(KeyMapping key) {
        boolean pressed = false;
        while (key.consumeClick()) pressed = true;
        return pressed;
    }

    private static void startHold(Minecraft client, Move move) {
        KeyMapping key = keyOf(client, move);
        userOwned = key.isDown();          // hielt der Spieler die Taste schon selbst?
        press(client, move);
        consumePhysicalPress(key);         // Druecke von vorher verwerfen
        waiting = false;
        holding = true;
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

    /** keepKey = true: die Taste selbst bleibt gedrueckt (gehoert dem Spieler), nur der Auto-Sprint wird geloest. */
    private static void release(Minecraft client, Move move, boolean keepKey) {
        if (move == null) return;
        switch (move) {
            case W -> {
                if (!keepKey) client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
            }
            case A -> { if (!keepKey) client.options.keyLeft.setDown(false); }
            case D -> { if (!keepKey) client.options.keyRight.setDown(false); }
            case SNEAK -> { if (!keepKey) client.options.keyShift.setDown(false); }
        }
    }
}
