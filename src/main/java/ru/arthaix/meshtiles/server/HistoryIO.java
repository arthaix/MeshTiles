package ru.arthaix.meshtiles.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import ru.arthaix.meshtiles.MeshTiles;

/**
 * Disk IO for undo and redo data on a background thread. The server thread only hands over objects it no longer
 * changes; building, compressing and writing megabytes of data never happens during a tick or a world autosave.
 * Tasks run strictly in the order they were queued, so a read always sees an earlier write.
 */
public final class HistoryIO {

    public interface Saver<T> {
        void save(T data, File file) throws IOException;
    }

    public interface Loader<T> {
        T load(File file) throws IOException;
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meshtiles-history-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Data queued for writing, by file. A read of such a file gets the object itself, without waiting for the disk. */
    private static final ConcurrentHashMap<File, Object> PENDING = new ConcurrentHashMap<>();

    private HistoryIO() {}

    /** Queues {@code data} to be saved into {@code file} (atomically: temp file, then rename). */
    public static <T> void write(File file, T data, Saver<T> saver) {
        PENDING.put(file, data);
        IO.execute(() -> {
            try {
                File dir = file.getParentFile();
                if (dir != null) dir.mkdirs();
                File tmp = new File(file.getPath() + ".tmp");
                saver.save(data, tmp);
                if (file.exists() && !file.delete()) throw new IOException("cannot replace " + file);
                if (!tmp.renameTo(file)) throw new IOException("cannot rename " + tmp + " to " + file);
            } catch (Exception e) {
                MeshTiles.logger.error("Could not write " + file + ": " + e);
            } finally {
                PENDING.remove(file, data);
            }
        });
    }

    /** Queues deletion of a file (after any write of it that is still queued). */
    public static void delete(File file) {
        PENDING.remove(file);
        IO.execute(() -> {
            if (file.exists() && !file.delete()) MeshTiles.logger.warn("Could not delete " + file);
        });
    }

    /**
     * Loads a file off the server thread and hands the result to the server thread. Must be called on the server
     * thread; when the data is still queued for writing the callback runs immediately.
     */
    @SuppressWarnings("unchecked")
    public static <T> void read(File file, Loader<T> loader, Consumer<T> onServer, Consumer<String> onError) {
        Object pending = PENDING.get(file);
        if (pending != null) {
            onServer.accept((T) pending);
            return;
        }
        IO.execute(() -> {
            T data = null;
            String error = null;
            try {
                if (!file.exists()) throw new IOException("file not found");
                data = loader.load(file);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final T result = data;
            final String failure = error;
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server == null) return;
            server.addScheduledTask(() -> {
                if (failure != null) onError.accept(failure);
                else onServer.accept(result);
            });
        });
    }

    /** Waits until everything queued so far is on disk (used when the server stops). */
    public static void flush(long timeoutMillis) {
        try {
            IO.submit(() -> {}).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            MeshTiles.logger.warn("MeshTiles history IO did not finish in time: " + e);
        }
    }

    public static void writeNbt(NBTTagCompound nbt, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(nbt, out);
        }
    }

    public static NBTTagCompound readNbt(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return CompressedStreamTools.readCompressed(in);
        }
    }
}
