package io.github.ekotov.davmail;

import davmail.AbstractConnection;
import davmail.caldav.CaldavServer;

import java.io.IOException;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/** Close keep-alive clients on reload so no connection retains the old policy snapshot. */
final class ManagedCaldavServer extends CaldavServer {
    private final Set<Socket> clients = new HashSet<>();
    private boolean closing;

    ManagedCaldavServer(int port) { super(port); }

    @Override
    public synchronized AbstractConnection createConnectionHandler(Socket socket) {
        AbstractConnection connection = super.createConnectionHandler(socket);
        clients.removeIf(Socket::isClosed);
        if (closing) {
            closeSocket(socket);
        } else {
            clients.add(socket);
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        closing = true;
        super.close();
        for (Socket socket : clients) { closeSocket(socket); }
        clients.clear();
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { /* Already disconnected. */ }
    }
}
