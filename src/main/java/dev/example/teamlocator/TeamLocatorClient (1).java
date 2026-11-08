package dev.example.teamlocator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier; // older mappings: ResourceLocation

/**
 * Team Locator – encrypted channel via HiveMQ
 *
 * Positions are NOT sent through Minecraft chat. They are encrypted (AES-256-GCM) and sent
 * through a public MQTT broker (default: broker.hivemq.com, TLS) straight to your friends' mods.
 * Only people with the same team password can read them. The Minecraft server, its admins
 * and other players see nothing. The mod only connects while you are on a multiplayer server
 * (never in singleplayer) and while the mod is turned on.
 *
 * All /tl commands are client-side and are never sent to the server.
 */
public class TeamLocatorClient implements ClientModInitializer {

    // ------------------------------------------------------------------ Config
    private static final Path CONFIG =
            FabricLoader.getInstance().getConfigDir().resolve("team-locator.properties");

    private static boolean enabled = true;
    private static String password = "";
    private static String broker = "broker.hivemq.com:8883";
    private static int intervalSeconds = 2;
    private static float hudScale = 0.75f;

    private static final int EXPIRE_SECONDS = 120;     // entry disappears without updates
    private static final int STALE_SECONDS = 30;       // shown grey after this
    private static final int FORCE_SEND_SECONDS = 20;  // send at least this often, even when standing still
    private static final double MIN_MOVE = 3.0;        // blocks moved before sending again
    private static final long MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L;

    // ------------------------------------------------------------------- State
    private record Mark(String name, String dimension, double x, double y, double z, long timeMs) {}

    private static final Map<String, Mark> MARKS = new ConcurrentHashMap<>();
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private static KeyMapping toggleShareKey;
    private static KeyMapping toggleAllKey;

    private static boolean sharing = false;
    private static int tickCounter = 0;
    private static long lastSendMs = 0;
    private static double lastX, lastY, lastZ;
    private static String lastDim = "";

    private static volatile TeamCrypto crypto;
    private static MqttLink link;
    private static volatile String myName = "";
    private static volatile String myServer = "";

    @Override
    public void onInitializeClient() {
        loadConfig();
        crypto = password.isEmpty() ? null : TeamCrypto.create(password);

        KeyMapping.Category cat = KeyMapping.Category.MISC; // before 1.21.9: "key.categories.misc"
        toggleShareKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.team_locator.toggle_share", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, cat));
        toggleAllKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.team_locator.toggle_all", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));

        ClientTickEvents.END_CLIENT_TICK.register(TeamLocatorClient::tick);

        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            if (sharing) sendBye();
            sharing = false;
            MARKS.clear();
            stopLink();
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("team_locator", "hud"),
                (g, delta) -> render(Minecraft.getInstance(), g));

