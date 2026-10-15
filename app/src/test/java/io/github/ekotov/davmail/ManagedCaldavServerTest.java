package io.github.ekotov.davmail;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import static org.junit.Assert.*;

public class ManagedCaldavServerTest {
    @Test public void reloadClosesKeepAliveAndLateAcceptedClients() {
        ManagedCaldavServer server = new ManagedCaldavServer(11080);
        Socket client = new MemorySocket();
        server.createConnectionHandler(client);
        assertFalse(client.isClosed());
        server.close();
        assertTrue(client.isClosed());
        Socket lateClient = new MemorySocket();
        server.createConnectionHandler(lateClient);
        assertTrue(lateClient.isClosed());
        server.close();
    }
    private static class MemorySocket extends Socket {
        @Override public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    }
}
