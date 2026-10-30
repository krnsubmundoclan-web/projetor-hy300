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

/** Grade com todos os apps (estilo Launchpad). Segurar OK abre a acao de adicionar. */
public class AppsActivity extends Activity {
    private static final int COLS = 6;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.col(this);
        root.setBackground(Ui.wallpaper(this));
        root.setPadding(Ui.dp(64), Ui.dp(36), Ui.dp(64), 0);

        TextView title = Ui.text(this, "Todos os apps", 34, Ui.w700, Ui.TEXT);
        title.setLetterSpacing(-0.02f);
        root.addView(title);
        TextView hint = Ui.text(this, "OK abre o app · Segure OK para adicionar à tela inicial", 15, Ui.w400, Ui.MUTED);
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
        TextView t = Ui.text(this, a.label, 13, Ui.w600, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(12);
        tlp.leftMargin = tlp.rightMargin = Ui.dp(8);
        c.addView(t, tlp);
        Ui.focusable(c, bg, 1.06f, null);
        c.setOnClickListener(v -> {
            try {
                startActivity(Apps.launchIntent(this, a.pkg));
            } catch (Exception e) {
                App.inst.controller.hud.message("Não foi possível abrir");
            }
        });
        c.setOnLongClickListener(v -> {
            Ui.appMenu(this, c, a.label, new String[]{"Adicionar à tela inicial"}, new Runnable[]{() -> {
                if (Prefs.favs(this).contains(a.pkg)) {
                    App.inst.controller.hud.message(a.label + " já está na tela inicial");
                } else {
                    List<Apps.Entry> before = Apps.favorites(this);
                    String replaced = before.size() >= Prefs.MAX_FAVS
                            ? before.get(before.size() - 1).label : null;
                    Prefs.toggleFav(this, a.pkg);
                    App.inst.controller.hud.message(a.label + " adicionado à tela inicial"
                            + (replaced == null ? "" : " · " + replaced + " saiu para liberar espaço"));
                }
            }});
            return true;
        });
        return c;
    }
}
