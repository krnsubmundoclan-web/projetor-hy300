package com.mlab.projetor;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor HTTP + WebSocket minimo (sem dependencias) na rede local.
 * Tudo em /api e /ws exige o PIN mostrado na tela inicial do projetor.
 */
final class WebServer {
    private static final String TAG = "Projetor";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final long MIN_FREE_BYTES = 300L * 1024 * 1024;
    private static final long MAX_FOTO_BYTES = 25L * 1024 * 1024;
    private static final int MAX_WORKERS = 8;
    private static final int MAX_PENDING = 16;
    private static final int MAX_WEBSOCKETS = 4;

    private final Context ctx;
    private final Controller ctl;
    private final Voice voice;
    private final int port;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(MAX_WORKERS, MAX_WORKERS, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING));
    private final AtomicInteger webSockets = new AtomicInteger();
    private volatile boolean running;
    private ServerSocket server;

    /** Por IP: PINs errados distintos tentados, ate quando esta bloqueado e quantas vezes ja foi. */
    private final Map<String, java.util.Set<String>> wrongPins = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private final Map<String, Integer> lockCount = new HashMap<>();
    /** Todos os erros da ultima hora, de qualquer IP (barra quem troca de IP para continuar tentando). */
    private final java.util.ArrayDeque<Long> recentWrong = new java.util.ArrayDeque<>();
    private long globalLockUntil;
    private static final int GLOBAL_MAX_WRONG = 60;

    WebServer(Context ctx, Controller ctl, int port) {
        this.ctx = ctx;
        this.ctl = ctl;
        this.port = port;
        this.voice = new Voice(ctx, ctl);
        ctl.setScreenListener(this::broadcast);
    }

    void start() {
        running = true;
        new Thread(this::loop, "web").start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void loop() {
        while (running) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(port));
                while (running) {
                    final Socket s = server.accept();
                    if (!Net.isLocal(s.getInetAddress())) {
                        // So atende a rede de casa, mesmo que alguem abra a porta no roteador por engano.
                        s.close();
                        continue;
                    }
                    try {
                        pool.execute(() -> handle(s));
                    } catch (RejectedExecutionException e) {
                        s.close();
                    }
                }
            } catch (IOException e) {
                if (!running) return;
                Log.w(TAG, "servidor caiu, reiniciando", e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static final class Req {
        String method, path;
        final Map<String, String> q = new HashMap<>();
        final Map<String, String> h = new HashMap<>();
        InputStream in;
        long length;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            Req r = new Req();
            r.method = parts[0];
            String target = parts[1];
            int qi = target.indexOf('?');
            r.path = qi < 0 ? target : target.substring(0, qi);
            if (qi >= 0) parseQuery(target.substring(qi + 1), r.q);
            String hl;
            while ((hl = readLine(in)) != null && !hl.isEmpty()) {
                int c = hl.indexOf(':');
                if (c > 0) r.h.put(hl.substring(0, c).trim().toLowerCase(Locale.ROOT), hl.substring(c + 1).trim());
            }
            r.in = in;
            try {
                r.length = Long.parseLong(r.h.containsKey("content-length") ? r.h.get("content-length") : "0");
            } catch (NumberFormatException e) {
                r.length = 0;
            }
            // Corpo de formulario pequeno (POST dos Atalhos) vira parametros, como na query.
            if ("POST".equals(r.method) && !"/api/upload".equals(r.path) && !"/api/fundo".equals(r.path) && r.length > 0 && r.length < 65536) {
                byte[] body = new byte[(int) r.length];
                readFully(in, body);
                String ct = r.h.containsKey("content-type") ? r.h.get("content-type") : "";
                if (ct.contains("json")) parseJson(new String(body, StandardCharsets.UTF_8), r.q);
                else parseQuery(new String(body, StandardCharsets.UTF_8), r.q);
            }
            route(r, s, out);
            out.flush();
        } catch (Exception e) {
            Log.d(TAG, "conexao encerrada: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(Req r, Socket s, OutputStream out) throws IOException {
        if ("OPTIONS".equals(r.method)) {
            preflight(r, out);
            return;
        }
        String p = r.path;
        if (p.equals("/") || p.equals("/index.html")) {
            asset(out, "web/index.html", "text/html; charset=utf-8");
            return;
        }
        if (p.equals("/icon.png") || p.equals("/apple-touch-icon.png") || p.equals("/favicon.ico")) {
            asset(out, "web/icon.png", "image/png");
            return;
        }
        if (p.equals("/manifest.json")) {
            asset(out, "web/manifest.json", "application/manifest+json");
            return;
        }
        if (p.equals("/font.ttf")) {
            asset(out, "fonts/Inter.ttf", "font/ttf");
            return;
        }
        if (!p.startsWith("/api/") && !p.equals("/ws")) {
            send(out, 404, "text/plain; charset=utf-8", "nao encontrado".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (p.equals("/api/ola")) {
            // Aparelho ja salvo que perdeu o token (app instalado na tela de inicio): entra pelo IP.
            String tk = Aparelhos.porIp(ctx, s.getInetAddress().getHostAddress());
            json(out, 200, "{\"ok\":true,\"token\":" + (tk == null ? "null" : Json.str(tk)) + "}");
            return;
        }
        String pin = r.q.get("t");
        if (pin == null) pin = r.h.get("x-pin");
        boolean auth = checkPin(s.getInetAddress().getHostAddress(), pin);
        if (p.equals("/api/ping")) {
            json(out, 200, "{\"ok\":true,\"auth\":" + auth + "}");
            return;
        }
        if (!auth) {
            json(out, 401, "{\"ok\":false,\"erro\":\"PIN incorreto\"}");
            return;
        }
        switch (p) {
            case "/ws":
                websocket(r, s, out);
                return;
            case "/api/key":
                ok(out, ctl.key(r.q.get("k")));
                return;
            case "/api/listas":
                if ("POST".equals(r.method)) {
                    if (r.q.containsKey("remover")) {
                        Iptv.remover(ctx, r.q.get("remover"));
                    } else {
                        String erroLista = Iptv.adicionar(ctx, r.q.get("add"));
                        if (erroLista != null) {
                            json(out, 200, "{\"ok\":false,\"erro\":" + Json.str(erroLista) + "}");
                            return;
                        }
                    }
                }
                StringBuilder ls = new StringBuilder();
                for (String u : Iptv.listas(ctx)) ls.append(ls.length() > 0 ? "," : "").append(Json.str(u));
                json(out, 200, "{\"ok\":true,\"listas\":[" + ls + "]}");
                return;
            case "/api/transmitir":
                transmitir(r, out);
                return;
            case "/api/personalizar":
                personalizar(r, out);
                return;
            case "/api/fav":
                if (!"POST".equals(r.method) || r.q.get("pkg") == null) {
                    json(out, 400, "{\"ok\":false}");
                    return;
                }
                String favPkg = r.q.get("pkg");
                if (!Prefs.favs(ctx).contains(favPkg) && Apps.favorites(ctx).size() >= AppsActivity.MAX_INICIO) {
                    json(out, 200, "{\"ok\":false,\"cheio\":true}");
                    return;
                }
                boolean virouFav = Prefs.toggleFav(ctx, favPkg);
                HomeActivity.refreshTheme();
                json(out, 200, "{\"ok\":true,\"fav\":" + virouFav + "}");
                return;
            case "/api/aparelho/salvar":
                if (!"POST".equals(r.method)) {
                    json(out, 405, "{\"ok\":false,\"erro\":\"use POST\"}");
                    return;
                }
                json(out, 200, "{\"ok\":true,\"token\":" + Json.str(Aparelhos.emitir(ctx,
                        s.getInetAddress().getHostAddress(), r.h.get("user-agent"))) + "}");
                return;
            case "/api/aparelho/esquecer":
                if (!"POST".equals(r.method)) {
                    json(out, 405, "{\"ok\":false,\"erro\":\"use POST\"}");
                    return;
                }
                for (Aparelhos.Item it : Aparelhos.todos(ctx)) {
                    if (it.ip.equals(s.getInetAddress().getHostAddress())) Aparelhos.remover(ctx, it.id);
                }
                ok(out, true);
                return;
            case "/api/text":
                String txt = r.q.get("s");
                if (txt != null) ctl.text(txt);
                if ("1".equals(r.q.get("enter"))) ctl.key("enter");
                ok(out, txt != null);
                return;
            case "/api/open":
                ok(out, ctl.openApp(r.q.get("app")));
                return;
            case "/api/url":
                ok(out, ctl.openUrl(r.q.get("u")));
                return;
            case "/api/volume":
                if (r.q.containsKey("set")) {
                    try {
                        ctl.setVolume(Integer.parseInt(r.q.get("set").trim()));
                    } catch (NumberFormatException e) {
                        ok(out, false);
                        return;
                    }
                } else {
                    ctl.volume(r.q.containsKey("dir") ? r.q.get("dir") : "up");
                }
                ok(out, true);
                return;
            case "/api/power":
                ok(out, ctl.key("power"));
                return;
            case "/api/click":
                ctl.click("1".equals(r.q.get("long")));
                ok(out, true);
                return;
            case "/api/state":
                json(out, 200, ctl.stateJson());
                return;
            case "/api/apps":
                json(out, 200, ctl.appsJson());
                return;
            case "/api/upload":
                upload(r, out);
                return;
            case "/api/fundo":
                fundo(r, out);
                return;
            case "/api/voz":
                Voice.Result vr = voice.run(r.q.containsKey("s") ? r.q.get("s") : r.q.get("texto"));
                json(out, 200, "{\"ok\":" + vr.ok + ",\"fala\":" + Json.str(vr.speech) + "}");
                return;
            case "/api/pin/novo":
                if (!"POST".equals(r.method)) {
                    json(out, 405, "{\"ok\":false,\"erro\":\"use POST\"}");
                    return;
                }
                json(out, 200, "{\"ok\":true,\"pin\":" + Json.str(regeneratePin()) + "}");
                return;
            case "/api/desinstalar":
                if (!"POST".equals(r.method)) {
                    json(out, 405, "{\"ok\":false,\"erro\":\"use POST\"}");
                    return;
                }
                ok(out, true);
                out.flush();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    ctl.hud.message("Voltando à tela original e removendo o Projetor…");
                    Sistema.restoreAndUninstall(ctx);
                }, 500);
                return;
            case "/api/factory-reset":
                if (!"POST".equals(r.method)) {
                    json(out, 405, "{\"ok\":false,\"erro\":\"use POST\"}");
                    return;
                }
                ok(out, true);
                out.flush();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    ctl.hud.message("Restaurando configurações de fábrica…");
                    // Limpa os dados do app; o Android encerra o processo.
                    ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).clearApplicationUserData();
                }, 500);
                return;
            case "/api/adb":
                if ("POST".equals(r.method) && r.q.containsKey("on")) {
                    try {
                        android.provider.Settings.Global.putInt(ctx.getContentResolver(),
                                android.provider.Settings.Global.ADB_ENABLED, "1".equals(r.q.get("on")) ? 1 : 0);
                    } catch (Exception e) {
                        Log.w(TAG, "nao foi possivel mudar a depuracao ADB", e);
                        ok(out, false);
                        return;
                    }
                }
                json(out, 200, "{\"ok\":true,\"adb\":" + adbEnabled() + "}");
                return;
            case "/api/icon":
                byte[] png = ctl.iconPng(r.q.get("pkg"));
                if (png == null) send(out, 404, "text/plain", new byte[0]);
                else send(out, 200, "image/png", png);
                return;
            default:
                json(out, 404, "{\"ok\":false,\"erro\":\"comando desconhecido\"}");
        }
    }

    private boolean adbEnabled() {
        return android.provider.Settings.Global.getInt(ctx.getContentResolver(),
                android.provider.Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** Sorteia um PIN novo; quem estava conectado com o antigo cai e precisa do novo. */
    String regeneratePin() {
        String novo = Prefs.newPin(ctx);
        resetAttempts();
        dropClients();
        ctl.hud.message("Novo PIN do controle: " + novo);
        return novo;
    }

    /** Escolhido na janela do controle; quem entrou com o PIN antigo precisa do novo. */
    void fixarPin(String novo) {
        Prefs.setPinFixo(ctx, novo);
        resetAttempts();
        dropClients();
    }

    /** IPs com o controle aberto agora. */
    Set<String> conectados() {
        return new java.util.HashSet<>(clients.values());
    }

    /** Derruba o controle de um aparelho removido da lista. */
    void desconectar(String ip) {
        for (Map.Entry<OutputStream, String> e : clients.entrySet()) {
            if (!e.getValue().equals(ip)) continue;
            try {
                synchronized (e.getKey()) {
                    frame(e.getKey(), 0x8, new byte[0]);
                }
            } catch (IOException ignored) {
            }
            clients.remove(e.getKey());
        }
    }

    private void dropClients() {
        for (OutputStream o : clients.keySet()) {
            try {
                synchronized (o) {
                    frame(o, 0x8, new byte[0]);
                }
            } catch (IOException ignored) {
            }
        }
        clients.clear();
    }

    private synchronized void resetAttempts() {
        wrongPins.clear();
        lockedUntil.clear();
        lockCount.clear();
        recentWrong.clear();
        globalLockUntil = 0;
    }

    /**
     * Bloqueio por aparelho (IP) depois de 10 PINs errados DIFERENTES: 1 minuto, e o dobro a cada
     * novo bloqueio (ate 1 hora). Uma pagina antiga reconectando com o mesmo PIN velho nao conta
     * varias vezes nem trava os outros aparelhos. Com 60 erros na ultima hora, somando todos os
     * IPs, o controle inteiro fica travado por 15 minutos e o projetor avisa na tela.
     */
    private synchronized boolean checkPin(String ip, String pin) {
        long now = System.currentTimeMillis();
        if (now < globalLockUntil) return false;
        Long until = lockedUntil.get(ip);
        if (until != null && now < until) return false;
        if (pin == null || pin.trim().isEmpty()) return false;
        pin = pin.trim();
        boolean ok = pin.length() == Aparelhos.TOKEN_LEN ? Aparelhos.valido(ctx, pin, ip)
                : MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8), Prefs.pin(ctx).getBytes(StandardCharsets.UTF_8));
        if (ok) {
            wrongPins.remove(ip);
            lockedUntil.remove(ip);
            lockCount.remove(ip);
            return true;
        }
        // Mapas limitados: um varredor com milhares de IPs nao enche a memoria.
        if (wrongPins.size() > 256) wrongPins.clear();
        java.util.Set<String> tried = wrongPins.get(ip);
        if (tried == null) wrongPins.put(ip, tried = new java.util.HashSet<>());
        if (!tried.add(pin)) return false;
        while (!recentWrong.isEmpty() && now - recentWrong.peekFirst() > 3_600_000L) recentWrong.pollFirst();
        recentWrong.addLast(now);
        if (recentWrong.size() >= GLOBAL_MAX_WRONG) {
            recentWrong.clear();
            globalLockUntil = now + 15 * 60_000L;
            ctl.hud.message("Muitas tentativas de PIN erradas. Controle travado por 15 minutos.");
        }
        if (tried.size() >= 10) {
            tried.clear();
            if (lockedUntil.size() > 256) lockedUntil.clear();
            if (lockCount.size() > 256) lockCount.clear();
            Integer n = lockCount.get(ip);
            n = n == null ? 0 : n;
            lockCount.put(ip, n + 1);
            lockedUntil.put(ip, now + Math.min(60L << Math.min(n, 6), 3600L) * 1000L);
        }
        return false;
    }

    // ---- Upload de foto/video: corpo bruto, nome na query ----

    private void upload(Req r, OutputStream out) throws IOException {
        if (!"POST".equals(r.method) || r.length <= 0) {
            json(out, 400, "{\"ok\":false,\"erro\":\"envie o arquivo no corpo\"}");
            return;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Projetor");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            json(out, 500, "{\"ok\":false,\"erro\":\"sem acesso ao armazenamento\"}");
            return;
        }
        if (dir.getUsableSpace() - r.length < MIN_FREE_BYTES) {
            json(out, 507, "{\"ok\":false,\"erro\":\"sem espaco no projetor\"}");
            return;
        }
        String name = r.q.containsKey("name") ? r.q.get("name") : "arquivo";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.startsWith(".")) name = "_" + name;
        File f = new File(dir, System.currentTimeMillis() + "_" + name);
        byte[] buf = new byte[64 * 1024];
        long left = r.length;
        try (FileOutputStream fo = new FileOutputStream(f)) {
            while (left > 0) {
                int n = r.in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException();
                fo.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw e;
        }
        boolean opened = ctl.openFile(f);
        json(out, 200, "{\"ok\":true,\"aberto\":" + opened + ",\"arquivo\":" + Json.str(f.getName()) + "}");
    }

    /** Lista os receptores de transmissao de tela deste projetor; POST com abrir=nome abre um. */
    private void transmitir(Req r, OutputStream out) throws IOException {
        Map<String, android.content.Intent> lista = Hardware.receptores(ctx);
        if ("POST".equals(r.method)) {
            android.content.Intent i = lista.get(r.q.get("abrir"));
            if (i != null) ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            ok(out, i != null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String nome : lista.keySet()) sb.append(sb.length() > 0 ? "," : "").append(Json.str(nome));
        json(out, 200, "{\"ok\":true,\"lista\":[" + sb + "]}");
    }

    private static final String[][] ANEIS = {{"temp", "Temperatura"}, {"cpu", "CPU"}, {"ram", "RAM"},
            {"espaco", "Espaço"}, {"internet", "Internet"}};

    /** O que a aba Personalizar do celular mostra e muda; a tela inicial se redesenha sozinha. */
    private void personalizar(Req r, OutputStream out) throws IOException {
        if ("POST".equals(r.method)) {
            try {
                if (r.q.containsKey("cor")) {
                    int c = Integer.parseInt(r.q.get("cor"));
                    if (c < 0 || c >= Fundo.CORES.length) throw new NumberFormatException();
                    Prefs.setFundoCor(ctx, c);
                    Fundo.remover(ctx);
                }
                if (r.q.containsKey("descanso")) {
                    int d = Integer.parseInt(r.q.get("descanso"));
                    int dc = r.q.containsKey("descansoCor") ? Integer.parseInt(r.q.get("descansoCor")) : Prefs.descansoCor(ctx);
                    if (d < 0 || d >= Fundo.DESCANSO.length || dc < 0 || dc >= Fundo.CORES.length) throw new NumberFormatException();
                    Prefs.setDescanso(ctx, d, dc);
                    Sistema.aplicarDescanso(ctx);
                }
                if (r.q.containsKey("escurecer")) {
                    int e = Integer.parseInt(r.q.get("escurecer"));
                    if (e < 0 || e >= Fundo.ESCURECER.length) throw new NumberFormatException();
                    Prefs.setFundoEscurecer(ctx, e);
                }
            } catch (NumberFormatException e) {
                ok(out, false);
                return;
            }
            if (r.q.containsKey("removerFoto")) Fundo.remover(ctx);
            String anel = r.q.get("anel");
            if (anel != null) {
                for (String[] a : ANEIS) if (a[0].equals(anel)) Prefs.setShowRing(ctx, anel, "1".equals(r.q.get("on")));
            }
            HomeActivity.refreshTheme();
            ok(out, true);
            return;
        }
        StringBuilder cores = new StringBuilder(), esc = new StringBuilder(), aneis = new StringBuilder(), desc = new StringBuilder();
        for (String d : Fundo.DESCANSO) desc.append(desc.length() > 0 ? "," : "").append(Json.str(d));
        for (String c : Fundo.CORES) cores.append(cores.length() > 0 ? "," : "").append(Json.str(c));
        for (String e : Fundo.ESCURECER) esc.append(esc.length() > 0 ? "," : "").append(Json.str(e));
        for (String[] a : ANEIS) {
            aneis.append(aneis.length() > 0 ? "," : "").append("{\"id\":").append(Json.str(a[0]))
                    .append(",\"nome\":").append(Json.str(a[1])).append(",\"on\":").append(Prefs.showRing(ctx, a[0])).append('}');
        }
        json(out, 200, "{\"ok\":true,\"foto\":" + Fundo.temFoto(ctx) + ",\"cor\":" + Prefs.fundoCor(ctx)
                + ",\"cores\":[" + cores + "],\"escurecer\":" + Prefs.fundoEscurecer(ctx)
                + ",\"escurecerOp\":[" + esc + "],\"aneis\":[" + aneis + "],\"descanso\":" + Prefs.descansoModo(ctx)
                + ",\"descansoCor\":" + Prefs.descansoCor(ctx) + ",\"descansoOp\":[" + desc + "]}");
    }

    /** Foto de fundo enviada pelo celular: fica so como fundo, a original nao e guardada. */
    private void fundo(Req r, OutputStream out) throws IOException {
        if (!"POST".equals(r.method) || r.length <= 0 || r.length > MAX_FOTO_BYTES) {
            json(out, 400, "{\"ok\":false,\"erro\":\"envie uma foto de até 25 MB\"}");
            return;
        }
        File tmp = File.createTempFile("foto", null, ctx.getCacheDir());
        try {
            byte[] buf = new byte[64 * 1024];
            long left = r.length;
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                while (left > 0) {
                    int n = r.in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new EOFException();
                    fo.write(buf, 0, n);
                    left -= n;
                }
            }
            boolean paraDescanso = "descanso".equals(r.q.get("destino"));
            boolean ok = paraDescanso ? Fundo.importarDescanso(ctx, tmp) : Fundo.importar(ctx, tmp);
            if (ok && paraDescanso) {
                Prefs.setDescanso(ctx, 2, 0);
                Sistema.aplicarDescanso(ctx);
            } else if (ok) {
                HomeActivity.refreshTheme();
            }
            json(out, 200, ok ? "{\"ok\":true}" : "{\"ok\":false,\"erro\":\"formato de foto não suportado\"}");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ---- WebSocket (RFC 6455, so mensagens de texto curtas) ----

    /** Celulares conectados, para avisar quando a tela do projetor muda. */
    private final Map<OutputStream, String> clients = new java.util.concurrent.ConcurrentHashMap<>();

    /** Envio fora da thread principal (rede na main thread derruba o app). */
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private void broadcast(String json) {
        final byte[] data = json.getBytes(StandardCharsets.UTF_8);
        sender.execute(() -> {
            for (OutputStream out : clients.keySet()) {
                try {
                    synchronized (out) {
                        frame(out, 0x1, data);
                    }
                } catch (IOException e) {
                    clients.remove(out);
                }
            }
        });
    }

    private void websocket(Req r, Socket s, OutputStream out) throws IOException {
        if (webSockets.incrementAndGet() > MAX_WEBSOCKETS) {
            webSockets.decrementAndGet();
            json(out, 503, "{\"ok\":false,\"erro\":\"controles demais conectados\"}");
            return;
        }
        try {
            String key = r.h.get("sec-websocket-key");
            if (key == null) {
                json(out, 400, "{\"ok\":false}");
                return;
            }
            String accept;
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                accept = Base64.encodeToString(sha1.digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            } catch (Exception e) {
                throw new IOException(e);
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            s.setSoTimeout(0);
            clients.put(out, s.getInetAddress().getHostAddress());
            try {
                synchronized (out) {
                    // Estado inicial da tela assim que o celular conecta.
                    frame(out, 0x1, ctl.stateJson().getBytes(StandardCharsets.UTF_8));
                }
                readFrames(r.in, out);
            } finally {
                clients.remove(out);
                // Ninguem mais com o controle aberto: a seta nao fica largada na tela.
                if (clients.isEmpty()) ctl.command("cur:0");
            }
        } finally {
            webSockets.decrementAndGet();
        }
    }

    private void readFrames(InputStream in, OutputStream out) throws IOException {
        while (running) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len > 65536) return;
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] data = new byte[(int) len];
            readFully(in, data);
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (op == 0x8) {
                synchronized (out) {
                    frame(out, 0x8, new byte[0]);
                }
                return;
            } else if (op == 0x9) {
                synchronized (out) {
                    frame(out, 0xA, data);
                }
            } else if (op == 0x1) {
                ctl.command(new String(data, StandardCharsets.UTF_8));
            }
        }
    }

    private static void frame(OutputStream out, int op, byte[] data) throws IOException {
        out.write(0x80 | op);
        if (data.length < 126) {
            out.write(data.length);
        } else {
            out.write(126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        }
        out.write(data);
        out.flush();
    }

    // ---- Utilitarios HTTP ----

    private void asset(OutputStream out, String path, String type) throws IOException {
        try (InputStream a = ctx.getAssets().open(path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = a.read(buf)) > 0) bo.write(buf, 0, n);
            send(out, 200, type, bo.toByteArray());
        }
    }

    private static void ok(OutputStream out, boolean ok) throws IOException {
        json(out, ok ? 200 : 400, ok ? "{\"ok\":true}" : "{\"ok\":false}");
    }

    private static void json(OutputStream out, int code, String body) throws IOException {
        send(out, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    /** Chromium consulta a permissao da rede local antes do fetch; nunca executa a acao no preflight. */
    private static void preflight(Req r, OutputStream out) throws IOException {
        String origin = r.h.get("origin");
        String host = r.h.get("host");
        if (origin == null || host == null || !origin.equals("http://" + host)) {
            send(out, 403, "text/plain", new byte[0]);
            return;
        }
        out.write(("HTTP/1.1 204 No Content\r\n"
                + "Access-Control-Allow-Origin: " + origin + "\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, X-Pin\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Access-Control-Max-Age: 600\r\n"
                + "Vary: Origin\r\nCache-Control: no-store\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 401: status = "Unauthorized"; break;
            case 403: status = "Forbidden"; break;
            case 404: status = "Not Found"; break;
            case 405: status = "Method Not Allowed"; break;
            case 503: status = "Service Unavailable"; break;
            case 507: status = "Insufficient Storage"; break;
            default: status = "Error";
        }
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 8192) throw new IOException("linha longa demais");
        }
        if (c < 0 && bo.size() == 0) return null;
        return bo.toString("UTF-8");
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private static void parseQuery(String qs, Map<String, String> into) {
        for (String kv : qs.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                if (!into.containsKey(k)) into.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    /** JSON plano {"k":"v"} enviado pelo app Atalhos ("Solicitar corpo: JSON"). */
    private static void parseJson(String body, Map<String, String> into) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(body);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!into.containsKey(k)) into.put(k, o.optString(k));
            }
        } catch (Exception ignored) {
        }
    }
}
