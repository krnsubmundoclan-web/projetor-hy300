package com.mlab.projetor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

/** Janela do controle pelo celular: aparelhos conectados e salvos, conectar outro e o PIN. */
public class ControleActivity extends Activity {
    static final String EXTRA_QR = "qr";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout lista, painelLista;
    private View painelQr, botaoConectar;
    private ImageView qr;
    private TextView qrIp, qrPin, pinEstado;
    private String assinatura = "";
    private int conectadosAoAbrirQr;

    private final Runnable atualizar = new Runnable() {
        @Override
        public void run() {
            carregar(false);
            // Alguem acabou de entrar pelo QR: volta para a lista, onde o aparelho aparece.
            if (painelQr.getVisibility() == View.VISIBLE
                    && App.inst.server.conectados().size() > conectadosAoAbrirQr) mostrarQr(false);
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3000000);
        LinearLayout janela = Ui.col(this);
        janela.setBackground(Ui.round(0xFF1C1C22, 28));
        janela.setElevation(Ui.dp(16));
        janela.setPadding(Ui.dp(32), Ui.dp(28), Ui.dp(32), Ui.dp(28));
        janela.addView(montarLista(), new LinearLayout.LayoutParams(-1, -2));
        janela.addView(montarQr(), new LinearLayout.LayoutParams(-1, -2));
        root.addView(janela, new FrameLayout.LayoutParams(Ui.dp(780), -2, Gravity.CENTER));
        setContentView(root);
        mostrarQr(getIntent().getBooleanExtra(EXTRA_QR, false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        carregar(true);
        handler.post(atualizar);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(atualizar);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && painelQr.getVisibility() == View.VISIBLE
                && !getIntent().getBooleanExtra(EXTRA_QR, false)) {
            mostrarQr(false);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---- Lista ----

    private View montarLista() {
        LinearLayout p = Ui.col(this);
        painelLista = p;
        TextView t = Ui.text(this, "Controle pelo celular", 28, Ui.w700, Ui.TEXT);
        t.setLetterSpacing(-0.02f);
        p.addView(t);
        TextView estado = Ui.text(this, descricaoEstado(), 14, Ui.w400, Ui.MUTED);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = Ui.dp(6);
        p.addView(estado, elp);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        lista = Ui.col(this);
        scroll.addView(lista);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(210));
        slp.topMargin = Ui.dp(12);
        p.addView(scroll, slp);

        LinearLayout botoes = Ui.row(this);
        botaoConectar = botao("Conectar outro dispositivo", true, v -> mostrarQr(true));
        botoes.addView(botaoConectar, new LinearLayout.LayoutParams(0, Ui.dp(50), 1.6f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(50), 1f);
        blp.leftMargin = Ui.dp(12);
        botoes.addView(botao("Fixar PIN", false, v -> fixarPin()), blp);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, Ui.dp(50), 1f);
        glp.leftMargin = Ui.dp(12);
        botoes.addView(botao("Gerar novo PIN", false, v -> novoPin()), glp);
        LinearLayout.LayoutParams brow = new LinearLayout.LayoutParams(-1, -2);
        brow.topMargin = Ui.dp(14);
        p.addView(botoes, brow);

        pinEstado = Ui.text(this, "", 13, Ui.w500, Ui.MUTED);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(12);
        p.addView(pinEstado, plp);

        String modo = App.inst.controller.entrada.modo();
        if (Entrada.ACESSIBILIDADE.equals(modo) || Entrada.NENHUM.equals(modo)) {
            View a = botao("Abrir ajustes de acessibilidade", false, v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (RuntimeException e) {
                    App.inst.controller.hud.message("Não foi possível abrir");
                }
            });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, Ui.dp(46));
            alp.topMargin = Ui.dp(12);
            p.addView(a, alp);
        }
        return p;
    }

    private static String descricaoEstado() {
        switch (App.inst.controller.entrada.modo()) {
            case Entrada.SISTEMA:
            case Entrada.SHELL:
                return "Controle completo neste projetor";
            case Entrada.ACESSIBILIDADE:
                return "Controle básico: as setas podem falhar em alguns apps. Ligue a depuração por rede (ADB) para o completo";
            default:
                return "O controle ainda não age aqui. Ligue a acessibilidade ou a depuração por rede (ADB)";
        }
    }

