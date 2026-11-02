package com.mlab.projetor;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Escolhe como entregar teclas e toques ao aparelho, do melhor para o mais simples:
 * sistema (versao com permissao de sistema), shell (ajudante pelo ADB local) ou
 * acessibilidade (toques e acoes globais; setas so movem o foco de quem o expoe).
 * Sempre chamado fora da main thread.
 */
final class Entrada {
    private static final String TAG = "Projetor";
    static final String SISTEMA = "sistema", SHELL = "shell", ACESSIBILIDADE = "acessibilidade", NENHUM = "nenhum";

    private final Context ctx;
    private final boolean sistema;
    private final Instrumentation inst = new Instrumentation();
    private Socket shell;
    private volatile OutputStream shellOut;
    private long shellRetryAt;

    Entrada(Context c) {
        ctx = c.getApplicationContext();
        sistema = temSistema(ctx);
    }

    static boolean temSistema(Context c) {
        return c.checkSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED;
    }

    String modo() {
        if (sistema) return SISTEMA;
        if (shellOut != null) return SHELL;
        return KeyService.inst != null ? ACESSIBILIDADE : NENHUM;
    }

    /** Liga ao ajudante; devolve false se ele nao estiver de pe (tenta de novo a cada 5 s). */
    private boolean shellPronto() {
        if (shellOut != null) return true;
        long now = SystemClock.uptimeMillis();
        if (now < shellRetryAt) return false;
        shellRetryAt = now + 5000;
        return conectarShell();
    }

    synchronized boolean conectarShell() {
        fecharShell();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), ShellInjetor.PORT), 500);
            s.setTcpNoDelay(true);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write((Prefs.shellToken(ctx) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (!("ok " + ShellInjetor.PROTOCOLO).equals(r)) throw new IOException("ajudante incompativel");
            s.setSoTimeout(0);
            shell = s;
            shellOut = out;
            return true;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    private void fecharShell() {
        if (shell != null) {
            try {
                shell.close();
            } catch (IOException ignored) {
            }
        }
        shell = null;
        shellOut = null;
    }

    private synchronized boolean shellEnvia(String cmd) {
        if (!shellPronto()) return false;
        try {
            shellOut.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            shellOut.flush();
            return true;
        } catch (IOException e) {
            fecharShell();
            return false;
        }
    }

    boolean tecla(int code) {
        if (sistema) {
            if (Injecao.key(code)) return true;
            try {
                inst.sendKeyDownUpSync(code);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "falha ao injetar tecla " + code, e);
                return false;
            }
        }
        if (shellEnvia("k " + code)) return true;
        if (midia(code)) return true;
        KeyService k = KeyService.inst;
        return k != null && k.tecla(code);
    }

    /** Teclas de midia nao precisam de permissao nenhuma: vao direto para o player ativo. */
    private boolean midia(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                break;
            default:
                return false;
        }
        AudioManager a = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        a.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return true;
    }

    boolean toque(float x, float y, boolean longo) {
        if (sistema) return Injecao.tap(x, y, longo) || toqueInstrumentation(x, y, longo);
        if (shellEnvia(String.format(Locale.ROOT, "t %.1f %.1f %d", x, y, longo ? 1 : 0))) return true;
        KeyService k = KeyService.inst;
        return k != null && k.toque(x, y, longo);
    }

    private boolean toqueInstrumentation(float x, float y, boolean longo) {
        try {
            long t = SystemClock.uptimeMillis();
            ponteiro(t, t, MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(longo ? 700 : 40);
            ponteiro(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "falha ao injetar toque", e);
            return false;
        }
    }

    private void ponteiro(long down, long when, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            inst.sendPointerSync(e);
        } finally {
            e.recycle();
        }
    }

    boolean arrasto(float x0, float y0, float x1, float y1) {
        if (sistema) return Injecao.swipe(x0, y0, x1, y1);
        if (shellEnvia(String.format(Locale.ROOT, "w %.1f %.1f %.1f %.1f", x0, y0, x1, y1))) return true;
        KeyService k = KeyService.inst;
        return k != null && k.arrasto(x0, y0, x1, y1);
    }

    boolean texto(String s) {
        if (sistema) {
            if (Injecao.text(s)) return true;
            try {
                inst.sendStringSync(s);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "falha ao digitar", e);
                return false;
            }
        }
        if (shellEnvia("x " + Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))) return true;
        KeyService k = KeyService.inst;
        return k != null && k.texto(s);
    }
}
