package com.mlab.projetor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** Pagina de personalizacao da tela inicial: apps, fundo e os indicadores. */
public class PersonalizarActivity extends Activity {
    private static final String[][] INDICADORES = {{"temp", "Temperatura"}, {"cpu", "CPU"}, {"ram", "RAM"},
            {"espaco", "Espaço"}, {"internet", "Internet"}};

    private LinearLayout root, itens;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.col(this);
        root.setBackground(Ui.wallpaper(this));
        root.setPadding(Ui.dp(64), Ui.dp(36), Ui.dp(64), 0);
        TextView title = Ui.text(this, "Personalizar", 34, Ui.w700, Ui.TEXT);
        title.setLetterSpacing(-0.02f);
        root.addView(title);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(6), Ui.dp(18), Ui.dp(6), Ui.dp(36));
        itens = Ui.col(this);
        scroll.addView(itens);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        montar(0);
    }

    /** Refaz os itens mantendo o foco na mesma posicao. */
    private void montar(int foco) {
        itens.removeAllViews();
        secao("Tela inicial");
        item("Apps da tela inicial", Apps.favorites(this).size() + " apps", v -> startActivity(
                new Intent(this, AppsActivity.class).putExtra(AppsActivity.EXTRA_ESCOLHER, true)));
        boolean foto = Fundo.temFoto(this);
        item("Foto de fundo", foto ? "Personalizada" : "Nenhuma", v -> fotoDeFundo());
        item("Cor de fundo", foto ? "Foto em uso" : Fundo.CORES[Prefs.fundoCor(this)], v -> corDeFundo());
        if (foto) item("Escurecer a foto", Fundo.ESCURECER[Prefs.fundoEscurecer(this)], v -> escurecerFoto());
        secao("Tela de descanso");
        item("Tela de descanso", Fundo.DESCANSO[Prefs.descansoModo(this)], v -> telaDescanso());
        secao("Mostrar na tela inicial");
        for (final String[] ind : INDICADORES) {
            item(ind[1], Prefs.showRing(this, ind[0]) ? "Ligado" : "Desligado", v -> {
                Prefs.setShowRing(this, ind[0], !Prefs.showRing(this, ind[0]));
                montarMantendoFoco(v);
            });
        }
        focar(foco);
    }

    private void focar(int foco) {
        int n = 0;
        for (int i = 0; i < itens.getChildCount(); i++) {
            View c = itens.getChildAt(i);
            if (!c.isFocusable()) continue;
            if (n++ == foco) {
                c.requestFocus();
                return;
            }
        }
    }

    private int posicao(View v) {
        int n = 0;
        for (int i = 0; i < itens.getChildCount(); i++) {
            View c = itens.getChildAt(i);
            if (c == v) return n;
            if (c.isFocusable()) n++;
        }
        return 0;
    }

    private void montarMantendoFoco(View v) {
        montar(posicao(v));
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        View atual = getCurrentFocus();
        montar(atual == null ? 0 : posicao(atual));
    }

    private void secao(String texto) {
        TextView t = Ui.text(this, texto, 14, Ui.w600, Ui.MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = itens.getChildCount() == 0 ? 0 : Ui.dp(22);
        lp.bottomMargin = Ui.dp(10);
        itens.addView(t, lp);
    }

    private void item(String nome, String valor, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = Ui.glass(16);
        r.setBackground(bg);
        r.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        r.addView(Ui.text(this, nome, 17, Ui.w600, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(Ui.text(this, valor, 15, Ui.w500, Ui.MUTED));
        Ui.focusable(r, bg, 1.015f, null);
        r.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(56));
        lp.bottomMargin = Ui.dp(8);
        itens.addView(r, lp);
    }

    // ---- Fundo ----

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Aplica aqui e na tela inicial que esta por baixo. */
    private void fundoMudou() {
        root.setBackground(Ui.wallpaper(this));
        HomeActivity.refreshTheme();
        View atual = getCurrentFocus();
        montar(atual == null ? 0 : posicao(atual));
    }

    private void fotoDeFundo() {
        boolean tem = Fundo.temFoto(this);
        String[] op = tem ? new String[]{"Enviar do celular", "Escolher neste projetor ou pendrive", "Remover a foto"}
                : new String[]{"Enviar do celular", "Escolher neste projetor ou pendrive"};
        dialog().setTitle("Foto de fundo").setItems(op, (d, which) -> {
            if (which == 0) {
                dialog().setTitle("Enviar do celular")
                        .setMessage("No controle do celular, abra os ajustes e toque em Foto de fundo.")
                        .setPositiveButton("Ok", null)
                        .setNeutralButton("Conectar celular", (x, y) -> startActivity(
                                new Intent(this, ControleActivity.class).putExtra(ControleActivity.EXTRA_QR, true)))
                        .show();
            } else if (which == 1) {
                fotoLocal();
            } else {
                Fundo.remover(this);
                fundoMudou();
            }
        }).show();
    }

    private void fotoLocal() {
        App.inst.controller.hud.message("Procurando fotos…");
        new Thread(() -> {
            final List<File> fotos = Fundo.fotosDoAparelho();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fotos.isEmpty()) {
                    dialog().setTitle("Nenhuma foto encontrada")
                            .setMessage("Coloque a foto em um pendrive ou envie pelo celular.")
                            .setPositiveButton("Ok", null).show();
                    return;
                }
                String[] nomes = new String[fotos.size()];
                for (int i = 0; i < nomes.length; i++) nomes[i] = fotos.get(i).getName();
                dialog().setTitle("Escolha a foto").setItems(nomes, (d, w) -> aplicar(fotos.get(w))).show();
            });
        }, "fotos").start();
    }

    private void aplicar(final File f) {
        new Thread(() -> {
            final boolean ok = Fundo.importar(this, f);
            runOnUiThread(() -> {
                if (ok) {
                    fundoMudou();
                    if (Prefs.descansoModo(this) != 1) perguntarDescanso();
                } else App.inst.controller.hud.message("Não foi possível abrir essa foto");
            });
        }, "foto").start();
    }

    private void perguntarDescanso() {
        dialog().setTitle("Usar também na tela de descanso?")
                .setPositiveButton("Sim", (x, y) -> definirDescanso(1, 0))
                .setNegativeButton("Não", null).show();
    }

    private void definirDescanso(int modo, int cor) {
        Prefs.setDescanso(this, modo, cor);
        Sistema.aplicarDescanso(this);
        View atual = getCurrentFocus();
        montar(atual == null ? 0 : posicao(atual));
    }

    private void telaDescanso() {
        dialog().setTitle("Tela de descanso").setSingleChoiceItems(Fundo.DESCANSO, Prefs.descansoModo(this), (d, w) -> {
            d.dismiss();
            if (w == 2) fotoDescanso();
            else if (w == 3) corDescanso();
            else definirDescanso(w, 0);
        }).show();
    }

    private void fotoDescanso() {
        new Thread(() -> {
            final List<File> fotos = Fundo.fotosDoAparelho();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fotos.isEmpty()) {
                    dialog().setTitle("Nenhuma foto encontrada")
                            .setMessage("Coloque a foto em um pendrive ou envie pela aba Personalizar do controle do celular.")
                            .setPositiveButton("Ok", null).show();
                    return;
                }
                String[] nomes = new String[fotos.size()];
                for (int i = 0; i < nomes.length; i++) nomes[i] = fotos.get(i).getName();
                dialog().setTitle("Foto da tela de descanso").setItems(nomes, (d, w) -> {
                    final File f = fotos.get(w);
                    new Thread(() -> {
                        final boolean ok = Fundo.importarDescanso(this, f);
                        runOnUiThread(() -> {
                            if (ok) definirDescanso(2, 0);
                            else App.inst.controller.hud.message("Não foi possível abrir essa foto");
                        });
                    }, "foto").start();
                }).show();
            });
        }, "fotos").start();
    }

    private void corDescanso() {
        dialog().setTitle("Cor da tela de descanso").setSingleChoiceItems(Fundo.CORES, Prefs.descansoCor(this), (d, w) -> {
            d.dismiss();
            definirDescanso(3, w);
        }).show();
    }

    private void corDeFundo() {
        dialog().setTitle("Cor de fundo").setSingleChoiceItems(Fundo.CORES, Prefs.fundoCor(this), (d, w) -> {
            d.dismiss();
            Prefs.setFundoCor(this, w);
            // A cor so aparece sem foto; escolher uma cor e deixar de usar a foto.
            Fundo.remover(this);
            fundoMudou();
        }).show();
    }

    private void escurecerFoto() {
        dialog().setTitle("Escurecer a foto").setSingleChoiceItems(Fundo.ESCURECER, Prefs.fundoEscurecer(this), (d, w) -> {
            d.dismiss();
            Prefs.setFundoEscurecer(this, w);
            fundoMudou();
        }).show();
    }
}
