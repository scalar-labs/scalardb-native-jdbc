package com.scalar.db.nativejdbc.bench;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A TCP proxy that delays every chunk by half a round-trip time in each direction, as if the
 * database were remote; bandwidth is not limited and chunks keep their order.
 */
@SuppressFBWarnings(
    value = {"UNENCRYPTED_SOCKET", "UNENCRYPTED_SERVER_SOCKET"},
    justification = "a local proxy to the database container, which the drivers reach in clear")
final class DelayProxy implements AutoCloseable {
  private final ServerSocket server;
  private final long oneWayNanos;

  /** Chunks sent by the client, roughly its round trips. */
  final java.util.concurrent.atomic.AtomicLong requests =
      new java.util.concurrent.atomic.AtomicLong();

  DelayProxy(String host, int port, double roundTripMs) throws IOException {
    this.server = new ServerSocket();
    server.bind(new InetSocketAddress("127.0.0.1", 0));
    this.oneWayNanos = (long) (roundTripMs * 1_000_000 / 2);
    Thread accept =
        new Thread(
            () -> {
              while (!server.isClosed()) {
                try {
                  Socket client = server.accept();
                  Socket db = new Socket(host, port);
                  client.setTcpNoDelay(true);
                  db.setTcpNoDelay(true);
                  pipe(client.getInputStream(), db.getOutputStream(), true);
                  pipe(db.getInputStream(), client.getOutputStream(), false);
                } catch (IOException e) {
                  // closed
                }
              }
            });
    accept.setDaemon(true);
    accept.start();
  }

  int port() {
    return server.getLocalPort();
  }

  private void pipe(InputStream in, OutputStream out, boolean fromClient) {
    BlockingQueue<Object[]> queue = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              byte[] buf = new byte[65536];
              try {
                for (int n; (n = in.read(buf)) > 0; ) {
                  if (fromClient) {
                    requests.incrementAndGet();
                  }
                  queue.put(new Object[] {System.nanoTime() + oneWayNanos, Arrays.copyOf(buf, n)});
                }
              } catch (IOException | InterruptedException e) {
                // closed
              }
              queue.add(new Object[] {0L, new byte[0]});
            });
    Thread writer =
        new Thread(
            () -> {
              try {
                while (true) {
                  // spin rather than block: waking a parked thread takes up to milliseconds
                  Object[] chunk = queue.poll();
                  if (chunk == null) {
                    Thread.onSpinWait();
                    continue;
                  }
                  byte[] bytes = (byte[]) chunk[1];
                  if (bytes.length == 0) {
                    out.close();
                    return;
                  }
                  long wait = (long) chunk[0] - System.nanoTime();
                  while (wait > 0) {
                    Thread.onSpinWait();
                    wait = (long) chunk[0] - System.nanoTime();
                  }
                  out.write(bytes);
                  out.flush();
                }
              } catch (IOException e) {
                // closed
              }
            });
    reader.setDaemon(true);
    writer.setDaemon(true);
    reader.start();
    writer.start();
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