        registerCommands();
    }

    // ------------------------------------------------------------------ Tick

    private static void tick(Minecraft mc) {
        boolean inGame = mc.player != null && mc.level != null && mc.getConnection() != null;
        boolean onServer = inGame && mc.getCurrentServer() != null && !mc.isLocalServer();
        if (inGame) {
            myName = mc.player.getGameProfile().name();
            myServer = onServer ? mc.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "";
        }

        // Only connect to the broker when needed: mod on, password set, on a multiplayer server
        boolean wantLink = enabled && onServer && crypto != null;
        if (wantLink && link == null) startLink();
        if (!wantLink && link != null) stopLink();

        while (toggleAllKey.consumeClick()) setEnabled(mc, !enabled);

        while (toggleShareKey.consumeClick()) {
            if (!enabled) {
                actionbar(mc, "§cTeam Locator is off (" + keyName(toggleAllKey) + ")");
                continue;
            }
            if (crypto == null) {
                info(mc, "Set a team password first: /tl team <password>");
                continue;
            }
            if (!onServer) {
                actionbar(mc, "§cOnly works on multiplayer servers");
                continue;
            }
            sharing = !sharing;
            if (sharing) {
                lastSendMs = 0; // send immediately
            } else {
                sendBye();
            }
            actionbar(mc, sharing ? "§aSharing position: ON" : "§cSharing position: OFF");
        }

        long now = System.currentTimeMillis();
        MARKS.values().removeIf(m -> now - m.timeMs() > EXPIRE_SECONDS * 1000L);

        if (!enabled || !sharing || !onServer || link == null) return;
        if (lastSendMs != 0 && ++tickCounter < Math.max(1, intervalSeconds) * 20) return;
        tickCounter = 0;

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        String dim = currentDimension(mc);
        double moved = Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
        boolean due = lastSendMs == 0 || moved >= MIN_MOVE || !dim.equals(lastDim)
                || now - lastSendMs >= FORCE_SEND_SECONDS * 1000L;
        if (!due) return;

        publish(String.join("|", "v1", "pos", myName, myServer, dim,
                Integer.toString((int) Math.floor(x)), Integer.toString((int) Math.floor(y)),
                Integer.toString((int) Math.floor(z)), Long.toString(now)));
        lastSendMs = now;
        lastX = x; lastY = y; lastZ = z; lastDim = dim;
    }

    private static void sendBye() {
        publish(String.join("|", "v1", "bye", myName, myServer, Long.toString(System.currentTimeMillis())));
    }

    private static void publish(String plain) {
        TeamCrypto c = crypto;
        MqttLink l = link;
        if (c == null || l == null) return;
        byte[] data = c.encrypt(plain);
        if (data != null) l.publish(data);
    }

    // ------------------------------------------------------------------ Receiving (network thread)

    private static void onMessage(byte[] data) {
        TeamCrypto c = crypto;
        if (c == null || !enabled) return;
        String plain = c.decrypt(data);
        if (plain == null) return; // wrong password or foreign data -> ignore

        String[] p = plain.split("\\|");
        if (p.length < 5 || !p[0].equals("v1")) return;
        String type = p[1], name = p[2], server = p[3];
        if (name.equalsIgnoreCase(myName)) return;            // own message
        if (myServer.isEmpty() || !server.equals(myServer)) return; // different Minecraft server
        String key = name.toLowerCase(Locale.ROOT);

        try {
            if (type.equals("bye")) {
                MARKS.remove(key);
            } else if (type.equals("pos") && p.length >= 9) {
                long sent = Long.parseLong(p[8]);
                if (Math.abs(System.currentTimeMillis() - sent) > MAX_CLOCK_SKEW_MS) return;
                MARKS.put(key, new Mark(name, p[4],
                        Integer.parseInt(p[5]) + 0.5, Integer.parseInt(p[6]), Integer.parseInt(p[7]) + 0.5,
                        System.currentTimeMillis()));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------------ Connection

    private static void startLink() {
        TeamCrypto c = crypto;
        if (c == null) return;
        String host = broker;
        int port = 8883;
        int i = broker.lastIndexOf(':');
        if (i > 0) {
            host = broker.substring(0, i);
            try { port = Integer.parseInt(broker.substring(i + 1)); } catch (NumberFormatException ignored) { }
        }
        link = new MqttLink(host, port, c.topic, TeamLocatorClient::onMessage);
        link.start();
    }

    private static void stopLink() {
        if (link != null) {
            link.stop();
            link = null;
        }
    }

    private static void setEnabled(Minecraft mc, boolean on) {
        if (!on && sharing) sendBye();
        enabled = on;
        if (!on) {
            sharing = false;
            MARKS.clear();
            stopLink();
        }
        saveConfig();
        actionbar(mc, on ? "§aTeam Locator: ON" : "§cTeam Locator: OFF");
    }

    // ------------------------------------------------------------------ HUD

    private static void render(Minecraft mc, GuiGraphics g) {
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (MARKS.isEmpty() && !sharing) return;

        String myDim = currentDimension(mc);
        long now = System.currentTimeMillis();
        double px = mc.player.getX(), pz = mc.player.getZ();

        record Line(String text, int color, double dist) {}
        List<Line> lines = new ArrayList<>();
        for (Mark mk : MARKS.values()) {
            long age = (now - mk.timeMs()) / 1000;
            boolean stale = age > STALE_SECONDS;
            if (!mk.dimension().equals(myDim)) {
                lines.add(new Line(mk.name() + " §7(" + dimName(mk.dimension()) + ")", 0xFFFFD080, Double.MAX_VALUE));
                continue;
            }
            double dx = mk.x() - px, dz = mk.z() - pz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ((targetYaw - mc.player.getYRot()) % 360 + 540) % 360 - 180;
            int idx = (int) Math.floorMod(Math.round(rel / 45.0), 8L);
            lines.add(new Line(mk.name() + " " + ARROWS[idx] + " " + Math.round(dist) + " blocks"
                    + (stale ? " §8(old)" : ""), stale ? 0xFFAAAAAA : 0xFFFFFFFF, dist));
        }
        lines.sort(Comparator.comparingDouble(Line::dist));

        boolean online = link != null && link.isConnected();
        String header = "Team" + (sharing ? " §a●" : "") + (online ? "" : " §c(offline)");

        int pad = 3, lineH = 10;
        int w = mc.font.width(header);
        for (Line l : lines) w = Math.max(w, mc.font.width(l.text()));
        int h = lineH * (1 + lines.size());

        g.pose().pushMatrix();
        g.pose().translate(4, 4);
        g.pose().scale(hudScale, hudScale);
        g.fill(0, 0, w + pad * 2, h + pad * 2 - 2, 0x66000000);
        g.drawString(mc.font, header, pad, pad, 0xFF80FFFF, true);
        int y = pad + lineH;
        for (Line l : lines) {
            g.drawString(mc.font, l.text(), pad, y, l.color(), true);
            y += lineH;
        }
        g.pose().popMatrix();
    }

    private static String currentDimension(Minecraft mc) {
        return mc.level.dimension().identifier().toString(); // older mappings: .location()
    }

    private static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dim.substring(dim.indexOf(':') + 1);
        };
    }

    private static String keyName(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    private static void info(Minecraft mc, String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§b[Team Locator]§r " + text), false);
    }

    private static void actionbar(Minecraft mc, String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), true);
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Component.literal("§b[Team Locator]§r " + text));
    }

    // ------------------------------------------------------------------ Commands (client-side only)

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(
                ClientCommandManager.literal("tl")
                        .executes(ctx -> {
                            boolean online = link != null && link.isConnected();
                            feedback(ctx, (enabled ? "§aON" : "§cOFF")
                                    + "§r | Password: " + (crypto != null ? "set" : "§cmissing§r")
                                    + " | Channel: " + (online ? "§aconnected§r" : "§cdisconnected§r")
                                    + " | Sharing: " + (sharing ? "on" : "off")
                                    + " | Keys: " + keyName(toggleShareKey) + " = share, "
                                    + keyName(toggleAllKey) + " = mod on/off");
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            setEnabled(Minecraft.getInstance(), true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            setEnabled(Minecraft.getInstance(), false);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("team")
                                .then(ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String pw = StringArgumentType.getString(ctx, "password").trim();
                                            if (pw.length() < 8) {
                                                feedback(ctx, "§cPassword too short (at least 8 characters, 15+ is better).");
                                                return 0;
                                            }
                                            if (sharing) sendBye();
                                            sharing = false;
                                            stopLink();
                                            MARKS.clear();
                                            password = pw;
                                            crypto = TeamCrypto.create(pw);
                                            saveConfig();
                                            feedback(ctx, "Team password set. Everyone in your team needs exactly the same one.");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("interval")
                                .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 60))
                                        .executes(ctx -> {
                                            intervalSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                            saveConfig();
                                            feedback(ctx, "Interval: " + intervalSeconds + "s");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("size")
                                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(50, 150))
                                        .executes(ctx -> {
                                            hudScale = IntegerArgumentType.getInteger(ctx, "percent") / 100f;
                                            saveConfig();
                                            feedback(ctx, "HUD size: " + Math.round(hudScale * 100) + "%");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("broker")
                                .then(ClientCommandManager.argument("hostport", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            broker = StringArgumentType.getString(ctx, "hostport").trim();
                                            saveConfig();
                                            stopLink();
                                            feedback(ctx, "Broker: " + broker);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            MARKS.clear();
                            feedback(ctx, "HUD cleared.");
                            return 1;
                        }))));
    }

    // ------------------------------------------------------------------ Config

    private static void loadConfig() {
        Properties p = new Properties();
        if (Files.exists(CONFIG)) {
            try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.err.println("[Team Locator] Could not read config: " + e.getMessage());
            }
        }
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "" + enabled));
        password = p.getProperty("password", password);
        broker = p.getProperty("broker", broker);
        try {
            intervalSeconds = Math.max(1, Integer.parseInt(p.getProperty("intervalSeconds", "" + intervalSeconds)));
        } catch (NumberFormatException ignored) { }
        try {
            hudScale = Math.max(0.5f, Math.min(1.5f, Float.parseFloat(p.getProperty("hudScale", "" + hudScale))));
        } catch (NumberFormatException ignored) { }
        saveConfig();
    }

    private static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("enabled", "" + enabled);
        p.setProperty("password", password);
        p.setProperty("broker", broker);
        p.setProperty("intervalSeconds", "" + intervalSeconds);
        p.setProperty("hudScale", "" + hudScale);
        try {
            Files.createDirectories(CONFIG.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                p.store(w, "Team Locator");
            }
        } catch (IOException e) {
            System.err.println("[Team Locator] Could not save config: " + e.getMessage());
        }
    }

    // ======================================================================
    // Encryption: AES-256-GCM, key + topic derived from the password (PBKDF2)
    // ======================================================================
    static final class TeamCrypto {
        private static final SecureRandom RANDOM = new SecureRandom();
        final SecretKeySpec key;
        final String topic;

        private TeamCrypto(SecretKeySpec key, String topic) {
            this.key = key;
            this.topic = topic;
        }

        static TeamCrypto create(String password) {
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] k = f.generateSecret(new PBEKeySpec(password.toCharArray(),
                        "team-locator-v3".getBytes(StandardCharsets.UTF_8), 150_000, 384)).getEncoded();
                SecretKeySpec aes = new SecretKeySpec(Arrays.copyOfRange(k, 0, 32), "AES");
                String topic = "teamlocator/" + HexFormat.of().formatHex(Arrays.copyOfRange(k, 32, 48));
                return new TeamCrypto(aes, topic);
            } catch (Exception e) {
                System.err.println("[Team Locator] Key error: " + e);
                return null;
            }
        }

        byte[] encrypt(String plain) {
            try {
                byte[] iv = new byte[12];
                RANDOM.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
                byte[] out = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, out, 0, iv.length);
                System.arraycopy(ct, 0, out, iv.length, ct.length);
                return out;
            } catch (Exception e) {
                return null;
            }
        }

        String decrypt(byte[] data) {
            if (data == null || data.length < 12 + 16) return null;
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
                return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null; // wrong key or tampered
            }
        }
    }

    // ======================================================================
    // Minimal MQTT 3.1.1 client over TLS (QoS 0 only, no extra libraries)
    // ======================================================================
    static final class MqttLink implements Runnable {
        private final String host;
        private final int port;
        private final String topic;
        private final Consumer<byte[]> onMessage;
        private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>(64);
        private volatile boolean running = true;
        private volatile boolean connected = false;
        private volatile SSLSocket socket;
        private Thread thread;

        MqttLink(String host, int port, String topic, Consumer<byte[]> onMessage) {
            this.host = host;
            this.port = port;
            this.topic = topic;
            this.onMessage = onMessage;
        }

        void start() {
            thread = new Thread(this, "TeamLocator-Link");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            connected = false;
            closeSocket();
            if (thread != null) thread.interrupt();
        }

        boolean isConnected() {
            return connected;
        }

        void publish(byte[] payload) {
            if (connected) outbox.offer(payload);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    session();
                } catch (Exception e) {
                    if (running) System.err.println("[Team Locator] Connection: " + e.getMessage());
                }
                connected = false;
                closeSocket();
                if (!running) break;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private void session() throws IOException {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // verify certificate + hostname
            params.setServerNames(List.of(new SNIHostName(host)));
            s.setSSLParameters(params);
            socket = s;
            s.connect(new InetSocketAddress(host, port), 10_000);
            s.setSoTimeout(15_000);
            s.startHandshake();

            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = new BufferedOutputStream(s.getOutputStream());

            // CONNECT
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeString(body, "MQTT");
            body.write(4);                 // protocol level 3.1.1
            body.write(0x02);              // clean session
            body.write(0); body.write(60); // keep-alive 60 s
            byte[] id = new byte[6];
            new SecureRandom().nextBytes(id);
            writeString(body, "tl-" + HexFormat.of().formatHex(id));
            writePacket(out, 0x10, body.toByteArray());

            int type = in.readUnsignedByte();
            int len = readLength(in);
            byte[] ack = new byte[len];
            in.readFully(ack);
            if ((type >> 4) != 2 || len < 2 || ack[1] != 0) throw new IOException("CONNACK refused");

            // SUBSCRIBE
            body.reset();
            body.write(0); body.write(1);  // packet id 1
            writeString(body, topic);
            body.write(0);                 // QoS 0
            writePacket(out, 0x82, body.toByteArray());

            s.setSoTimeout(0);
            connected = true;
            outbox.clear();

            Thread writer = new Thread(() -> writerLoop(out), "TeamLocator-Writer");
            writer.setDaemon(true);
            writer.start();
            try {
                while (running) {
                    int h = in.readUnsignedByte();
                    int l = readLength(in);
                    byte[] data = new byte[l];
                    in.readFully(data);
                    if ((h >> 4) == 3) { // PUBLISH
                        int qos = (h >> 1) & 3;
                        int tl = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                        int off = 2 + tl + (qos > 0 ? 2 : 0);
                        if (off <= data.length) {
                            try {
                                onMessage.accept(Arrays.copyOfRange(data, off, data.length));
                            } catch (Exception ignored) { }
                        }
                    }
                }
            } finally {
                connected = false;
                writer.interrupt();
            }
        }

        private void writerLoop(OutputStream out) {
            try {
                while (running && connected) {
                    byte[] payload = outbox.poll(25, TimeUnit.SECONDS);
                    if (payload == null) {
                        writePacket(out, 0xC0, new byte[0]); // PINGREQ
                    } else {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        writeString(b, topic);
                        b.write(payload);
                        writePacket(out, 0x30, b.toByteArray()); // PUBLISH QoS 0
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                closeSocket();
            }
        }

        static void writeString(ByteArrayOutputStream b, String s) {
            byte[] d = s.getBytes(StandardCharsets.UTF_8);
            b.write(d.length >> 8);
            b.write(d.length & 0xFF);
            b.write(d, 0, d.length);
        }

        static void writePacket(OutputStream out, int header, byte[] body) throws IOException {
            synchronized (out) {
                out.write(header);
                int len = body.length;
                do {
                    int digit = len % 128;
                    len /= 128;
                    if (len > 0) digit |= 0x80;
                    out.write(digit);
                } while (len > 0);
                out.write(body);
                out.flush();
            }
        }

        static int readLength(DataInputStream in) throws IOException {
            int mult = 1, value = 0, digit, count = 0;
            do {
                digit = in.readUnsignedByte();
                value += (digit & 127) * mult;
                mult *= 128;
                if (++count > 4) throw new IOException("Invalid length");
            } while ((digit & 128) != 0);
            return value;
        }

        private void closeSocket() {
            SSLSocket s = socket;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }
}
