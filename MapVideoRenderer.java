package ru.ethertv.television;

import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One tile of a synchronized, multi-map EtherTV display. */
public final class MapVideoRenderer extends MapRenderer implements AutoCloseable {
    private final PlaybackFeed feed;
    private final int tileX;
    private final int tileY;

    public MapVideoRenderer(PlaybackFeed feed, int tileX, int tileY) {
        super(false);
        this.feed = feed;
        this.tileX = tileX;
        this.tileY = tileY;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        BufferedImage tile = feed.getTile(tileX, tileY);
        if (tile != null) canvas.drawImage(0, 0, tile);
        else {
            BufferedImage blank = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = blank.createGraphics();
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, 128, 128);
            graphics.dispose();
            canvas.drawImage(0, 0, blank);
            blank.flush();
        }
    }

    @Override
    public void close() {
        feed.close();
    }

    /** Shared decoder/clock: every map in a screen group displays the same source frame. */
    public static final class PlaybackFeed implements AutoCloseable {
        private final JavaPlugin plugin;
        private final Path framesDirectory;
        private final long frameIntervalMillis;
        private final int columns;
        private final int rows;
        private final boolean startAtBeginning;
        private volatile BufferedImage[][] currentTiles;
        private final ScheduledExecutorService loader;
        private volatile long nextFrame;
        private volatile boolean initialized;
        private volatile boolean closed;

        public PlaybackFeed(JavaPlugin plugin, Path framesDirectory, long frameIntervalMillis,
                            boolean startAtBeginning, int columns, int rows) {
            this.plugin = plugin;
            this.framesDirectory = framesDirectory;
            this.frameIntervalMillis = Math.max(250L, frameIntervalMillis);
            this.startAtBeginning = startAtBeginning;
            this.columns = Math.max(1, Math.min(4, columns));
            this.rows = Math.max(1, Math.min(4, rows));
            this.loader = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "EtherTV-MapFrameLoader");
                thread.setDaemon(true);
                return thread;
            });
            loader.scheduleWithFixedDelay(this::loadNextFrame, 0L, this.frameIntervalMillis, TimeUnit.MILLISECONDS);
        }

        private void loadNextFrame() {
            if (closed) return;
            try {
                if (!initialized) {
                    long latest;
                    try (var files = Files.list(framesDirectory)) {
                        latest = files.map(path -> path.getFileName().toString())
                                .filter(PlaybackFeed::isFrameFile)
                                .mapToLong(name -> { try { return frameNumber(name); } catch (NumberFormatException ignored) { return -1L; } })
                                .max().orElse(-1L);
                    }
                    nextFrame = startAtBeginning ? 0L : Math.max(0L, latest);
                    initialized = true;
                }

                Path candidate = null;
                long candidateNumber = Long.MAX_VALUE;
                try (var files = Files.list(framesDirectory)) {
                    for (Path path : files.toList()) {
                        String name = path.getFileName().toString();
                        if (!isFrameFile(name)) continue;
                        try {
                            long number = frameNumber(name);
                            if (number >= nextFrame && number < candidateNumber) {
                                candidate = path;
                                candidateNumber = number;
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
                if (candidate == null) return;

                BufferedImage loaded = ImageIO.read(candidate.toFile());
                if (loaded == null) return;
                BufferedImage scaled = scaleToScreen(loaded, columns * 128, rows * 128);
                loaded.flush();
                nextFrame = candidateNumber + 1L;
                BufferedImage[][] nextTiles = splitTiles(scaled);
                scaled.flush();
                synchronized (this) {
                    if (closed) flushTiles(nextTiles);
                    else {
                        BufferedImage[][] previous = currentTiles;
                        currentTiles = nextTiles;
                        flushTiles(previous);
                    }
                }
            } catch (IOException exception) {
                if (!closed) plugin.getLogger().warning("Не удалось прочитать кадр EtherTV: " + exception.getMessage());
            } catch (RuntimeException exception) {
                if (!closed) plugin.getLogger().warning("Ошибка видеопотока EtherTV: " + exception.getMessage());
            }
        }

        private static BufferedImage scaleToScreen(BufferedImage source, int width, int height) {
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = result.createGraphics();
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            double scale = Math.min((double) width / source.getWidth(), (double) height / source.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int x = (width - drawWidth) / 2;
            int y = (height - drawHeight) / 2;
            Image scaled = source.getScaledInstance(drawWidth, drawHeight, Image.SCALE_FAST);
            graphics.drawImage(scaled, x, y, null);
            graphics.dispose();
            return result;
        }

        private BufferedImage[][] splitTiles(BufferedImage source) {
            BufferedImage[][] tiles = new BufferedImage[rows][columns];
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++) {
                    BufferedImage tile = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = tile.createGraphics();
                    graphics.drawImage(source, 0, 0, 128, 128,
                            x * 128, y * 128, (x + 1) * 128, (y + 1) * 128, null);
                    graphics.dispose();
                    tiles[y][x] = tile;
                }
            }
            return tiles;
        }

        private static void flushTiles(BufferedImage[][] tiles) {
            if (tiles == null) return;
            for (BufferedImage[] row : tiles) for (BufferedImage tile : row) if (tile != null) tile.flush();
        }

        public synchronized BufferedImage getTile(int x, int y) {
            BufferedImage[][] tiles = currentTiles;
            if (tiles == null || closed || x < 0 || y < 0 || x >= columns || y >= rows) return null;
            return tiles[y][x];
        }

        private static boolean isFrameFile(String name) {
            return name.startsWith("frame-") && (name.endsWith(".png") || name.endsWith(".jpg"));
        }

        private static long frameNumber(String name) {
            int dot = name.lastIndexOf('.');
            return Long.parseLong(name.substring(6, dot));
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            loader.shutdownNow();
            synchronized (this) {
                flushTiles(currentTiles);
                currentTiles = null;
            }
        }
    }
}
