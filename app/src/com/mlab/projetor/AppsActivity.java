package com.mlab.projetor;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Grade com todos os apps (estilo Launchpad). Segurar OK adiciona/remove dos favoritos.
 * Com EXTRA_ESCOLHER, um OK simples faz isso: e o caminho do "+", e o unico que funciona pelo
 * controle do celular, que nao tem como segurar uma tecla.
 */
public class AppsActivity extends Activity {
    static final String EXTRA_ESCOLHER = "escolher";
    /** Uma vaga da grade fica sempre para o botao "+". */
    static final int MAX_INICIO = Prefs.MAX_FAVS - 1;
    private static final int COLS = 6;
    private boolean escolher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        escolher = getIntent().getBooleanExtra(EXTRA_ESCOLHER, false);
        LinearLayout root = Ui.col(this);
        root.setBackground(Ui.wallpaper(this));
        root.setPadding(Ui.dp(64), Ui.dp(36), Ui.dp(64), 0);

        TextView title = Ui.text(this, escolher ? "Adicionar à tela inicial" : "Todos os apps", 34, Ui.w700, Ui.TEXT);
        title.setLetterSpacing(-0.02f);
        root.addView(title);
        TextView hint = Ui.text(this, escolher
                ? "Aperte OK para colocar ou tirar da tela inicial (até " + MAX_INICIO + " apps)"
                : "Segure OK para adicionar ou remover dos favoritos", 15, Ui.w400, Ui.MUTED);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = Ui.dp(8);
        hlp.bottomMargin = Ui.dp(20);
        root.addView(hint, hlp);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(10), 0, Ui.dp(40));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setClipChildren(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        int cell = (getResources().getDisplayMetrics().widthPixels - Ui.dp(128) - Ui.dp(16) * (COLS - 1)) / COLS;
        List<Apps.Entry> list = Apps.all(this, true);
        for (int i = 0; i < list.size(); i++) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = Ui.dp(120);
            lp.leftMargin = (i % COLS == 0) ? 0 : Ui.dp(16);
            lp.bottomMargin = Ui.dp(16);
            grid.addView(cell(list.get(i)), lp);
        }
        setContentView(root);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private String nome(Apps.Entry a) {
        return escolher && Prefs.favs(this).contains(a.pkg) ? "✓ " + a.label : a.label;
    }

    /** Com o inicio cheio, avisa em vez de tirar outro app sem a pessoa saber. */
    private void alternar(Apps.Entry a, TextView t) {
        if (!Prefs.favs(this).contains(a.pkg) && Apps.favorites(this).size() >= MAX_INICIO) {
            App.inst.controller.hud.message("Tela inicial cheia: tire um app antes");
            return;
        }
        boolean added = Prefs.toggleFav(this, a.pkg);
        t.setText(nome(a));
        App.inst.controller.hud.message(a.label + (added ? " adicionado à tela inicial" : " removido da tela inicial"));
    }

    private View cell(final Apps.Entry a) {
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.glass(22);
        c.setBackground(bg);
        c.setElevation(Ui.dp(2));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(0xFFFFFFFF, 14));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        box.addView(iv, new FrameLayout.LayoutParams(Ui.dp(38), Ui.dp(38), Gravity.CENTER));
        c.addView(box, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
        final TextView t = Ui.text(this, nome(a), 13, Ui.w600, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(12);
        tlp.leftMargin = tlp.rightMargin = Ui.dp(8);
        c.addView(t, tlp);
        Ui.focusable(c, bg, 1.06f, null);
        c.setOnClickListener(v -> {
            if (escolher) {
                alternar(a, t);
                return;
            }
            try {
                startActivity(Apps.launchIntent(this, a.pkg));
            } catch (Exception e) {
                App.inst.controller.hud.message("Não foi possível abrir");
            }
        });
        c.setOnLongClickListener(v -> {
            alternar(a, t);
            return true;
        });
        return c;
    }
}
