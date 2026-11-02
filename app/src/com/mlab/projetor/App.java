package com.mlab.projetor;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.os.StrictMode;
import android.os.UserHandle;
import android.util.Log;

public class App extends Application {
    static App inst;
    Controller controller;
    WebServer server;
    boolean testingInternet;
    private Thread internetWorker;
    private boolean internetCancelled;

    /** Chamado na thread principal; o teste continua se a Home for recriada ou perder o foco. */
    void testInternet(boolean manual) {
        long now = System.currentTimeMillis();
        long previousAttempt = Prefs.downloadAttemptAt(this);
        if (!InternetSpeed.shouldTest(Prefs.showHardware(this), testingInternet, manual, previousAttempt, now)) return;
        testingInternet = true;
        internetCancelled = false;
        Prefs.setDownloadAttemptAt(this, now);
        HomeActivity.refreshInternet(null);
        internetWorker = new Thread(() -> {
            int kbps = -1;
            try {
                kbps = InternetSpeed.measureKbps();
            } catch (java.io.IOException | SecurityException e) {
                if (!Thread.currentThread().isInterrupted()) Log.w("Projetor", "Teste de internet indisponível", e);
            }
            final int measured = kbps;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                testingInternet = false;
                internetWorker = null;
                if (internetCancelled) {
                    Prefs.setDownloadAttemptAt(this, previousAttempt);
                    HomeActivity.refreshInternet(null);
                    if (Prefs.showHardware(this)) testInternet(false);
                    return;
                }
                String message = "Não foi possível medir a velocidade. Verifique a conexão e tente novamente. "
                        + "O resultado anterior foi mantido.";
                if (measured >= 0) {
                    String checkedAt = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT)
                            .format(new java.util.Date());
                    Prefs.setDownloadResult(this, measured, checkedAt);
                    message = "Download estimado: " + UsageRing.speedText(measured) + " Mbps.\nMedido em "
                            + checkedAt + ".\nO gráfico fica completo a partir de 15 Mbps, conforme a recomendação da Netflix para 4K. "
                            + "Para 1080p (Full HD), a Netflix recomenda 5 Mbps ou mais.";
                }
                HomeActivity.refreshInternet(manual ? message : null);
            });
        }, "internet-speed");
        internetWorker.start();
    }

    void cancelInternetTest() {
        if (internetWorker == null) return;
        internetCancelled = true;
        internetWorker.interrupt();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        // Permite abrir arquivos enviados pelo celular com Uri file:// nos players do sistema.
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        // O firmware vem em ingles e o relogio da tela inicial usa o idioma padrao.
        java.util.Locale.setDefault(new java.util.Locale("pt", "BR"));
        Prefs.setDark(this, true);
        Ui.init(this);
        fusoInicial();
        if (Prefs.netflixFix(this)) applyNetflixFix();
        takeOverSystemUi();
        // Instalado direto no projetor (sem o instalar.bat): vira a tela inicial na primeira vez.
        Sistema.claimHomeOnce(this);
        controller = new Controller(this);
        // O servidor vive no processo do app (que e o launcher, entao esta sempre de pe);
        // o servico em primeiro plano so protege o processo de ser encerrado.
        server = new WebServer(this, controller, ControlService.PORT);
        server.start();
        startKeepAlive();
        Provisao.iniciar(this, controller.entrada, controller.hud);
        Updater.schedule(this);
    }

    /**
     * Estes firmwares saem de fabrica no fuso de Los Angeles e nao acertam sozinhos sem chip: uma
     * vez, troca o fuso padrao de fabrica pelo de Brasilia. Depois disso vale o que a pessoa escolher.
     */
    private void fusoInicial() {
        if (Prefs.fusoAjustado(this)) return;
        String id = java.util.TimeZone.getDefault().getID();
        if (id.equals("America/Los_Angeles") || id.equals("GMT") || id.equals("UTC") || id.equals("Etc/UTC")) {
            try {
                getSystemService(android.app.AlarmManager.class).setTimeZone("America/Sao_Paulo");
            } catch (RuntimeException e) {
                Log.i("Projetor", "sem permissao para ajustar o fuso");
                return;
            }
        }
        Prefs.setFusoAjustado(this);
    }

    /**
     * Desliga o Codec2 para o sistema usar os decodificadores OMX. O AAC do Codec2 falha
     * (erro 0xe) quando a Netflix troca o audio do filme pelo do anuncio, gerando o erro 5.0.
     * A propriedade nao sobrevive a reinicios, por isso e reaplicada sempre que o app sobe.
     */
    private static void applyNetflixFix() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, "debug.stagefright.ccodec", "0");
        } catch (Exception e) {
            Log.w("Projetor", "nao foi possivel aplicar o ajuste da Netflix", e);
        }
    }

    /**
     * Troca os avisos do fabricante pelos do app, a cada inicializacao (o fabricante religa os dele):
     * - desliga o "modo mouse" do com.sxk.testdsndpi (avisos "enter/exit mouse");
     * - liga o KeyService, que mostra o volume do controle fisico no aviso do app.
     */
    void takeOverSystemUi() {
        if (getPackageName().endsWith(".teste")) return; // roda ao lado do app instalado
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        if (!saveSystemState()) return;
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName("com.sxk.testdsndpi", "com.sxk.testdsndpi.MyService"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w("Projetor", "modo mouse do fabricante nao encontrado", e);
        }
        try {
            ContentResolver cr = getContentResolver();
            // Mantem os servicos de acessibilidade de outros apps; so tira o modo mouse do fabricante.
            String mine = new ComponentName(this, KeyService.class).flattenToString();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder list = new StringBuilder();
            if (cur != null) {
                for (String svc : cur.split(":")) {
                    if (svc.isEmpty() || svc.equals(mine) || svc.startsWith("com.sxk.testdsndpi/")) continue;
                    list.append(svc).append(':');
                }
            }
            list.append(mine);
            // Depois de uma atualizacao do app o Android deixa o servico "habilitado mas desligado";
            // limpar e gravar de novo forca a reconexao.
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.toString());
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w("Projetor", "nao foi possivel ligar o KeyService", e);
        }
    }

    /** Versoes antigas ja alteraram estes ajustes e nao conseguem mais descobrir o estado anterior. */
    private boolean saveSystemState() {
        if (Prefs.systemStateSaved(this) || Prefs.homeClaimed(this)) return true;
        try {
            ContentResolver cr = getContentResolver();
            String services = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            int accessibility = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            int mouse = getPackageManager().getComponentEnabledSetting(Sistema.MOUSE_SVC);
            if (Prefs.saveSystemState(this, services, accessibility, mouse)) return true;
        } catch (Exception e) {
            Log.w("Projetor", "nao foi possivel guardar os ajustes originais", e);
        }
        Log.e("Projetor", "ajustes originais nao foram guardados; sistema nao sera alterado");
        return false;
    }

    /**
     * Processos com uid de sistema precisam indicar o usuario ao iniciar servicos;
     * sem isso o Android 11 ignora a chamada em silencio.
     */
    void startKeepAlive() {
        Intent i = new Intent(this, ControlService.class);
        if (android.os.Process.myUid() != android.os.Process.SYSTEM_UID) {
            startForegroundService(i);
            return;
        }
        try {
            UserHandle user = UserHandle.getUserHandleForUid(0);
            getClass().getMethod("startForegroundServiceAsUser", Intent.class, UserHandle.class)
                    .invoke(this, i, user);
        } catch (Exception e) {
            Log.w("Projetor", "startForegroundServiceAsUser indisponivel", e);
            startForegroundService(i);
        }
    }
}
