package com.otectus.immersivebrewing.smoketest;

import com.mojang.blaze3d.platform.NativeImage;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Uncut footage of the real-input journeys (handoff 22.7): every rendered frame due at 20 frames a second is read back
 * from the main render target, exactly what the window showed (world, camera, HUD and labels), and piped as raw video
 * into ffmpeg, which writes an H.264 file. Frames are paced by wall-clock time, so the file plays in real time: a slow
 * frame is repeated rather than skipped, and a frame the encoder could not take is counted as dropped. Never shipped.
 */
final class Footage {
    private static final long FRAME_MS = 50;
    private static final Object END = new Object();

    private record Frame(NativeImage image, int repeat) {}

    private static String name;
    private static Process ffmpeg;
    private static Thread writer;
    private static ArrayBlockingQueue<Object> queue;
    private static long startMs;
    private static long due;
    private static int width;
    private static int height;
    private static int dropped;
    private static int captured;
    private static Path file;

    static boolean available() {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, "ffmpeg"))) return true;
        }
        return false;
    }

    static boolean recording() {
        return name != null;
    }

    /** Starts a recording into {@code dir/<name>.mp4}; false (nothing recorded) when ffmpeg is missing or fails to start. */
    static boolean start(Path dir, String recording) {
        if (name != null || !available()) return false;
        var target = Minecraft.getInstance().getMainRenderTarget();
        width = target.width;
        height = target.height;
        try {
            Files.createDirectories(dir);
            file = dir.resolve(recording + ".mp4");
            ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", width + "x" + height,
                    "-framerate", String.valueOf(1000 / FRAME_MS), "-i", "-", "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.toString())
                    .redirectErrorStream(true).redirectOutput(dir.resolve(recording + ".ffmpeg.log").toFile()).start();
        } catch (IOException e) {
            ImmersiveBrewing.LOGGER.error("SMOKETEST footage could not start", e);
            return false;
        }
        queue = new ArrayBlockingQueue<>(16);
        OutputStream pipe = new BufferedOutputStream(ffmpeg.getOutputStream(), 1 << 20);
        ArrayBlockingQueue<Object> frames = queue;
        int w = width, h = height;
        writer = new Thread(() -> encode(frames, pipe, w, h), "smoketest-footage");
        writer.setDaemon(true);
        writer.start();
        name = recording;
        startMs = Util.getMillis();
        due = 0;
        dropped = 0;
        captured = 0;
        return true;
    }

    private static void encode(ArrayBlockingQueue<Object> frames, OutputStream pipe, int w, int h) {
        byte[] row = new byte[w * 3];
        try (pipe) {
            while (true) {
                Object next = frames.take();
                if (next == END) break;
                Frame f = (Frame) next;
                try (NativeImage image = f.image()) {
                    byte[] data = new byte[w * h * 3];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            // Little-endian RGBA in memory: red is the low byte.
                            int p = image.getPixelRGBA(x, y);
                            int i = x * 3;
                            row[i] = (byte) p;
                            row[i + 1] = (byte) (p >> 8);
                            row[i + 2] = (byte) (p >> 16);
                        }
                        System.arraycopy(row, 0, data, y * w * 3, row.length);
                    }
                    for (int r = 0; r < f.repeat(); r++) pipe.write(data);
                }
            }
        } catch (IOException | InterruptedException e) {
            ImmersiveBrewing.LOGGER.error("SMOKETEST footage encoder stopped", e);
        }
    }

    /** After each rendered frame: reads it back if one is due. */
    static void frame() {
        if (name == null) return;
        long elapsed = Util.getMillis() - startMs;
        long wanted = elapsed / FRAME_MS + 1;
        if (wanted <= due) return;
        int repeat = (int) Math.min(40, wanted - due);
        due = wanted;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != width || target.height != height) {
            dropped += repeat;
            return;
        }
        NativeImage image = Screenshot.takeScreenshot(target);
        if (!queue.offer(new Frame(image, repeat))) {
            image.close();
            dropped += repeat;
            return;
        }
        captured += repeat;
    }

    /** Ends the recording; the result (file, frames, seconds, dropped, exit code) completes once ffmpeg has written it. */
    static CompletableFuture<Map<String, Object>> stop() {
        if (name == null) return CompletableFuture.completedFuture(Map.of("recorded", false));
        String finished = name;
        name = null;
        long seconds = (Util.getMillis() - startMs) / 1000;
        int frames = captured, lost = dropped;
        Path out = file;
        Process process = ffmpeg;
        Thread thread = writer;
        ArrayBlockingQueue<Object> frames2 = queue;
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                frames2.put(END);
                thread.join(120_000);
                boolean exited = process.waitFor(120, TimeUnit.SECONDS);
                result.put("recorded", exited && process.exitValue() == 0 && Files.size(out) > 0);
                result.put("exit", exited ? process.exitValue() : -1);
                result.put("bytes", Files.size(out));
            } catch (IOException | InterruptedException e) {
                result.put("recorded", false);
                result.put("error", e.toString());
            }
            result.put("file", out.toString());
            result.put("name", finished);
            result.put("frames", frames);
            result.put("dropped_frames", lost);
            result.put("seconds", seconds);
            result.put("size", width + "x" + height);
            return result;
        });
    }

    private Footage() {}
}
