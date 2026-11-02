package com.mlab.projetor;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Servico de acessibilidade do app:
 * - intercepta as teclas de volume do controle fisico (sem a barra do fabricante, com o Hud);
 * - acompanha qual app esta aberto e qual item esta focado, para o controle do celular
 *   se adaptar a cada tela ("Selecionado: Netflix").
 */
public class KeyService extends AccessibilityService {
    private static final int MAX_LEN = 80;
    /** Ligado nos ajustes de acessibilidade; null quando desligado. */
    static volatile KeyService inst;

    @Override
    protected void onServiceConnected() {
        inst = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    // ---- Controle sem permissao de sistema (versao compativel) ----

    boolean toque(float x, float y, boolean longo) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y);
        return gesto(p, longo ? 700 : 50);
    }

    boolean arrasto(float x0, float y0, float x1, float y1) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        return gesto(p, 120);
    }

    private boolean gesto(android.graphics.Path p, long ms) {
        return dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null);
    }

    /** Sem injetar teclas: voltar e inicio sao acoes globais; setas e OK agem sobre o item focado. */
    boolean tecla(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case KeyEvent.KEYCODE_HOME:
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
                return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case KeyEvent.KEYCODE_DPAD_UP:
                return foco(android.view.View.FOCUS_UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return foco(android.view.View.FOCUS_DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return foco(android.view.View.FOCUS_LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return foco(android.view.View.FOCUS_RIGHT);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return clicarFoco();
            case KeyEvent.KEYCODE_DEL:
                return apagar();
            default:
                return false;
        }
    }

    private AccessibilityNodeInfo focado() {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return n != null ? n : findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    }

    private boolean foco(int direcao) {
        AccessibilityNodeInfo atual = focado();
        if (atual == null) {
            AccessibilityNodeInfo raiz = getRootInActiveWindow();
            if (raiz == null) return false;
            AccessibilityNodeInfo primeiro = raiz.focusSearch(android.view.View.FOCUS_FORWARD);
            return primeiro != null && primeiro.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        AccessibilityNodeInfo prox = atual.focusSearch(direcao);
        return prox != null && prox.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private boolean clicarFoco() {
        AccessibilityNodeInfo n = focado();
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    boolean texto(String s) {
        AccessibilityNodeInfo n = focado();
        if (n == null || !n.isEditable()) return false;
        CharSequence atual = n.getText();
        return definirTexto(n, (atual == null ? "" : atual.toString()) + s);
    }

    private boolean apagar() {
        AccessibilityNodeInfo n = focado();
        if (n == null || !n.isEditable() || n.getText() == null || n.getText().length() == 0) return false;
        CharSequence t = n.getText();
        return definirTexto(n, t.subSequence(0, t.length() - 1).toString());
    }

    private static boolean definirTexto(AccessibilityNodeInfo n, String s) {
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN && k != KeyEvent.KEYCODE_VOLUME_MUTE) {
            // Teclas do controle fisico: deixa passar e depois avisa o celular do novo foco.
            // OK do controle fisico: o item focado num app de video vai para os Recentes.
            if (e.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
                    && App.inst != null) {
                App.inst.controller.onSelect();
            }
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && App.inst != null) {
            App.inst.controller.volume(k == KeyEvent.KEYCODE_VOLUME_UP ? "up" : k == KeyEvent.KEYCODE_VOLUME_DOWN ? "down" : "mute");
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (App.inst == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        Controller ctl = App.inst.controller;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                // Teclado e janelas do sistema nao contam como "app aberto".
                // Do proprio app so contam as telas (Activity); o aviso de volume e o cursor tambem
                // geram esse evento e fariam parecer que o usuario voltou para a tela inicial.
                CharSequence cls = event.getClassName();
                boolean ownOverlay = pkg.equals(getPackageName()) && (cls == null || !cls.toString().endsWith("Activity"));
                if (!ownOverlay && !pkg.equals("android") && !pkg.equals("com.android.systemui") && !pkg.contains("inputmethod")) {
                    ctl.onScreen(pkg, null);
                }
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                String label = describe(event);
                if (label != null) ctl.onScreen(pkg, label);
                break;
            default:
        }
    }

    /** Texto do item focado vindo de um evento (quando o sistema envia eventos de foco). */
    private static String describe(AccessibilityEvent event) {
        AccessibilityNodeInfo node = event.getSource();
        String s = null;
        if (node != null) {
            s = describe(node);
            node.recycle();
        }
        if (s == null && !event.getText().isEmpty()) s = clean(event.getText().get(0).toString());
        if (s == null && event.getContentDescription() != null) s = clean(event.getContentDescription().toString());
        return s;
    }

    /** Texto do item: o proprio texto/descricao, senao os textos dos filhos. */
    private static String describe(AccessibilityNodeInfo node) {
        String s = nodeText(node);
        if (s == null) {
            StringBuilder sb = new StringBuilder();
            collect(node, sb, 0);
            if (sb.length() > 0) s = sb.toString();
        }
        return s == null ? null : clean(s);
    }

    private static String clean(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN - 1) + "…" : s;
    }

    private static String nodeText(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().trim().length() > 0) return d.toString();
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().length() > 0) return t.toString();
        return null;
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (depth > 3 || sb.length() > MAX_LEN) return;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            String t = nodeText(c);
            if (t != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(t.trim());
            } else {
                collect(c, sb, depth + 1);
            }
            c.recycle();
        }
    }

    @Override
    public void onInterrupt() {
    }
}