    /** Refaz a lista so quando algo mudou, para o foco nao pular enquanto a pessoa navega. */
    private void carregar(boolean forcar) {
        List<Aparelhos.Item> salvos = Aparelhos.todos(this);
        Set<String> online = App.inst.server.conectados();
        StringBuilder sb = new StringBuilder();
        for (Aparelhos.Item it : salvos) sb.append(it.id).append(it.ip).append(online.contains(it.ip)).append(';');
        for (String ip : online) sb.append(ip).append(',');
        String nova = sb.toString();
        pinEstado.setText("PIN " + Prefs.pin(this) + (Prefs.pinFixo(this) ? "  ·  fixo" : ""));
        if (!forcar && nova.equals(assinatura)) return;
        assinatura = nova;
        boolean tinhaFoco = lista.findFocus() != null;
        lista.removeAllViews();
        for (Aparelhos.Item it : salvos) {
            boolean on = online.contains(it.ip);
            lista.addView(linha(it.nome, on ? "Conectado agora" : "Salvo · " + it.ip, on, it));
        }
        for (String ip : online) {
            boolean salvo = false;
            for (Aparelhos.Item it : salvos) salvo |= it.ip.equals(ip);
            if (!salvo) lista.addView(linha("Aparelho", "Conectado agora · " + ip, true, null));
        }
        if (lista.getChildCount() == 0) {
            TextView v = Ui.text(this, "Nenhum aparelho ainda. Conecte o primeiro pelo botão abaixo", 15, Ui.w500, Ui.MUTED);
            v.setGravity(Gravity.CENTER);
            lista.addView(v, new LinearLayout.LayoutParams(-1, Ui.dp(180)));
        }
        if (tinhaFoco || (forcar && painelLista.getVisibility() == View.VISIBLE)) botaoConectar.requestFocus();
    }

