package ru.ethertv.renderer;

import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/**
 * Отдельный компонент кодирования и сохранения кадров.
 * Bukkit World/Block API здесь не вызывается: PNG кодируется в отдельном потоке.
 */
public final class FrameEncoder implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ThreadPoolExecutor worker;

    public FrameEncoder(JavaPlugin plugin) {
        this.plugin = plugin;
        this.worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable, "EtherTV-FrameEncoder");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> { throw new RejectedExecutionException("Frame queue is full"); }
        );
    }

    public void submit(BufferedImage frame, Path output) {
        if (frame == null || output == null) return;
        if (worker.isShutdown()) { frame.flush(); return; }
        try {
            worker.execute(() -> {
                try {
                    Path parent = output.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Path temporary = output.resolveSibling(output.getFileName().toString() + ".part");
                    if (!ImageIO.write(frame, "png", temporary.toFile())) {
                        throw new IOException("PNG writer is unavailable");
                    }
                    try {
                        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    plugin.getLogger().warning("Не удалось сохранить кадр EtherTV: " + exception.getMessage());
                } finally {
                    frame.flush();
                }
            });
        } catch (RejectedExecutionException exception) {
            frame.flush();
            plugin.getLogger().warning("Очередь рендерера переполнена: кадр пропущен.");
        }
    }


    /** Writes an already encoded JPEG frame without decoding it on the server thread. */
    public void submitEncodedJpeg(byte[] encoded, Path output) {
        if (encoded == null || output == null || encoded.length < 3 || encoded.length > 400_000) return;
        byte[] copy = encoded.clone();
        if ((copy[0] & 0xff) != 0xff || (copy[1] & 0xff) != 0xd8 || (copy[2] & 0xff) != 0xff) return;
        if (worker.isShutdown()) return;
        try {
            worker.execute(() -> {
                try {
                    Path parent = output.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Path temporary = output.resolveSibling(output.getFileName().toString() + ".part");
                    Files.write(temporary, copy);
                    try {
                        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    plugin.getLogger().warning("Не удалось сохранить клиентский кадр EtherTV: " + exception.getMessage());
                }
            });
        } catch (RejectedExecutionException exception) {
            plugin.getLogger().warning("Очередь клиентских кадров переполнена: кадр пропущен.");
        }
    }

    @Override
    public void close() {
        worker.shutdown(); // кадры, уже стоящие в очереди, будут сохранены
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                plugin.getLogger().warning("Ожидание сохранения кадров EtherTV превысило 3 секунды; часть последних кадров могла не сохраниться.");
            }
        } catch (InterruptedException exception) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
