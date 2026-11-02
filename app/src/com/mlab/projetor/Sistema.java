package com.mlab.projetor;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Tela inicial e volta ao original sem computador: quem instala o APK direto no projetor
 * (gerenciador de arquivos, navegador ou pendrive) nao passa pelo instalar.bat.
 */
final class Sistema {
    private static final String TAG = "Projetor";
    private static final String ROLE_HOME = "android.app.role.HOME";
    static final String MOUSE_PKG = "com.sxk.testdsndpi";
    static final ComponentName MOUSE_SVC = new ComponentName(MOUSE_PKG, "com.sxk.testdsndpi.MyService");

    private Sistema() {}

    private static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    /** Pacote da tela inicial padrao agora, ou null se o Android ainda pergunta qual usar. */
    static String currentHome(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        String p = ri.activityInfo.packageName;
        return "android".equals(p) ? null : p;
    }

    /** Outra tela inicial instalada (a do fabricante), sem a do app e sem a de espera do Android. */
    private static String otherHome(Context c) {
        List<ResolveInfo> l = c.getPackageManager().queryIntentActivities(homeIntent(), 0);
        for (ResolveInfo ri : l) {
            String p = ri.activityInfo.packageName;
            if (!p.equals(c.getPackageName()) && !p.equals("android") && !p.equals("com.android.settings")
                    && !p.equals("com.android.tv.settings") && !p.equals("com.android.provision")) return p;
        }
        return null;
    }

    /**
     * Na primeira vez que o app abre, guarda a tela inicial do fabricante e vira a padrao.
     * So uma vez: se a pessoa trocar depois pelos ajustes do Android, o app respeita.
     */
    static void claimHomeOnce(Context c) {
        String cur = currentHome(c);
        // O Android as vezes perde a tela padrao numa atualizacao e fica perguntando qual usar.
        // Escolha da pessoa por outra tela (cur != null) continua sendo respeitada.
        if (Prefs.homeClaimed(c) && cur != null) return;
        if (Prefs.originalHome(c) == null) {
            String orig = cur != null && !cur.equals(c.getPackageName()) ? cur : otherHome(c);
            if (orig != null) Prefs.setOriginalHome(c, orig);
        }
        if (c.getPackageName().equals(cur)) {
            Prefs.setHomeClaimed(c);
            return;
        }
        setHome(c, c.getPackageName(), ok -> {
            if (ok) Prefs.setHomeClaimed(c);
            Log.i(TAG, "tela inicial definida: " + ok);
        });
    }

    /** Define a tela inicial padrao (o mesmo que "cmd package set-home-activity"). */
    static void setHome(Context c, String pkg, Consumer<Boolean> done) {
        try {
            Object rm = c.getSystemService(Class.forName("android.app.role.RoleManager"));
            rm.getClass().getMethod("addRoleHolderAsUser", String.class, String.class, int.class,
                    UserHandle.class, Executor.class, Consumer.class)
                    .invoke(rm, ROLE_HOME, pkg, 0, Process.myUserHandle(), c.getMainExecutor(), done);
        } catch (Exception e) {
            Log.w(TAG, "nao foi possivel definir a tela inicial", e);
            done.accept(false);
        }
    }

    /** Liga ou desliga a nossa tela de descanso, guardando a do sistema para poder voltar. */
    static void aplicarDescanso(Context c) {
        try {
            ContentResolver cr = c.getContentResolver();
            Prefs.salvarDescansoOriginal(c, Settings.Secure.getString(cr, "screensaver_components"));
            if (Prefs.descansoModo(c) == 0) {
                Settings.Secure.putString(cr, "screensaver_components", Prefs.descansoOriginal(c));
            } else {
                Settings.Secure.putString(cr, "screensaver_components", new ComponentName(c, Descanso.class).flattenToString());
                Settings.Secure.putInt(cr, "screensaver_enabled", 1);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "nao foi possivel mudar a tela de descanso", e);
        }
    }

    /** Fone, caixa de som ou outro aparelho de audio/entrada conectado. HID_HOST e o perfil 4. */
    static boolean bluetoothConectado() {
        try {
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return false;
            for (int perfil : new int[]{android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET, 4}) {
                if (bt.getProfileConnectionState(perfil) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) return true;
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "estado do Bluetooth indisponivel");
        }
        return false;
    }

