package com.mlab.projetor;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atualizacao automatica: confere o version.json do site, baixa o APK novo e instala sozinho.
 * A chave do APK e a de teste publica do Android, entao a assinatura do APK nao prova nada; o
 * que prova que a versao e nossa e a assinatura ECDSA do version.json (chave privada so no PC).
 */
final class Updater {
    private static final String TAG = "Projetor";
    static final String VERSION_URL = "https://mlabsoluction.pages.dev/version.json";
    /** A versao compativel nao instala por cima da de sistema (e vice-versa): cada uma tem sua lista. */
    static final String VERSION_COMPAT_URL = "https://mlabsoluction.pages.dev/version-compat.json";
    /** So aceita baixar o APK destes enderecos. */
    private static final String[] ALLOWED = {"https://mlabsoluction.pages.dev/", "https://github.com/", "https://objects.githubusercontent.com/"};
    private static final String PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEy1nV0C4Fu6tvsyOuSz/dFB4HYf7Uu9LtU5EI+KsWSUAHDEj9MG2aqUzn78NEz50wszuU6ZSi4yfvDsDbP1KnGA==";
    private static final long CHECK_EVERY_MS = 12 * 60 * 60 * 1000L;
    private static final long MAX_APK = 30L * 1024 * 1024;

    /** Versao nova encontrada (null = nenhuma). */
    static final class Info {
        int code;
        String name, url, sha256;
        long size;
    }

    interface Listener {
        void onResult(Info info, String error);
    }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Info available;
    private static volatile boolean installing;
    private static volatile Info installingInfo;
    private static volatile Listener installListener;

    private Updater() {}

    static Info available() {
        return available;
    }

    /** Confere logo depois de ligar e a cada 12 horas; avisa a tela inicial se houver versao nova. */
    static void schedule(final Context c) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                check(c, (info, err) -> {
                    if (info != null) HomeActivity.offerUpdate(info);
                });
                main.postDelayed(this, CHECK_EVERY_MS);
            }
        }, 90_000);
    }

    static void check(final Context c, final Listener l) {
        worker.execute(() -> {
            Info found = null;
            String err = null;
            try {
                found = fetch(c);
            } catch (Exception e) {
                Log.w(TAG, "nao foi possivel procurar atualizacao", e);
                err = "Sem conexão com o site de atualização";
            }
            available = found;
            final Info f = found;
            final String e = err;
            main.post(() -> l.onResult(f, e));
        });
    }

    private static Info fetch(Context c) throws Exception {
        JSONObject j = new JSONObject(new String(get(Entrada.temSistema(c) ? VERSION_URL : VERSION_COMPAT_URL, 64 * 1024), StandardCharsets.UTF_8));
        Info i = new Info();
        i.code = j.getInt("code");
        i.name = j.getString("name");
        i.url = j.getString("url");
        i.sha256 = j.getString("sha256").toLowerCase(Locale.ROOT);
        i.size = j.optLong("size", 0);
        String sig = j.getString("sig");
        if (!verify(PKG_PREFIX + i.code + "|" + i.sha256, sig)) throw new SecurityException("version.json sem assinatura valida");
        if (!allowed(i.url)) throw new SecurityException("endereco de download nao permitido: " + i.url);
        long mine = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        return i.code > mine ? i : null;
    }

    private static final String PKG_PREFIX = "com.mlab.projetor|";

    private static boolean verify(String msg, String sigB64) throws Exception {
        PublicKey k = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(k);
        s.update(msg.getBytes(StandardCharsets.UTF_8));
        return s.verify(Base64.decode(sigB64, Base64.DEFAULT));
    }

    private static boolean allowed(String url) {
        for (String a : ALLOWED) if (url.startsWith(a)) return true;
        return false;
    }

    /**
     * Baixa, confere o SHA-256 assinado e instala por cima (o app tem permissao de sistema, entao
     * nao pergunta nada). O processo do app e reiniciado pelo Android ao fim; os ajustes ficam.
     */
    static void install(final Context c, final Info i, final Listener l) {
        if (installing) return;
        installing = true;
        installingInfo = i;
        installListener = l;
        worker.execute(() -> {
            File apk = new File(c.getCacheDir(), "atualizacao.apk");
            try {
                download(i, apk);
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(c.getPackageName());
                p.setSize(apk.length());
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("projetor.apk", 0, apk.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent done = new Intent(c, UpdateReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    // FLAG_MUTABLE e API 31; o build ainda compila com android-30.
                    if (android.os.Build.VERSION.SDK_INT >= 31) flags |= 0x02000000;
                    PendingIntent pend = PendingIntent.getBroadcast(c, id, done, flags);
                    s.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                Log.w(TAG, "atualizacao falhou", e);
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                finishInstall(e instanceof SecurityException ? "O arquivo baixado não confere. Nada foi instalado."
                        : "Não foi possível baixar a atualização");
            }
        });
    }

    /** Resultado assincrono enviado pelo PackageInstaller depois de commit(). */
    static void onInstallResult(Context c, Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm == null) throw new IllegalStateException("confirmacao ausente");
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "nao foi possivel confirmar a atualizacao", e);
                finishInstall("A atualização precisa de confirmação no projetor");
            }
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(c.getCacheDir(), "atualizacao.apk").delete();
        if (status == PackageInstaller.STATUS_SUCCESS) {
            finishInstall(null);
            return;
        }
        String detail = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        finishInstall(detail == null || detail.trim().isEmpty()
                ? "Não foi possível instalar a atualização"
                : "Não foi possível instalar a atualização: " + detail);
    }

    private static void finishInstall(String error) {
        installing = false;
        Info info = installingInfo;
        Listener listener = installListener;
        installingInfo = null;
        installListener = null;
        if (error == null) return;
        main.post(() -> {
            if (listener != null) listener.onResult(info, error);
            else if (App.inst != null && App.inst.controller != null) App.inst.controller.hud.message(error);
        });
    }

    private static void download(Info i, File dest) throws Exception {
        HttpURLConnection con = open(i.url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_APK) throw new IOException("arquivo grande demais");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } finally {
            con.disconnect();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        if (!sb.toString().equals(i.sha256)) throw new SecurityException("SHA-256 diferente");
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("resposta grande demais");
            }
            return bo.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    /** So HTTPS, segue redirecionamentos (GitHub Releases manda para outro host) ate 5 vezes. */
    private static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            if (!url.startsWith("https://")) throw new IOException("so HTTPS");
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(30_000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Cache-Control", "no-cache");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new IOException("redirecionamento sem destino");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new IOException("HTTP " + code);
            }
            return con;
        }
        throw new IOException("redirecionamentos demais");
    }
}
