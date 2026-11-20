package ru.ethertv.client.youtube;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.ethertv.client.EtherTVClient;

import com.sun.net.httpserver.HttpServer;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Rinku/CEF adapter. The browser renders locally; bounded JPEG snapshots feed the vanilla map screen. */
public final class RinkuYoutubeBrowserSurface implements YoutubeBrowserService.BrowserSurface {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final int FRAME_WIDTH = 320;
    private static final int FRAME_HEIGHT = 180;
    private static final long FRAME_INTERVAL_MS = 500L;
    private static final int MAX_JPEG_BYTES = 180_000;
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static volatile HttpServer localServer;
    private static volatile java.util.concurrent.ExecutorService httpExecutor;
    private static volatile String playerPageUrl;

    private RinkuYoutubeBrowserSurface() { }

    public static void install() {
        YoutubeBrowserService.install(new RinkuYoutubeBrowserSurface());
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    public static void stopAll() {
        for (Session session : SESSIONS.values()) session.close();
        SESSIONS.clear();
        HttpServer server = localServer;
        localServer = null;
        playerPageUrl = null;
        if (server != null) server.stop(0);
        java.util.concurrent.ExecutorService executor = httpExecutor;
        httpExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public void loadOnTelevision(String dimension, int x, int y, int z, String videoId, long startedAt, boolean captureFrames) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return;
        String key = key(dimension, x, y, z);
        stopOnTelevision(dimension, x, y, z);
        try {
            String page = ensurePlayerPage();
            Class<?> rinku = Class.forName("de.keksuccino.rinku.Rinku");
            boolean initialized = (boolean) rinku.getMethod("isInitialized").invoke(null);
            if (!initialized) {
                boolean ok = (boolean) rinku.getMethod("initialize").invoke(null);
                initialized = ok && (boolean) rinku.getMethod("isInitialized").invoke(null);
            }
            if (!initialized) {
                mc.player.displayClientMessage(Component.literal("EtherTV: Rinku не смог инициализировать Chromium. Проверь нативный runtime и журнал клиента."), false);
                return;
            }
            Object browser = rinku.getMethod("createBrowser", String.class, boolean.class, int.class, int.class)
                    .invoke(null, "about:blank", false, WIDTH, HEIGHT);
            Method loadUrl = browser.getClass().getMethod("loadURL", String.class);
            Method executeJs = browser.getClass().getMethod("executeJavaScript", String.class, String.class, int.class);
            Method screenshot = browser.getClass().getMethod("createScreenshot", boolean.class);
            Method close = browser.getClass().getMethod("close");
            loadUrl.invoke(browser, page);
            SESSIONS.put(key, new Session(key, dimension, x, y, z, videoId, startedAt,
                    browser, executeJs, screenshot, close, page, captureFrames));
        } catch (Throwable error) {
            mc.player.displayClientMessage(Component.literal("EtherTV: не удалось запустить Chromium/Rinku: " + rootMessage(error)), false);
        }
    }

    @Override
    public void stopOnTelevision(String dimension, int x, int y, int z) {
        Session session = SESSIONS.remove(key(dimension, x, y, z));
        if (session != null) session.close();
    }

    @Override
    public void controlOnTelevision(String dimension, int x, int y, int z, String action, double seconds) {
        Session session = SESSIONS.get(key(dimension, x, y, z));
        if (session == null) return;
        String js = switch (action) {
            case "play" -> "window.etherTvPlay && window.etherTvPlay();";
            case "pause" -> "window.etherTvPause && window.etherTvPause();";
            case "seek" -> "window.etherTvSeek && window.etherTvSeek(" + Math.max(0, Math.min(86_400, seconds)) + ");";
            default -> null;
        };
        if (js != null) session.runJs(js);
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Session session : SESSIONS.values()) {
            if (session.closed) continue;
            if (!session.playerStarted && now - session.createdAt > 2200L) {
                long offset = Math.max(0L, (now - session.startedAt) / 1000L);
                session.runJs("window.etherTvLoad && window.etherTvLoad('" + session.videoId + "'," + offset + ");");
                session.playerStarted = true;
            }
            session.captureFrame(now);
        }
    }

