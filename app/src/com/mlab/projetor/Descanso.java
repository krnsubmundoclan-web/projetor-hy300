package com.mlab.projetor;

import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextClock;

/** Tela de descanso do projetor: a foto ou a cor escolhida em Personalizar, com um relogio discreto. */
public class Descanso extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        FrameLayout root = new FrameLayout(this);
        View fundo = new View(this);
        fundo.setBackground(Fundo.descanso(this));
        root.addView(fundo, new FrameLayout.LayoutParams(-1, -1));
        TextClock hora = new TextClock(this);
        hora.setFormat24Hour("HH:mm");
        hora.setFormat12Hour("HH:mm");
        hora.setTextSize(44);
        hora.setTypeface(Ui.w300);
        hora.setTextColor(0xFFFFFFFF);
        hora.setShadowLayer(Ui.dp(6), 0, Ui.dp(1), 0x99000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = lp.bottomMargin = Ui.dp(36);
        root.addView(hora, lp);
        setContentView(root);
    }
}
