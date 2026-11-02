package com.mlab.projetor;

import android.os.Looper;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Ajudante da versao compativel: iniciado pelo ADB local com app_process, roda como usuario shell,
 * que pode injetar teclas e toques. So escuta em 127.0.0.1, exige o token sorteado pelo app e so
 * entende comandos de entrada, nunca executa comandos do sistema. Morre ao reiniciar o aparelho
 * e quando o APK e trocado; o app sobe outro.
 */
public final class ShellInjetor {
    static final int PORT = 57321;
    static final int PROTOCOLO = 1;

    private ShellInjetor() {}

    public static void main(String[] args) throws Exception {
        final String token = args[0];
        final File apk = new File(args[1]);
        Looper.prepare();
        Thread guard = new Thread(() -> {
            while (apk.exists()) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            System.exit(0);
        });
        guard.setDaemon(true);
        guard.start();
        try (ServerSocket server = new ServerSocket(PORT, 2, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                try (Socket s = server.accept()) {
                    serve(s, token);
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
    }

    private static void serve(Socket s, String token) throws IOException {
        s.setSoTimeout(3000);
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = s.getOutputStream();
        String first = in.readLine();
        if (first == null || !MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) return;
        s.setSoTimeout(0);
        out.write(("ok " + PROTOCOLO + "\n").getBytes(StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String[] p = line.split(" ");
            switch (p[0]) {
                case "k":
                    Injecao.key(Integer.parseInt(p[1]));
                    break;
                case "t":
                    Injecao.tap(Float.parseFloat(p[1]), Float.parseFloat(p[2]), "1".equals(p[3]));
                    break;
                case "w":
                    Injecao.swipe(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]));
                    break;
                case "x":
                    Injecao.text(new String(Base64.decode(p[1], Base64.NO_WRAP), StandardCharsets.UTF_8));
                    break;
                case "q":
                    System.exit(0);
                    break;
                default:
            }
        }
    }
}
