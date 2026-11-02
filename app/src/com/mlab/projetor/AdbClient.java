package com.mlab.projetor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Cliente minimo do protocolo ADB por TCP (o mesmo do "adb connect IP:5555"), sem bibliotecas.
 * Um fluxo por vez: abre um servico (shell:, exec:), le a saida ate o aparelho fechar.
 */
final class AdbClient implements Closeable {
    static final int PORT = 5555;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;
    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** Avisa a tela quando o projetor esta mostrando "Permitir depuracao?". */
    interface AuthListener {
        void waitingForUser();
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String host;
    private int maxPayload = 4096;
    private int nextLocalId = 1;
    String banner = "";

    private AdbClient(Socket s, String host) throws IOException {
        this.socket = s;
        this.host = host;
        in = new BufferedInputStream(s.getInputStream());
        out = new BufferedOutputStream(s.getOutputStream());
    }

    /** Retorna o IP do projetor conectado (usado para abrir o controle remoto no navegador). */
    String getHost() {
        return host;
    }

    /**
     * Conecta e autentica. Se o projetor pedir permissao, manda a chave publica e espera ate
     * {@code authWaitMs} a pessoa aceitar no controle.
     */
    static AdbClient connect(String host, AdbKey key, AuthListener listener, int authWaitMs)
            throws IOException, GeneralSecurityException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, PORT), 4000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(10_000);
        AdbClient c = new AdbClient(s, host);
        try {
            c.handshake(key, listener, authWaitMs);
            return c;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private void handshake(AdbKey key, AuthListener listener, int authWaitMs) throws IOException, GeneralSecurityException {
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, sentKey = false;
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (SocketTimeoutException e) {
                throw new IOException(sentKey ? "A permissão não foi aceita no projetor." : "O projetor não respondeu.");
            }
            if (m.cmd == A_CNXN) {
                maxPayload = Math.max(4096, Math.min(m.arg1, MAX_PAYLOAD));
                banner = new String(m.data, StandardCharsets.UTF_8);
                socket.setSoTimeout(30_000);
                return;
            }
            if (m.cmd == A_STLS) throw new IOException("Este aparelho usa a depuração por Wi‑Fi com pareamento, que o instalador ainda não suporta.");
            if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("Resposta inesperada do projetor.");
            if (!signed) {
                signed = true;
                send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
            } else if (!sentKey) {
                sentKey = true;
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload("projetor@projetor"));
                if (listener != null) listener.waitingForUser();
                socket.setSoTimeout(authWaitMs);
            } else {
                throw new IOException("A permissão foi recusada no projetor.");
            }
        }
    }

    /** Roda um comando no projetor e devolve a saida (sem o \n final). */
    String shell(String cmd) throws IOException {
        return exec("shell:" + cmd, null).trim();
    }

    String host() {
        return socket.getInetAddress().getHostAddress();
    }

    /**
     * Abre um servico, manda {@code input} (se houver) e le tudo ate o projetor fechar.
     * Usado pelo "exec:cmd package install -S", que le o APK pela entrada padrao.
     */
    String exec(String service, byte[] input) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, (service + "\u0000").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remote;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
                break;
            }
            if (m.cmd == A_CLSE) throw new IOException("O projetor recusou o comando.");
        }
        boolean closed = false;
        if (input != null) {
            for (int off = 0; off < input.length && !closed; ) {
                int n = Math.min(maxPayload, input.length - off);
                byte[] chunk = new byte[n];
                System.arraycopy(input, off, chunk, 0, n);
                send(A_WRTE, local, remote, chunk);
                off += n;
                // Espera o OKAY do bloco; a saida que chegar no meio vai para o buffer.
                while (true) {
                    Msg m = read();
                    if (m.arg1 != local) continue;
                    if (m.cmd == A_OKAY) break;
                    if (m.cmd == A_WRTE) {
                        output.write(m.data);
                        send(A_OKAY, local, remote, new byte[0]);
                    } else if (m.cmd == A_CLSE) {
                        closed = true;
                        break;
                    }
                }
            }
        }
        while (!closed) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == A_WRTE) {
                output.write(m.data);
                send(A_OKAY, local, remote, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                closed = true;
            }
        }
        send(A_CLSE, local, remote, new byte[0]);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- Mensagens: cabecalho de 24 bytes little-endian + dados ----

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt();
        int magic = h.getInt();
        if (magic != ~m.cmd || len < 0 || len > 1024 * 1024) throw new IOException("Conexão com o projetor corrompida.");
        m.data = new byte[len];
        readFully(m.data);
        return m;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("O projetor fechou a conexão.");
            off += n;
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
