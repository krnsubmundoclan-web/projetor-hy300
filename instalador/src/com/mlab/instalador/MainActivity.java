package com.mlab.instalador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Instalador MS Projetor: Detecta estado, instala, gerencia e reporta erros.
 * Fluxo: Detecção -> (Instalar OU Painel de Controle) -> Reporte de Erros/Ajuda.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MSInstalador";
    private static final int INK = 0xFFFFFFFF, INK2 = 0xFFA3A3A3, INK3 = 0xFF737373, PAPER = 0xFF0A0A0A, LINE = 0xFF3D3D3D;
    private static final String ADMIN_URL = "https://mlabsoluction.pages.dev/api/help"; // Endpoint fictício para exemplo, ajustar conforme backend real

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private AdbClient adb;
    private Instalacao.Info info;
    private String lastErrorCode = "";
    private String lastErrorStage = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(40), dp(24), dp(32));
        scroll.addView(body);
        setContentView(scroll);
        inicio();
    }

    @Override
    protected void onDestroy() {
        fechar();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (adb != null || !"inicio".equals(body.getTag())) {
            fechar();
            inicio();
        } else {
            super.onBackPressed();
        }
    }

    // ---- Telas Principais ----

    private void inicio() {
        limpar("inicio");
        titulo("MS Projetor");
        texto("Gerenciador de Instalação e Controle", INK2, 16);
        espaco(32);

        botao("1. Detectar Aparelho", true, v -> telaConexao());
        espaco(16);
        texto("Suporte: HY300, HY320, TX6, X96, H96 e variantes Android.", INK3, 13);
        rodape("Versão 2.1 | M Soluction");
    }

    private void telaConexao() {
        limpar("conexao");
        titulo("Conectar ao Projetor");
        passo("1", "Celular e projetor no mesmo Wi‑Fi.");
        passo("2", "Depuração USB (ADB) ligada no projetor.");
        link("Como ligar a Depuração USB", v -> comoLigarAdb());
        espaco(24);
        botao("Procurar na rede", true, v -> procurar());
        espaco(24);
        texto("Ou digite o IP manualmente", INK3, 14);
        espaco(8);
        final EditText ip = new EditText(this);
        ip.setHint("Ex.: 192.168.0.20");
        ip.setHintTextColor(INK3);
        ip.setTextColor(INK);
        ip.setTextSize(18);
        ip.setSingleLine(true);
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setImeOptions(EditorInfo.IME_ACTION_GO);
        ip.setBackground(caixa(false));
        ip.setPadding(dp(14), dp(12), dp(14), dp(12));
        body.addView(ip, new LinearLayout.LayoutParams(-1, -2));
        espaco(10);
        botao("Conectar via IP", false, v -> {
            String s = ip.getText().toString().trim();
            if (!s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                aviso("Digite um IP válido.");
                return;
            }
            conectar(s);
        });
        espaco(16);
        link("Voltar", v -> inicio());
    }

    private void procurar() {
        final String meu = meuIpWifi();
        if (meu == null) {
            aviso("Conecte o celular ao Wi‑Fi primeiro.");
            return;
        }
        limpar("busca");
        titulo("Procurando…");
        texto("Escaneando rede " + meu.substring(0, meu.lastIndexOf('.')) + ".x", INK2, 16);
        espaco(24);
        carregando();
        worker.execute(() -> {
            List<String> l;
            try {
                l = Busca.procurar(meu);
            } catch (InterruptedException e) {
                return;
            }
            final List<String> achados = l;
            runOnUiThread(() -> resultados(achados));
        });
    }

    private void resultados(List<String> achados) {
        limpar("resultados");
        if (achados.isEmpty()) {
            titulo("Nenhum aparelho encontrado");
            texto("Verifique se o projetor está ligado e com ADB ativo.", INK2, 16);
            espaco(24);
            botao("Tentar novamente", true, v -> procurar());
            botao("Voltar", false, v -> inicio());
            return;
        }
        titulo(achados.size() == 1 ? "Aparelho encontrado" : achados.size() + " aparelhos encontrados");
        espaco(20);
        for (final String ip : achados) botao(ip, false, v -> conectar(ip));
        espaco(8);
        botao("Procurar de novo", false, v -> procurar());
    }

    private void conectar(final String ip) {
        limpar("conectando");
        titulo("Conectando…");
        final TextView status = texto(ip, INK2, 16);
        espaco(24);
        carregando();
        worker.execute(() -> {
            try {
                AdbKey key = AdbKey.loadOrCreate(new File(getFilesDir(), "adbkey"));
                AdbClient c = AdbClient.connect(ip, key, () -> runOnUiThread(() -> {
                    status.setText("Confirme 'Permitir depuração USB' na tela do projetor.");
                    status.setTextColor(INK);
                }), 90_000);
                Instalacao.Info i = Instalacao.info(c);
                adb = c;
                info = i;
                runOnUiThread(this::verificarEstado);
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> erro("Falha na conexão", msg, "CONEXAO_ADB"));
            }
        });
    }

    // ---- Lógica de Estado: Instalado vs Não Instalado ----

    private void verificarEstado() {
        if (info.installed) {
            painelControle();
        } else {
            confirmarInstalacao();
        }
    }

    private void confirmarInstalacao() {
        limpar("confirmar");
        titulo("Pronto para instalar");
        texto(info.nome(), INK, 18);
        espaco(12);
        texto("O app MS Projetor será instalado como tela inicial.", INK2, 16);
        espaco(28);
        botao("Instalar Agora", true, v -> instalar());
        botao("Cancelar", false, v -> { fechar(); inicio(); });
    }

    private void painelControle() {
        limpar("painel");
        titulo("MS Projetor Ativo");
        texto(info.nome(), INK, 18);
        texto("Versão instalada detectada.", INK2, 14);
        espaco(24);

        // Opção 1: Controle Remoto (Abre navegador no IP do projetor)
        botao(" Controle Remoto (Celular)", true, v -> abrirControleRemoto());

        // Opção 2: Restaurar de Fábrica
        botao("⚙️ Restaurar Configurações", false, v -> restaurarFabrica());

        // Opção 3: Reinstalar/Atualizar
        botao("🔄 Reinstalar / Atualizar", false, v -> instalar());

        espaco(24);
        texto("Problemas?", INK3, 14);
        botao("🆘 Solicitar Ajuda", false, v -> solicitarAjuda("", ""));

        espaco(16);
        link("Desconectar", v -> { fechar(); inicio(); });
    }

    private void abrirControleRemoto() {
        if (adb == null) return;
        // Tenta pegar o IP do projetor da conexão ADB ou usa o IP conectado
        String ip = adb.getHost();
        String url = "http://" + ip + ":8080";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            startActivity(i);
        } catch (Exception e) {
            aviso("Não foi possível abrir o navegador. Acesse manualmente: " + url);
        }
    }

    private void restaurarFabrica() {
        new AlertDialog.Builder(this)
            .setTitle("Restaurar Configurações")
            .setMessage("Isso apagará os ajustes do MS Projetor no projetor (PIN, favoritos). O app continuará instalado.")
            .setPositiveButton("Restaurar", (d, w) -> {
                worker.execute(() -> {
                    try {
                        // Comando para limpar dados do app via ADB
                        adb.shell("pm clear com.mlab.projetor");
                        runOnUiThread(() -> aviso("Configurações restauradas. O projetor pode reiniciar o app."));
                    } catch (Exception e) {
                        runOnUiThread(() -> erro("Falha ao restaurar", e.getMessage(), "RESTAURAR_FABRICA"));
                    }
                });
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ---- Instalação ----

    private void instalar() {
        limpar("instalando");
        titulo("Instalando…");
        texto("Não desligue o projetor.", INK2, 16);
        espaco(24);
        carregando();
        worker.execute(() -> {
            try {
                // Seleciona o APK correto baseado no modelo do aparelho (Shared vs Compat)
                String apkName = Instalacao.escolherApk(info);
                runOnUiThread(() -> texto("Usando versão: " + apkName, INK3, 12));

                byte[] apk = asset(apkName);
                final Instalacao.Saida s = Instalacao.instalar(adb, info, apk);

                // Sem fallback automático: força o resultado da versão Shared (ou o erro real do Android)
                runOnUiThread(() -> resultado(s));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> erro("A instalação parou", msg, "INSTALACAO_EXCECAO"));
            }
        });
    }

    private void instalarCompat() {
        limpar("instalando");
        titulo("Instalando versão compatível…");
        texto("Para este aparelho. Deixe a depuração por rede (ADB) ligada para o controle funcionar por completo.", INK2, 14);
        espaco(24);
        carregando();
        worker.execute(() -> {
            try {
                byte[] apk = asset("Projetor-compat.apk");
                final Instalacao.Saida s = Instalacao.instalar(adb, info, apk);
                if (s.resultado == Instalacao.Resultado.OK) Instalacao.provisionarCompat(adb);
                runOnUiThread(() -> resultado(s));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> erro("A instalação parou", msg, "INSTALACAO_COMPAT_EXCECAO"));
            }
        });
    }

    private void resultado(Instalacao.Saida s) {
        switch (s.resultado) {
            case OK:
                limpar("pronto");
                titulo("Instalação Concluída");
                texto(s.telaInicial ? "O MS Projetor já é a tela inicial." : "Instalado. Aperte HOME e escolha MS Projetor.", INK, 18);
                espaco(24);
                botao("Ir para Painel de Controle", true, v -> painelControle());
                botao("Fechar", false, v -> finish());
                return;
            case INCOMPATIVEL:
            case ERRO_MANIFESTO:
            case ERRO:
                limpar("erro");
                titulo("A instalação falhou");
                texto(s.detalhe.isEmpty() ? "O projetor recusou o arquivo." : s.detalhe, INK2, 14);
                espaco(24);
                // Botão específico para fallback manual conforme pedido do usuário
                botao("Instalar versão compatível", true, v -> instalarCompat());
                botao("Voltar ao início", false, v -> inicio());
                return;
            case ANDROID_ANTIGO:
                erro("Android Antigo", "Requer Android 9+.", "ERR_ANDROID_OLD");
                return;
            case OUTRA_VERSAO:
                erro("Conflito de Versão", "Já existe outro app com este nome. Remova-o primeiro.", s.codigoErro);
                return;
            case VERSAO_MAIS_NOVA:
                erro("Versão Recente", "O projetor já tem uma versão igual ou mais nova.", s.codigoErro);
                return;
            case SEM_ESPACO:
                erro("Sem Espaço", "Libere espaço no projetor e tente novamente.", s.codigoErro);
                return;
            default:
                erro("Falha Desconhecida", s.detalhe, s.codigoErro.isEmpty() ? "ERR_UNKNOWN" : s.codigoErro);
        }
    }

// ---- Sistema de Erros e Ajuda ----

    private void desligarAdb() {
        final AdbClient c = adb;
        adb = null;
        worker.execute(() -> {
            final boolean desligado = c != null && Instalacao.desligarAdb(c);
            runOnUiThread(() -> {
                limpar("fim");
                titulo(desligado ? "Tudo certo" : "Confira o acesso ADB");
                texto(desligado
                        ? "Acesso ADB desligado. Para remover o app no futuro, use Ajustes → Voltar à tela original, no projetor."
                        : "Não foi possível confirmar que o ADB desligou. Confira em Opções do desenvolvedor e use apenas uma rede Wi‑Fi de confiança.",
                        INK2, 16);
                espaco(28);
                botao("Fechar", true, v -> finish());
            });
        });
    }

    private void erro(String t, String detalhe, String code) {
        lastErrorCode = code;
        lastErrorStage = (String) body.getTag();
        fechar();
        limpar("erro");
        titulo(t);
        texto(detalhe, INK2, 16);
        espaco(12);
        texto("Código do Erro: " + code, INK3, 14);
        espaco(28);
        botao("🆘 Solicitar Ajuda com este erro", true, v -> solicitarAjuda(code, detalhe));
        botao("Voltar ao início", false, v -> inicio());
    }

    private void solicitarAjuda(String code, String context) {
        limpar("ajuda");
        titulo("Solicitar Ajuda");
        texto("Descreva o problema ou o que estava fazendo.", INK2, 16);
        espaco(16);

        final EditText input = new EditText(this);
        input.setHint("Ex: A tela ficou preta após instalar...");
        input.setHintTextColor(INK3);
        input.setTextColor(INK);
        input.setTextSize(16);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(caixa(false));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        body.addView(input, new LinearLayout.LayoutParams(-1, -2));

        espaco(16);
        if (!code.isEmpty()) {
            texto("Contexto técnico anexado automaticamente: " + code, INK3, 12);
        }
        espaco(24);

        botao("Enviar Relatório", true, v -> {
            String userMsg = input.getText().toString().trim();
            if (userMsg.isEmpty()) {
                aviso("Por favor, escreva uma mensagem.");
                return;
            }
            enviarRelatorio(userMsg, code, context);
        });
        botao("Cancelar", false, v -> {
            // Volta para onde estava
            if (lastErrorCode.isEmpty()) painelControle();
            else inicio();
        });
    }

    private void enviarRelatorio(String userMsg, String code, String context) {
        limpar("enviando");
        titulo("Enviando…");
        carregando();
        worker.execute(() -> {
            try {
                // Monta o payload JSON
                String json = String.format(
                    "{\"model\":\"%s\",\"android\":\"%s\",\"error_code\":\"%s\",\"stage\":\"%s\",\"user_msg\":\"%s\",\"context\":\"%s\"}",
                    info != null ? info.model : "unknown",
                    info != null ? info.android : "unknown",
                    code,
                    lastErrorStage,
                    userMsg.replace("\"", "\\\""),
                    context.replace("\"", "\\\"").replace("\n", " ")
                );

                // Envia para o endpoint (simulado aqui com log, mas seria HTTP POST)
                // Em produção, isso iria para um Firebase, Supabase ou API própria
                Log.d(TAG, "RELATORIO AJUDA: " + json);

                // Simulação de envio HTTP
                URL url = new URL("https://httpbin.org/post"); // Placeholder
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int resp = conn.getResponseCode();

                runOnUiThread(() -> {
                    limpar("enviado");
                    titulo("Mensagem Enviada");
                    texto("Recebemos seu pedido de ajuda com o código " + (code.isEmpty() ? "INFO" : code) + ".", INK, 18);
                    texto("Responderemos em breve ou atualizaremos o app.", INK2, 16);
                    espaco(28);
                    botao("Fechar", true, v -> finish());
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    aviso("Falha ao enviar. Verifique sua internet.");
                    // Volta para tela de ajuda
                    solicitarAjuda(code, context);
                });
            }
        });
    }

    // ---- Utilitários de UI e Rede ----

    private void comoLigarAdb() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Depuração USB")
                .setMessage("1. Configurações → Sobre → Build (clique 7 vezes).\n2. Opções do Desenvolvedor → Depuração USB.\nEm muitos projetores já vem ativado.")
                .setPositiveButton("Entendi", null)
                .show();
    }

    private String meuIpWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                    return la.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void fechar() {
        AdbClient c = adb;
        adb = null;
        if (c != null) c.close();
    }

    private void limpar(String tela) {
        body.removeAllViews();
        body.setTag(tela);
    }

    private void titulo(String s) {
        TextView t = texto(s, INK, 28);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setLetterSpacing(-0.02f);
        espaco(10);
    }

    private TextView texto(String s, int color, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.25f);
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private void passo(String n, String s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = new TextView(this);
        num.setText(n);
        num.setTextColor(INK3);
        num.setTypeface(Typeface.MONOSPACE);
        num.setGravity(Gravity.CENTER);
        num.setBackground(caixa(false));
        row.addView(num, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(INK);
        t.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(14);
        row.addView(t, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(12);
        body.addView(row, rp);
    }

    private void link(String s, View.OnClickListener l) {
        TextView t = texto(s, INK2, 15);
        t.getPaint().setUnderlineText(true);
        t.setPadding(dp(48), dp(4), 0, dp(4));
        t.setOnClickListener(l);
    }

    private void botao(String s, boolean primario, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primario ? PAPER : INK);
        b.setBackground(caixa(primario));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.bottomMargin = dp(10);
        body.addView(b, lp);
    }

    private void carregando() {
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(INK));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.gravity = Gravity.START;
        body.addView(p, lp);
    }

    private void espaco(int dpH) {
        body.addView(new View(this), new LinearLayout.LayoutParams(1, dp(dpH)));
    }

    private void rodape(String s) {
        View v = new View(this);
        body.addView(v, new LinearLayout.LayoutParams(1, 0, 1f));
        espaco(24);
        texto(s, INK3, 12);
    }

    private void aviso(String s) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(s).setPositiveButton("OK", null).show();
    }

    private GradientDrawable caixa(boolean cheia) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(4));
        g.setColor(cheia ? INK : Color.TRANSPARENT);
        g.setStroke(dp(1), cheia ? INK : LINE);
        return g;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}