    private View linha(String nome, String detalhe, boolean on, final Aparelhos.Item item) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(0x14FFFFFF, 16));
        r.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        r.addView(Ui.icon(this, R.drawable.ic_phone, 24, Ui.TEXT));
        LinearLayout txt = Ui.col(this);
        txt.addView(Ui.text(this, nome, 16, Ui.w600, Ui.TEXT));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ui.dp(2);
        txt.addView(Ui.text(this, detalhe, 13, Ui.w500, on ? 0xFF30D158 : Ui.MUTED), dlp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.dp(14);
        r.addView(txt, tlp);
        if (item != null) {
            r.addView(botao("Remover", false, v -> confirmarRemover(item)), new LinearLayout.LayoutParams(Ui.dp(110), Ui.dp(40)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private void confirmarRemover(final Aparelhos.Item item) {
        dialog().setTitle("Remover " + item.nome + "?")
                .setMessage("Ele vai precisar do PIN para conectar de novo.")
                .setPositiveButton("Remover", (x, y) -> {
                    Aparelhos.remover(this, item.id);
                    App.inst.server.desconectar(item.ip);
                    carregar(true);
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ---- QR, IP e PIN ----

    private View montarQr() {
        LinearLayout p = Ui.row(this);
        painelQr = p;
        p.setGravity(Gravity.CENTER_VERTICAL);
        qr = new ImageView(this);
        qr.setBackground(Ui.round(0xFFFFFFFF, 18));
        // A margem branca faz a "zona de silencio" que o leitor de QR precisa.
        qr.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        qr.setScaleType(ImageView.ScaleType.CENTER);
        p.addView(qr, new LinearLayout.LayoutParams(Ui.dp(260), Ui.dp(260)));

        LinearLayout txt = Ui.col(this);
        TextView t = Ui.text(this, "Conectar outro dispositivo", 24, Ui.w700, Ui.TEXT);
        t.setLetterSpacing(-0.02f);
        txt.addView(t);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = Ui.dp(10);
        txt.addView(Ui.text(this, "No mesmo Wi‑Fi, aponte a câmera do celular para o código", 15, Ui.w500, Ui.MUTED), ilp);
        LinearLayout.LayoutParams o1 = new LinearLayout.LayoutParams(-2, -2);
        o1.topMargin = Ui.dp(22);
        txt.addView(Ui.text(this, "Ou abra no navegador", 13, Ui.w600, Ui.MUTED), o1);
        qrIp = Ui.text(this, "", 24, Ui.w700, Ui.TEXT);
        LinearLayout.LayoutParams o2 = new LinearLayout.LayoutParams(-2, -2);
        o2.topMargin = Ui.dp(4);
        txt.addView(qrIp, o2);
        LinearLayout.LayoutParams o3 = new LinearLayout.LayoutParams(-2, -2);
        o3.topMargin = Ui.dp(14);
        txt.addView(Ui.text(this, "PIN", 13, Ui.w600, Ui.MUTED), o3);
        qrPin = Ui.text(this, "", 30, Ui.w700, Ui.TEXT);
        qrPin.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams o4 = new LinearLayout.LayoutParams(-2, -2);
        o4.topMargin = Ui.dp(4);
        txt.addView(qrPin, o4);
        LinearLayout.LayoutParams o5 = new LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(44));
        o5.topMargin = Ui.dp(18);
        txt.addView(botao("Voltar", false, v -> {
            if (getIntent().getBooleanExtra(EXTRA_QR, false)) finish();
            else mostrarQr(false);
        }), o5);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.dp(28);
        p.addView(txt, tlp);
        return p;
    }

    private void mostrarQr(boolean mostrar) {
        if (mostrar) {
            String ip = Net.ip();
            if (ip.indexOf('.') < 0) {
                App.inst.controller.hud.message("Conecte o projetor ao Wi‑Fi primeiro");
                mostrar = false;
            } else {
                preencherQr(ip);
                conectadosAoAbrirQr = App.inst.server.conectados().size();
            }
        }
        painelQr.setVisibility(mostrar ? View.VISIBLE : View.GONE);
        painelLista.setVisibility(mostrar ? View.GONE : View.VISIBLE);
        if (mostrar) {
            LinearLayout col = (LinearLayout) ((LinearLayout) painelQr).getChildAt(1);
            col.getChildAt(col.getChildCount() - 1).requestFocus();
        } else if (botaoConectar != null) {
            botaoConectar.requestFocus();
        }
    }

    private void preencherQr(String ip) {
        String pin = Prefs.pin(this);
        // O PIN vai depois do "#": fica so no celular, nao e enviado na requisicao.
        Bitmap bmp = Qr.bitmap("http://" + ip + ":" + ControlService.PORT + "/#p=" + pin, Ui.dp(232), 0);
        if (bmp != null) {
            BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
            d.setFilterBitmap(false);
            qr.setImageDrawable(d);
        }
        qrIp.setText(ip + ":" + ControlService.PORT);
        qrPin.setText(pin);
    }

    // ---- PIN ----

    private void fixarPin() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        in.setGravity(Gravity.CENTER);
        in.setTextSize(28);
        in.setHint("6 números");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), 0);
        box.addView(in);
        dialog().setTitle("Fixar PIN")
                .setMessage("Escolha um PIN de 6 números. Ele não muda até você trocar.")
                .setView(box)
                .setPositiveButton("Fixar", (x, y) -> {
                    String p = in.getText().toString().trim();
                    if (!p.matches("\\d{6}")) {
                        App.inst.controller.hud.message("O PIN precisa ter 6 números");
                        return;
                    }
                    App.inst.server.fixarPin(p);
                    carregar(true);
                    App.inst.controller.hud.message("PIN fixado");
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void novoPin() {
        dialog().setTitle("Gerar novo PIN?")
                .setMessage(Prefs.pinFixo(this) ? "O PIN fixo deixa de valer. Os aparelhos salvos continuam entrando sem PIN."
                        : "Os aparelhos salvos continuam entrando sem PIN.")
                .setPositiveButton("Gerar", (x, y) -> {
                    App.inst.server.regeneratePin();
                    carregar(true);
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ---- Comum ----

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private View botao(String texto, boolean primario, View.OnClickListener l) {
        LinearLayout b = Ui.row(this);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = primario ? Ui.round(Ui.ACCENT, 14) : Ui.glass(14);
        b.setBackground(bg);
        TextView t = Ui.text(this, texto, 14, Ui.w600, primario ? 0xFFFFFFFF : Ui.TEXT);
        t.setSingleLine(true);
        b.addView(t);
        Ui.focusable(b, bg, 1.04f, null);
        b.setOnClickListener(l);
        return b;
    }
}
