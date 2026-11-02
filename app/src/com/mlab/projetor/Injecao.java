package com.mlab.projetor;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Injeta teclas e toques direto no InputManager, sem esperar o app da tela terminar de processar
 * (o Instrumentation espera, e num aparelho de 1 GB os comandos ficavam acumulados na fila).
 * Nao usa Context: roda no processo do app (uid de sistema) e no ShellInjetor (uid shell).
 */
final class Injecao {
    private static final int MODE_ASYNC = 0;
    private static Object manager;
    private static Method inject;
    private static int remoteDeviceId = Integer.MIN_VALUE;

    private Injecao() {}

    /** Android 14+ moveu a injecao para InputManagerGlobal. */
    private static synchronized void init() throws ReflectiveOperationException {
        if (inject != null) return;
        Class<?> c;
        try {
            c = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException e) {
            c = Class.forName("android.hardware.input.InputManager");
        }
        manager = c.getMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static boolean send(InputEvent e) {
        try {
            init();
            return Boolean.TRUE.equals(inject.invoke(manager, e, MODE_ASYNC));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Id do controle remoto fisico (teclado com direcional); sem ele, o teclado virtual. */
    private static int remoteDevice() {
        if (remoteDeviceId != Integer.MIN_VALUE) return remoteDeviceId;
        int found = KeyCharacterMap.VIRTUAL_KEYBOARD;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && (d.getSources() & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
                found = id;
                break;
            }
        }
        remoteDeviceId = found;
        return found;
    }

    /**
     * Tecla como se viesse do controle remoto (dispositivo real). Setas e OK saem com origem so
     * "direcional": apps de TV ignoram setas que chegam como "teclado + direcional".
     */
    static boolean key(int code) {
        int dev = remoteDevice();
        boolean dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
        int src = dpad ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_KEYBOARD;
        long down = SystemClock.uptimeMillis();
        boolean ok = send(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, dev, 0, KeyEvent.FLAG_FROM_SYSTEM, src));
        return send(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, dev, 0,
                KeyEvent.FLAG_FROM_SYSTEM, src)) && ok;
    }

    private static boolean touch(long down, long when, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            return send(e);
        } finally {
            e.recycle();
        }
    }

    static boolean tap(float x, float y, boolean longPress) {
        long t = SystemClock.uptimeMillis();
        boolean ok = touch(t, t, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(longPress ? 700 : 40);
        return touch(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y) && ok;
    }

    static boolean swipe(float x0, float y0, float x1, float y1) {
        long t = SystemClock.uptimeMillis();
        boolean ok = touch(t, t, MotionEvent.ACTION_DOWN, x0, y0);
        for (int i = 1; i <= 8; i++) {
            SystemClock.sleep(12);
            touch(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 8f, y0 + (y1 - y0) * i / 8f);
        }
        return touch(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y1) && ok;
    }

    /** O teclado virtual do Android so mapeia ASCII; quem chama ja tirou os acentos. */
    static boolean text(String s) {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(s.toCharArray());
        if (events == null) return false;
        boolean ok = true;
        for (KeyEvent e : events) ok &= send(KeyEvent.changeFlags(e, e.getFlags() | KeyEvent.FLAG_FROM_SYSTEM));
        return ok;
    }
}
