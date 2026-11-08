package com.example.helditemfixture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Serializes runs and persists the need for a read-only recovery after an interrupted write. */
final class RunGate implements Closeable {
    private final FileChannel channel;
    private final FileLock lock;

    RunGate(FixtureRunner.Settings settings) throws IOException {
        Path directory = Path.of(System.getProperty("user.home"), ".helditemuidfixture");
        Files.createDirectories(directory);
        String host = settings.host().getAddress().length == 4 ? "loopback4" : "loopback6";
        String name = host + "-" + settings.port() + "-"
                + settings.player().toLowerCase(Locale.ROOT) + ".state";
        channel = FileChannel.open(directory.resolve(name),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("Another run for this endpoint and player is active.");
            }
        } catch (OverlappingFileLockException error) {
            channel.close();
            throw new IOException("Another run for this endpoint and player is active.", error);
        } catch (IOException error) {
            channel.close();
            throw error;
        }
    }

    List<Integer> pendingSlots() throws IOException {
        long size = channel.size();
        if (size > FixtureRunner.MAIN_SLOTS) {
            throw new IOException("The pending slot journal is too large.");
        }
        ByteBuffer bytes = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) < 0) {
                throw new IOException("The pending slot journal ended early.");
            }
        }
        List<Integer> slots = new ArrayList<>();
        for (byte entry : bytes.array()) {
            int slot = entry - 1;
            if (slot < 0 || slot >= FixtureRunner.MAIN_SLOTS || slots.contains(slot)) {
                throw new IOException("The pending slot journal contains an invalid entry.");
            }
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    void markPending(int slot) throws IOException {
        // Version 1.0.1 used byte 1 for its only destination, inventory.0.
        if (channel.write(ByteBuffer.wrap(new byte[] {(byte) (slot + 1)}), channel.size()) != 1) {
            throw new IOException("Could not record the pending write.");
        }
        channel.force(true);
    }

    void confirmReadback() throws IOException {
        channel.truncate(0);
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