    /** Um app de receber transmissao esta aberto no aparelho. */
    static boolean transmitindo(Context c) {
        if (!Entrada.temSistema(c)) return false;
        try {
            java.util.Set<String> receptores = new java.util.HashSet<>();
            for (Intent i : Hardware.receptores(c).values()) {
                if (i.getComponent() != null) receptores.add(i.getComponent().getPackageName());
            }
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(8);
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                if (t.numRunning > 0 && t.topActivity != null && receptores.contains(t.topActivity.getPackageName())) return true;
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "estado da transmissao indisponivel");
        }
        return false;
    }

    static boolean hasMouseMode(Context c) {
        try {
            c.getPackageManager().getPackageInfo(MOUSE_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Devolve o projetor ao original e remove o app: tela inicial do fabricante, modo mouse do
     * fabricante, lista de acessibilidade sem o app e decodificador de audio padrao.
     */
    static void restoreAndUninstall(final Context c) {
        String orig = Prefs.originalHome(c);
        if (orig == null || !installed(c, orig)) orig = otherHome(c);
        final String home = orig;
        restoreSystem(c);
        Runnable rest = () -> {
            if (home != null) {
                Intent i = homeIntent().setPackage(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "tela inicial original nao abriu", e);
                }
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> uninstallSelf(c), 1500);
        };
        if (home != null) setHome(c, home, ok -> rest.run());
        else rest.run();
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void restoreSystem(Context c) {
        ContentResolver cr = c.getContentResolver();
        if (Prefs.descansoOriginal(c) != null) {
            try {
                Settings.Secure.putString(cr, "screensaver_components", Prefs.descansoOriginal(c));
            } catch (RuntimeException e) {
                Log.w(TAG, "nao foi possivel restaurar a tela de descanso", e);
            }
        }
        if (Prefs.systemStateSaved(c)) {
            try {
                c.getPackageManager().setComponentEnabledSetting(MOUSE_SVC, Prefs.originalMouseState(c),
                        PackageManager.DONT_KILL_APP);
            } catch (Exception e) {
                Log.w(TAG, "nao foi possivel restaurar o componente do modo mouse", e);
            }
            try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        Prefs.originalAccessibilityServices(c));
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED,
                        Prefs.originalAccessibilityEnabled(c));
            } catch (Exception e) {
                Log.w(TAG, "nao foi possivel restaurar a acessibilidade", e);
            }
        } else {
            restoreLegacyAccessibility(c, cr);
        }
        try {
            // Volta o decodificador de audio ao padrao (vazio = valor de fabrica).
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, "debug.stagefright.ccodec", "");
        } catch (Exception e) {
            Log.w(TAG, "nao foi possivel desfazer o ajuste da Netflix", e);
        }
    }

    /** Instalacoes anteriores nao guardavam o estado original; mantem a restauracao antiga. */
    private static void restoreLegacyAccessibility(Context c, ContentResolver cr) {
        String mine = new ComponentName(c, KeyService.class).flattenToString();
        boolean mouse = hasMouseMode(c);
        try {
            if (mouse) {
                c.getPackageManager().setComponentEnabledSetting(MOUSE_SVC,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            }
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder list = new StringBuilder();
            if (cur != null) {
                for (String svc : cur.split(":")) {
                    if (svc.isEmpty() || svc.equals(mine) || svc.equals(MOUSE_SVC.flattenToString())) continue;
                    if (list.length() > 0) list.append(':');
                    list.append(svc);
                }
            }
            if (mouse) {
                if (list.length() > 0) list.append(':');
                list.append(MOUSE_SVC.flattenToString());
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.toString());
            if (list.length() == 0) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        } catch (Exception e) {
            Log.w(TAG, "nao foi possivel restaurar o modo mouse", e);
        }
    }

    private static void uninstallSelf(Context c) {
        try {
            Intent done = new Intent("com.mlab.projetor.REMOVIDO").setPackage(c.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, done, PendingIntent.FLAG_IMMUTABLE);
            c.getPackageManager().getPackageInstaller().uninstall(c.getPackageName(), pi.getIntentSender());
        } catch (Exception e) {
            Log.w(TAG, "nao foi possivel remover o app sozinho; abrindo a tela de desinstalar", e);
            Intent i = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }
}
