package dev.example.teamlocator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier; // aeltere Mappings: ResourceLocation
import net.minecraft.world.phys.Vec3;

/**
 * Team Locator
 *
 * Senden:  Taste P = Position einmal teilen, Taste O = Auto-Teilen an/aus
 *          Es wird "<teamCommand> [TL] <Name> <x> <y> <z> <dimension>" gesendet,
 *          z.B. "/team chat [TL] Steve 120 64 -340 minecraft:overworld".
 * Empfangen: Jede Chat-/Systemnachricht mit "[TL] ..." wird erkannt und als
 *          Markierung (Name + Koordinaten darunter) in der Welt angezeigt.
 */
public class TeamLocatorClient implements ClientModInitializer {

    // ---------------------------------------------------------------- Config
    private static final Path CONFIG =
            FabricLoader.getInstance().getConfigDir().resolve("team-locator.properties");

    /** Befehl fuer den Team-Chat OHNE Slash. Je nach Server anpassen (/tl setcommand ...). */
    private static String teamCommand = "team chat";
    /** Abstand beim Auto-Teilen in Sekunden (min. 5, damit der Spamfilter nicht anspringt). */
    private static int intervalSeconds = 15;
    /** [TL]-Nachrichten im Chat ausblenden? */
    private static boolean hideMessages = true;
    /** Markierungen nach so vielen Sekunden ohne Update entfernen. */
    private static final int EXPIRE_SECONDS = 300;

    // ---------------------------------------------------------------- State
    private record Mark(String name, String dimension, double x, double y, double z, long timeMs) {}

    private static final Map<String, Mark> MARKS = new ConcurrentHashMap<>();
    private static final Pattern TL = Pattern.compile(
            "\\[TL]\\s+([A-Za-z0-9_]{1,16})\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)");

    private static KeyMapping shareOnceKey;
    private static KeyMapping toggleAutoKey;
    private static boolean autoShare = false;
    private static int ticksUntilNextShare = 0;

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyMapping.Category cat = KeyMapping.Category.MISC; // vor 1.21.9: String "key.categories.misc"
        shareOnceKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.team_locator.share_once", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, cat));
        toggleAutoKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.team_locator.toggle_auto", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, cat));

        ClientTickEvents.END_CLIENT_TICK.register(TeamLocatorClient::tick);

