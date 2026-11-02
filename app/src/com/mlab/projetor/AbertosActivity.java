package com.mlab.projetor;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apps abertos numa janela lateral por cima do que estiver na tela, como os recentes do celular.
 * OK abre o app; para a direita fica o X que encerra.
 */
public class AbertosActivity extends Activity {
    private static final String TAG = "Projetor";
    private static final int MAX = 24;
    static volatile AbertosActivity aberta;

    private LinearLayout painel, lista;
    private View fecharTodos;
    private TextView vazio;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        aberta = this;
        overridePendingTransition(0, 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);

        painel = Ui.col(this);
        painel.setBackground(Ui.round(0xF21C1C22, 26));
        painel.setElevation(Ui.dp(18));
        painel.setPadding(Ui.dp(22), Ui.dp(26), Ui.dp(22), Ui.dp(22));
        TextView titulo = Ui.text(this, "Abertos", 26, Ui.w700, Ui.TEXT);
        titulo.setLetterSpacing(-0.02f);
        painel.addView(titulo);

        vazio = Ui.text(this, "Nenhum app aberto", 16, Ui.w500, Ui.MUTED);
        vazio.setGravity(Gravity.CENTER);
        vazio.setVisibility(View.GONE);
        painel.addView(vazio, new LinearLayout.LayoutParams(-1, 0, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(4), Ui.dp(14), Ui.dp(4), Ui.dp(8));
        scroll.setVerticalScrollBarEnabled(false);
        lista = Ui.col(this);
        scroll.addView(lista);
        painel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, Ui.dp(48));
        flp.topMargin = Ui.dp(8);
        fecharTodos = botao("Fechar todos", 14, v -> fecharTudo());
        painel.addView(fecharTodos, flp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Ui.dp(400), -1, Gravity.END);
        plp.topMargin = plp.bottomMargin = plp.rightMargin = Ui.dp(16);
        root.addView(painel, plp);
        setContentView(root);
        painel.setTranslationX(Ui.dp(420));
        painel.animate().translationX(0).setDuration(200).start();
    }

    @Override
    protected void onDestroy() {
        if (aberta == this) aberta = null;
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Entrada.temSistema(this)) {
            semPermissao();
            return;
        }
        carregar(0);
    }

    /** Fora do uid de sistema o Android so mostra as tarefas do proprio app. */
    private void semPermissao() {
        lista.removeAllViews();
        fecharTodos.setVisibility(View.GONE);
        lista.addView(Ui.text(this, "Neste aparelho, feche os apps pelos ajustes do Android", 15, Ui.w500, Ui.MUTED));
        View abrir = botao("Abrir lista de apps", 14, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            } catch (RuntimeException e) {
                App.inst.controller.hud.message("Não foi possível abrir");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(48));
        lp.topMargin = Ui.dp(14);
        lista.addView(abrir, lp);
        abrir.requestFocus();
    }

    /**
     * Pacote -> tarefas, do uso mais recente para o mais antigo; so apps que a pessoa pode abrir.
     * Um app pode ter mais de uma tarefa guardada: fechar precisa tirar todas.
     */
    private Map<String, List<Integer>> tarefas() {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RecentTaskInfo> l = am.getRecentTasks(MAX, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
        for (ActivityManager.RecentTaskInfo t : l) {
            if (t.baseIntent == null || t.baseIntent.getComponent() == null) continue;
            String pkg = t.baseIntent.getComponent().getPackageName();
            if (pkg.equals(getPackageName()) || pkg.equals(Prefs.originalHome(this))) continue;
            List<Integer> ids = out.get(pkg);
            if (ids != null) {
                ids.add(t.taskId);
            } else if (Apps.entry(this, pkg) != null) {
                ids = new ArrayList<>();
                ids.add(t.taskId);
                out.put(pkg, ids);
            }
        }
        return out;
    }

    private void carregar(int foco) {
        lista.removeAllViews();
        Map<String, List<Integer>> abertos = tarefas();
        boolean nada = abertos.isEmpty();
        vazio.setVisibility(nada ? View.VISIBLE : View.GONE);
        ((View) lista.getParent()).setVisibility(nada ? View.GONE : View.VISIBLE);
        fecharTodos.setVisibility(nada ? View.GONE : View.VISIBLE);
        for (Map.Entry<String, List<Integer>> e : abertos.entrySet()) {
            lista.addView(cartao(Apps.entry(this, e.getKey()), e.getValue()));
        }
        if (nada) return;
        View c = lista.getChildAt(Math.min(foco, lista.getChildCount() - 1));
        ((LinearLayout) c).getChildAt(0).requestFocus();
    }

    private View cartao(final Apps.Entry a, final List<Integer> tarefas) {
        LinearLayout c = Ui.row(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setClipChildren(false);

        LinearLayout app = Ui.row(this);
        app.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = Ui.glass(18);
        app.setBackground(bg);
        app.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(0xFFFFFFFF, 12));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        box.addView(iv, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(36), Gravity.CENTER));
        app.addView(box, new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));
        TextView t = Ui.text(this, a.label, 16, Ui.w600, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.dp(14);
        app.addView(t, tlp);
        Ui.focusable(app, bg, 1.03f, null);
        app.setOnClickListener(v -> {
            try {
                startActivity(Apps.launchIntent(this, a.pkg));
                finish();
            } catch (RuntimeException e) {
                App.inst.controller.hud.message("Não foi possível abrir");
            }
        });
        c.addView(app, new LinearLayout.LayoutParams(0, Ui.dp(72), 1f));

        View x = botao("✕", 18, v -> {
            int pos = lista.indexOfChild((View) v.getParent());
            fechar(a.pkg, tarefas);
            App.inst.controller.hud.message(a.label + " fechado");
            carregar(pos);
        });
        x.setContentDescription("Fechar " + a.label);
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(72));
        xlp.leftMargin = Ui.dp(8);
        c.addView(x, xlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private View botao(String texto, int tamanho, View.OnClickListener l) {
        LinearLayout b = Ui.row(this);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.glass(16);
        b.setBackground(bg);
        b.addView(Ui.text(this, texto, tamanho, Ui.w600, Ui.TEXT));
        Ui.focusable(b, bg, 1.05f, null);
        b.setOnClickListener(l);
        return b;
    }

    private void fecharTudo() {
        List<Map.Entry<String, List<Integer>>> l = new ArrayList<>(tarefas().entrySet());
        for (Map.Entry<String, List<Integer>> e : l) fechar(e.getKey(), e.getValue());
        App.inst.controller.hud.message(l.size() == 1 ? "1 app fechado" : l.size() + " apps fechados");
        finish();
    }

    /** Tira da lista de recentes e encerra de verdade, liberando a memoria. */
    private void fechar(String pkg, List<Integer> tarefas) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            for (int id : tarefas) atm.getClass().getMethod("removeTask", int.class).invoke(atm, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "nao foi possivel tirar a tarefa de " + pkg, e);
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.getClass().getMethod("forceStopPackage", String.class).invoke(am, pkg);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "nao foi possivel encerrar " + pkg, e);
        }
    }
}
