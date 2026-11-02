package com.mlab.instalador;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Os passos da instalacao, sobre uma conexao ADB ja aberta. Nao depende do Android,
 * entao o mesmo codigo roda no teste do computador.
 */
final class Instalacao {
    static final String PKG = "com.mlab.projetor";
    static final String HOME = PKG + "/.HomeActivity";

    /** O que foi lido do projetor antes de instalar. */
    static final class Info {
        String model, manufacturer, android;
        int sdk;
        boolean installed;

        String nome() {
            String m = model == null || model.isEmpty() ? "Projetor Android" : model;
            String f = manufacturer == null || manufacturer.isEmpty() ? "" : manufacturer + ", ";
            return m + " (" + f + "Android " + android + ")";
        }
    }

    enum Resultado { OK, INCOMPATIVEL, ANDROID_ANTIGO, OUTRA_VERSAO, VERSAO_MAIS_NOVA, SEM_ESPACO, ERRO_MANIFESTO, ERRO }

    static final class Saida {
        Resultado resultado;
        String detalhe = "";
        String codigoErro = ""; // Código para suporte identificar o problema
        boolean telaInicial;
    }

    private Instalacao() {}

    static Info info(AdbClient adb) throws IOException {
        Info i = new Info();
        i.model = adb.shell("getprop ro.product.model");
        i.manufacturer = adb.shell("getprop ro.product.manufacturer");
        i.android = adb.shell("getprop ro.build.version.release");
        try {
            i.sdk = Integer.parseInt(adb.shell("getprop ro.build.version.sdk"));
        } catch (NumberFormatException e) {
            i.sdk = 0;
        }
        i.installed = false;
        for (String line : adb.shell("pm list packages " + PKG).split("\n")) {
            if (line.trim().equals("package:" + PKG)) i.installed = true;
        }
        return i;
    }

    /**
     * Estratégia: Sempre tentar Shared primeiro (Controle Total).
     * Se falhar, o método instalar() fará o fallback automático para Compat.
     */
    static String escolherApk(Info info) {
        return "Projetor-shared.apk";
    }

    /**
     * Instala o APK (pela entrada padrao do "cmd package install"), abre o app para ele virar a
     * tela inicial e confere. Num aparelho incompativel o Android recusa e nada muda.
     */
    static Saida instalar(AdbClient adb, Info info, byte[] apk) throws IOException {
        Saida s = new Saida();
        if (info.sdk > 0 && info.sdk < 28) {
            s.resultado = Resultado.ANDROID_ANTIGO;
            return s;
        }
        String out = adb.exec("exec:cmd package install -r -S " + apk.length, apk).trim();
        s.detalhe = out;
        if (!out.contains("Success")) {
            String u = out.toUpperCase(Locale.ROOT);
            // Troca entre versao de sistema e compativel: o Android exige remover antes (os ajustes do app se perdem).
            if (u.contains("UID_CHANGED")) {
                adb.shell("pm uninstall " + PKG);
                out = adb.exec("exec:cmd package install -r -S " + apk.length, apk).trim();
                s.detalhe = out;
                u = out.toUpperCase(Locale.ROOT);
            }
        }
        if (!out.contains("Success")) {
            String u = out.toUpperCase(Locale.ROOT);
            if (u.contains("SHARED_USER_INCOMPATIBLE")) {
                s.resultado = Resultado.INCOMPATIVEL;
                s.codigoErro = "ERR_102_SHARED_USER";
            }
            else if (u.contains("MANIFEST_MALFORMED") || u.contains("PARSE_FAILED")) {
                s.resultado = Resultado.ERRO_MANIFESTO;
                s.codigoErro = "ERR_101_MANIFEST";
                s.detalhe = "Erro de compatibilidade do sistema (Android 12+). O instalador tentará uma versão universal.";
            }
            else if (u.contains("UPDATE_INCOMPATIBLE")) {
                s.resultado = Resultado.OUTRA_VERSAO;
                s.codigoErro = "ERR_103_UPDATE";
            }
            else if (u.contains("VERSION_DOWNGRADE")) {
                s.resultado = Resultado.VERSAO_MAIS_NOVA;
                s.codigoErro = "ERR_104_DOWNGRADE";
            }
            else if (u.contains("INSUFFICIENT_STORAGE") || u.contains("NOT_ENOUGH_SPACE")) {
                s.resultado = Resultado.SEM_ESPACO;
                s.codigoErro = "ERR_105_SPACE";
                s.detalhe = "O projetor está sem espaço interno suficiente para instalar o app. Apague alguns aplicativos ou arquivos no projetor (Ajustes → Armazenamento) e tente novamente.";
            }
            else {
                s.resultado = Resultado.ERRO;
                s.codigoErro = "ERR_999_UNKNOWN";
            }
            return s;
        }
        s.resultado = Resultado.OK;
        // Na primeira abertura o proprio app se define como tela inicial e desliga o modo mouse.
        adb.shell("am start -n " + HOME);
        for (int i = 0; i < 8 && !s.telaInicial; i++) {
            sleep(1000);
            s.telaInicial = telaInicialAtual(adb).startsWith(PKG + "/");
        }
        return s;
    }

    /** Versao compativel: da de uma vez as permissoes que so o ADB concede; o app cuida do resto. */
    static void provisionarCompat(AdbClient adb) throws IOException {
        adb.shell("appops set " + PKG + " SYSTEM_ALERT_WINDOW allow");
        adb.shell("pm grant " + PKG + " android.permission.WRITE_SECURE_SETTINGS");
        adb.shell("pm grant " + PKG + " android.permission.READ_EXTERNAL_STORAGE");
        adb.shell("pm grant " + PKG + " android.permission.WRITE_EXTERNAL_STORAGE");
        adb.shell("cmd package set-home-activity " + HOME);
    }

    static String telaInicialAtual(AdbClient adb) throws IOException {
        String out = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
        String[] l = out.split("\n");
        return l.length == 0 ? "" : l[l.length - 1].trim();
    }

    /** Desliga o ADB e confirma que a porta fechou enquanto o controle continua acessivel. */
    static boolean desligarAdb(AdbClient adb) {
        String host = adb.host();
        try {
            adb.shell("settings put global adb_enabled 0");
        } catch (IOException ignored) {
        }
        adb.close();
        sleep(3000);
        return !portaAberta(host, AdbClient.PORT) && portaAberta(host, 8080);
    }

    static boolean portaAberta(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