        // Team-Chat von Plugins kommt meist als Systemnachricht, manchmal als Spielerchat -> beides abfangen
        ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> !handleIncoming(msg));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((msg, signed, sender, params, ts) -> !handleIncoming(msg));

        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            MARKS.clear();
            autoShare = false; // nach Serverwechsel nie versehentlich weiter senden
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("team_locator", "markers"),
                (g, delta) -> render(Minecraft.getInstance(), g));

        registerCommands();
    }

    // ---------------------------------------------------------------- Senden

    private static void tick(Minecraft mc) {
        while (shareOnceKey.consumeClick()) sharePosition(mc);

        while (toggleAutoKey.consumeClick()) {
            autoShare = !autoShare;
            ticksUntilNextShare = 0;
            info(mc, autoShare
                    ? "Auto-Teilen AN (alle " + intervalSeconds + "s an: /" + teamCommand + ")"
                    : "Auto-Teilen AUS");
        }

        if (autoShare && mc.player != null) {
            if (--ticksUntilNextShare <= 0) {
                sharePosition(mc);
                ticksUntilNextShare = Math.max(5, intervalSeconds) * 20;
            }
        }

        long now = System.currentTimeMillis();
        MARKS.values().removeIf(m -> now - m.timeMs() > EXPIRE_SECONDS * 1000L);
    }

    private static void sharePosition(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (teamCommand.isBlank()) {
            info(mc, "Kein Team-Befehl gesetzt. Nutze /tl setcommand <befehl>");
            return;
        }
        String msg = String.format(Locale.ROOT, "[TL] %s %d %d %d %s",
                mc.player.getGameProfile().name(),
                (int) Math.floor(mc.player.getX()),
                (int) Math.floor(mc.player.getY()),
                (int) Math.floor(mc.player.getZ()),
                currentDimension(mc));
        // Immer als Befehl senden, nie in den oeffentlichen Chat.
        mc.getConnection().sendCommand(teamCommand + " " + msg);
    }

    // ------------------------------------------------------------ Empfangen

    /** @return true = Nachricht ausblenden */
    private static boolean handleIncoming(Component message) {
        String text = message.getString().replaceAll("§.", "");
        Matcher m = TL.matcher(text);
        if (!m.find()) return false;

        Minecraft mc = Minecraft.getInstance();
        String name = m.group(1);
        boolean own = mc.player != null && mc.player.getGameProfile().name().equalsIgnoreCase(name);
        if (!own) {
            MARKS.put(name.toLowerCase(Locale.ROOT), new Mark(name, m.group(5),
                    Integer.parseInt(m.group(2)) + 0.5, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)) + 0.5, System.currentTimeMillis()));
        }
        return hideMessages;
    }

    // ------------------------------------------------------------ Anzeige

    private static void render(Minecraft mc, GuiGraphics g) {
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        int w = g.guiWidth();
        int h = g.guiHeight();

        if (autoShare) {
            g.drawString(mc.font, "● Teile Position", 6, h - 14, 0xFF55FF55, true);
        }

        String myDim = currentDimension(mc);
        Vec3 eye = mc.player.getEyePosition();
        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
        double fov = Math.toRadians(mc.options.fov().get());
        double scale = (h / 2.0) / Math.tan(fov / 2.0);
        long now = System.currentTimeMillis();

        // Kleine Liste oben links (auch fuer andere Dimensionen)
        int listY = 6;
        for (Mark mk : new ArrayList<>(MARKS.values())) {
            long age = (now - mk.timeMs()) / 1000;
            int color = mk.dimension().equals(myDim) ? 0xFFFFFFFF : 0xFFFFD080;
            g.drawString(mc.font, mk.name() + ": " + (int) mk.x() + " " + (int) mk.y() + " " + (int) mk.z()
                    + (mk.dimension().equals(myDim) ? "" : " (" + shortDim(mk.dimension()) + ")")
                    + " - vor " + age + "s", 6, listY, color, true);
            listY += 10;
        }

        // Markierungen in der Welt (gleiche Dimension)
        for (Mark mk : new ArrayList<>(MARKS.values())) {
            if (!mk.dimension().equals(myDim)) continue;

            double dx = mk.x() - eye.x;
            double dy = mk.y() + 1.0 - eye.y; // etwa Kopfhoehe
            double dz = mk.z() - eye.z;

            // In Kamera-Koordinaten umrechnen
            double fwdH = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;      // horizontal nach vorne
            double right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;     // nach rechts
            double depth = fwdH * Math.cos(pitch) - dy * Math.sin(pitch);
            double up = fwdH * Math.sin(pitch) + dy * Math.cos(pitch);

            double sx, sy;
            boolean onScreen;
            if (depth > 0.1) {
                sx = w / 2.0 + right / depth * scale;
                sy = h / 2.0 - up / depth * scale;
                onScreen = sx >= 0 && sx <= w && sy >= 0 && sy <= h;
            } else {
                // hinter dir -> an den Rand in die richtige Richtung
                sx = right >= 0 ? w : 0;
                sy = h / 2.0;
                onScreen = false;
            }
            if (!onScreen) {
                sx = Math.max(40, Math.min(w - 40, sx));
                sy = Math.max(20, Math.min(h - 30, sy));
            }

            long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
            long age = (now - mk.timeMs()) / 1000;
            int x = (int) sx;
            int y = (int) sy;

            String line1 = mk.name() + (onScreen ? "" : (right >= 0 ? " →" : " ←"));
            String line2 = (int) mk.x() + " " + (int) mk.y() + " " + (int) mk.z();
            String line3 = dist + " Bloecke" + (age > intervalSeconds + 5 ? " (vor " + age + "s)" : "");

            int bw = Math.max(mc.font.width(line1), Math.max(mc.font.width(line2), mc.font.width(line3))) + 6;
            g.fill(x - bw / 2, y - 2, x + bw / 2, y + 30, 0x80000000);
            g.drawCenteredString(mc.font, line1, x, y, 0xFF55FFFF);
            g.drawCenteredString(mc.font, line2, x, y + 10, 0xFFFFFFFF);
            g.drawCenteredString(mc.font, line3, x, y + 20, 0xFFAAAAAA);
        }
    }

    private static String currentDimension(Minecraft mc) {
        return mc.level.dimension().identifier().toString(); // aeltere Mappings: .location()
    }

    private static String shortDim(String dim) {
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }

    private static void info(Minecraft mc, String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Team Locator] " + text), false);
    }

    // ------------------------------------------------------------ Befehle

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(
                ClientCommandManager.literal("tl")
                        .then(ClientCommandManager.literal("setcommand")
                                .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String c = StringArgumentType.getString(ctx, "command").trim();
                                            if (c.startsWith("/")) c = c.substring(1);
                                            teamCommand = c;
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Component.literal(
                                                    "[Team Locator] Team-Befehl: /" + c + " [TL] ..."));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("interval")
                                .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(5, 600))
                                        .executes(ctx -> {
                                            intervalSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Component.literal(
                                                    "[Team Locator] Intervall: " + intervalSeconds + "s"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("hide").executes(ctx -> {
                            hideMessages = !hideMessages;
                            saveConfig();
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[Team Locator] [TL]-Nachrichten " + (hideMessages ? "ausgeblendet" : "sichtbar")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            MARKS.clear();
                            ctx.getSource().sendFeedback(Component.literal("[Team Locator] Markierungen geloescht"));
                            return 1;
                        }))
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[Team Locator] Befehl: /" + teamCommand + " | Intervall: " + intervalSeconds
                                            + "s | Auto: " + (autoShare ? "an" : "aus")
                                            + " | Tasten: P = einmal, O = Auto an/aus"));
                            return 1;
                        })));
    }

    private static void loadConfig() {
        Properties p = new Properties();
        if (Files.exists(CONFIG)) {
            try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.err.println("[Team Locator] Config lesen fehlgeschlagen: " + e.getMessage());
            }
        }
        teamCommand = p.getProperty("teamCommand", teamCommand);
        try {
            intervalSeconds = Math.max(5, Integer.parseInt(p.getProperty("intervalSeconds", "" + intervalSeconds)));
        } catch (NumberFormatException ignored) { }
        hideMessages = Boolean.parseBoolean(p.getProperty("hideMessages", "" + hideMessages));
        saveConfig();
    }

    private static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("teamCommand", teamCommand);
        p.setProperty("intervalSeconds", "" + intervalSeconds);
        p.setProperty("hideMessages", "" + hideMessages);
        try {
            Files.createDirectories(CONFIG.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                p.store(w, "Team Locator");
            }
        } catch (IOException e) {
            System.err.println("[Team Locator] Config speichern fehlgeschlagen: " + e.getMessage());
        }
    }
}
