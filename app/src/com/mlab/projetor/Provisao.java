package com.mlab.projetor;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Versao compativel: usa o ADB do proprio aparelho (127.0.0.1:5555), quando ligado, para fazer o
 * que o Android so deixa o shell fazer: dar as permissoes, ligar a acessibilidade, virar a tela
 * inicial e subir o ShellInjetor. Em aparelho que pede autorizacao, aparece "Permitir depuracao"
 * uma vez. Sem ADB, nada disso acontece e o app segue com a acessibilidade.
 */
final class Provisao {
    private static final String TAG = "Projetor";
    private static final long[] TENTATIVAS = {0, 20_000, 60_000, 300_000};

    private Provisao() {}

    static void iniciar(final Context c, final Entrada entrada, final Hud hud) {
        if (Entrada.temSistema(c)) return;
        Thread t = new Thread(() -> {
            for (long espera : TENTATIVAS) {
                try {
                    Thread.sleep(espera);
                    if (executar(c, entrada, hud)) return;
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.i(TAG, "ADB local indisponivel: " + e.getMessage());
                }
            }
        }, "provisao");
        t.setDaemon(true);
        t.start();
    }

    private static boolean executar(Context c, Entrada entrada, Hud hud) throws Exception {
        if (entrada.conectarShell()) return true;
        AdbKey key = AdbKey.loadOrCreate(new File(c.getFilesDir(), "adbkey"));
        String pkg = c.getPackageName();
        try (AdbClient adb = AdbClient.connect("127.0.0.1", key,
                () -> hud.message("Marque \"Sempre permitir\" e aceite na tela do projetor"), 60_000)) {
            adb.shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            adb.shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            adb.shell("pm grant " + pkg + " android.permission.READ_EXTERNAL_STORAGE");
            adb.shell("pm grant " + pkg + " android.permission.WRITE_EXTERNAL_STORAGE");
            if (!teste(pkg) && !Prefs.homeClaimed(c)) {
                adb.shell("cmd package set-home-activity " + pkg + "/com.mlab.projetor.HomeActivity");
                if (pkg.equals(Sistema.currentHome(c))) Prefs.setHomeClaimed(c);
            }
            subirAjudante(adb, c);
        }
        if (!teste(pkg)) App.inst.takeOverSystemUi();
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            if (entrada.conectarShell()) {
                hud.message("Controle completo ativado");
                return true;
            }
        }
        throw new IOException("ajudante nao respondeu");
    }

    /** A build de teste roda ao lado do app instalado: nao mexe em tela inicial nem acessibilidade. */
    private static boolean teste(String pkg) {
        return pkg.endsWith(".teste");
    }

    private static void subirAjudante(AdbClient adb, Context c) throws IOException {
        String apk = c.getApplicationInfo().sourceDir;
        adb.shell("pkill -f com.mlab.projetor.ShellInjetor");
        adb.shell("CLASSPATH=" + apk + " setsid nohup app_process /system/bin com.mlab.projetor.ShellInjetor "
                + Prefs.shellToken(c) + " " + apk + " >/dev/null 2>&1 < /dev/null & sleep 2");
    }
}
