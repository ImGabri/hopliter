package com.example.helditemfixture;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class RconClient implements Closeable {
    private static final int AUTH = 3;
    private static final int COMMAND = 2;
    private static final int RESPONSE = 0;
    private static final int CHUNK_SIZE = 4096;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private static final int TIMEOUT_MILLIS = 5000;

    private final Socket socket;
    private final DataInputStream input;
    private int nextId = 1;

    RconClient(InetAddress host, int port, char[] password) throws IOException {
        if (!host.isLoopbackAddress()) {
            throw new IllegalArgumentException("Use a local RCON endpoint or an SSH tunnel.");
        }
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            input = new DataInputStream(socket.getInputStream());
            int authId = nextId++;
            send(authId, AUTH, new String(password));
            Frame auth = receive();
            if (auth.id() == authId && auth.type() == RESPONSE && auth.body().length == 0) {
                auth = receive();
            }
            if (auth.id() != authId || auth.type() != COMMAND) {
                throw new IOException("RCON authentication failed.");
            }
        } catch (IOException | RuntimeException error) {
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    String command(String command) throws IOException {
        int id = nextId++;
        send(id, COMMAND, command);
        Frame first = receive();
        requireResponse(first, id);
        if (first.body().length < CHUNK_SIZE) {
            return decode(first.body());
        }

        // Vanilla may split responses. An empty type-0 probe gets its own reply ID.
        // Send it after the first reply so the two requests cannot coalesce.
        int endId = nextId++;
        send(endId, RESPONSE, "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(first.body());
        while (true) {
            Frame frame = receive();
            if (frame.id() == endId && frame.type() == RESPONSE) {
                return decode(body.toByteArray());
            }
            requireResponse(frame, id);
            if (body.size() + frame.body().length > MAX_RESPONSE) {
                throw new IOException("RCON response exceeded the readback size limit.");
            }
            body.writeBytes(frame.body());
        }
    }

    private void send(int id, int type, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1446 || message.indexOf('\0') >= 0) {
            throw new IOException("RCON request exceeds protocol limits.");
        }
        ByteBuffer packet = ByteBuffer.allocate(bytes.length + 14).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(bytes.length + 10).putInt(id).putInt(type).put(bytes).putShort((short) 0);
        socket.getOutputStream().write(packet.array());
    }

    private Frame receive() throws IOException {
        int length = Integer.reverseBytes(input.readInt());
        if (length < 10 || length > 65_536) {
            throw new IOException("Invalid RCON response length.");
        }
        int id = Integer.reverseBytes(input.readInt());
        int type = Integer.reverseBytes(input.readInt());
        byte[] body = input.readNBytes(length - 10);
        if (body.length != length - 10) {
            throw new EOFException("RCON response ended early.");
        }
        if (input.readUnsignedShort() != 0) {
            throw new IOException("Invalid RCON response terminator.");
        }
        return new Frame(id, type, body);
    }

    private static void requireResponse(Frame frame, int id) throws IOException {
        if (frame.id() != id || frame.type() != RESPONSE) {
            throw new IOException("Unexpected RCON response ID or type.");
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private record Frame(int id, int type, byte[] body) {}
}
