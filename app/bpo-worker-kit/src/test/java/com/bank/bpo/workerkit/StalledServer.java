package com.bank.bpo.workerkit;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback HTTP endpoint that accepts the connection and then never finishes the answer, the way a Keycloak
 * or provider pod behaves while it starts, restarts or loses its peer half-way. {@link Mode#SILENT} never
 * answers; {@link Mode#HEADERS_ONLY} sends 200 and a content length, then stops in the middle of the body.
 */
final class StalledServer implements AutoCloseable {

    enum Mode { SILENT, HEADERS_ONLY }

    private final ServerSocket socket;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    private StalledServer(Mode mode) throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    open.add(client);
                    Thread.ofVirtual().start(() -> hold(client, mode));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    static StalledServer start(Mode mode) {
        try {
            return new StalledServer(mode);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + socket.getLocalPort() + path);
    }

    private static void hold(Socket client, Mode mode) {
        try {
            InputStream in = client.getInputStream();
            in.read(new byte[8192]);
            if (mode == Mode.HEADERS_ONLY) {
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\n"
                    + "content-length: 200\r\n\r\n{\"access_token\":").getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            }
            while (in.read(new byte[8192]) >= 0) {
                // keep the connection open until the client gives up or the server closes
            }
        } catch (IOException ignored) {
            // the client cancelled, which is what the tests expect
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
            for (Socket client : open) {
                client.close();
            }
        } catch (IOException ignored) {
            // nothing left to release
        }
        acceptor.interrupt();
    }
}