    private static synchronized String ensurePlayerPage() throws Exception {
        if (playerPageUrl != null && localServer != null) return playerPageUrl;
        byte[] html;
        try (InputStream in = RinkuYoutubeBrowserSurface.class.getResourceAsStream("/assets/ethertv/web/youtube-player.html")) {
            if (in == null) throw new IllegalStateException("youtube-player.html отсутствует в JAR EtherTV Client");
            html = in.readAllBytes();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/youtube-player.html", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            exchange.sendResponseHeaders(200, html.length);
            try (var output = exchange.getResponseBody()) { output.write(html); }
        });
        httpExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "EtherTV-YouTube-LocalPage");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(httpExecutor);
        server.start();
        localServer = server;
        playerPageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube-player.html";
        return playerPageUrl;
    }

    private static String key(String dimension, int x, int y, int z) { return dimension + ":" + x + ":" + y + ":" + z; }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class Session {
        final String key, dimension, videoId, pageUrl;
        final boolean captureFrames;
        final int x, y, z;
        final long startedAt, createdAt = System.currentTimeMillis();
        final Object browser;
        final Method executeJs, screenshot, close;
        volatile boolean closed, playerStarted;
        volatile long lastFrameAt;
        volatile CompletableFuture<?> pendingScreenshot;
        int sequence;

        Session(String key, String dimension, int x, int y, int z, String videoId, long startedAt,
                Object browser, Method executeJs, Method screenshot, Method close, String pageUrl, boolean captureFrames) {
            this.key=key; this.dimension=dimension; this.x=x; this.y=y; this.z=z; this.videoId=videoId;
            this.startedAt=startedAt; this.browser=browser; this.executeJs=executeJs; this.screenshot=screenshot;
            this.close=close; this.pageUrl=pageUrl; this.captureFrames=captureFrames;
        }

        void runJs(String js) {
            if (closed) return;
            try { executeJs.invoke(browser, js, pageUrl, 0); } catch (Throwable ignored) { }
        }

        void captureFrame(long now) {
            if (!captureFrames) return;
            CompletableFuture<?> pending = pendingScreenshot;
            if (pending != null && pending.isDone()) {
                pendingScreenshot = null;
                try {
                    Object result = pending.getNow(null);
                    if (result instanceof BufferedImage image) encodeAndSend(image);
                } catch (Throwable ignored) { }
            }
            if (pendingScreenshot != null || now - lastFrameAt < FRAME_INTERVAL_MS) return;
            lastFrameAt = now;
            try {
                Object result = screenshot.invoke(browser, true);
                if (result instanceof CompletableFuture<?> future) pendingScreenshot = future;
            } catch (Throwable ignored) { }
        }

        void encodeAndSend(BufferedImage source) {
            try {
                BufferedImage scaled = new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source, 0, 0, FRAME_WIDTH, FRAME_HEIGHT, null);
                g.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream(32_000);
                ImageIO.write(scaled, "jpg", out);
                scaled.flush();
                byte[] jpeg = out.toByteArray();
                if (jpeg.length <= MAX_JPEG_BYTES) {
                    int seq = sequence++;
                    EtherTVClient.send(EtherTVClient.packet(packet -> {
                        packet.writeByte(4); // YOUTUBE_FRAME
                        packet.writeUTF(dimension);
                        packet.writeInt(x); packet.writeInt(y); packet.writeInt(z);
                        packet.writeInt(seq); packet.writeInt(jpeg.length); packet.write(jpeg);
                    }));
                }
            } catch (Throwable ignored) {
            } finally { source.flush(); }
        }

        void close() {
            if (closed) return;
            closed = true;
            CompletableFuture<?> pending = pendingScreenshot;
            if (pending != null) pending.cancel(true);
            try { close.invoke(browser); } catch (Throwable ignored) { }
        }
    }
}
