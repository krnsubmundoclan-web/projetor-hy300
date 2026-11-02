package com.mlab.projetor;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Tela inicial inspirada no iPhone/macOS: relogio grande da tela de bloqueio, favoritos
 * como artes horizontais dos apps e um Dock translucido com os ajustes do projetor.
 */
public class HomeActivity extends Activity {
    private static final int COLS = 4;
    private static final int ROWS = 4, ROW_GAP_DP = 14;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView selKind, selName;
    private UsageRing temperatureUsage, cpuUsage, ramUsage, storageUsage, internetUsage;
    private View hardwarePanel;
    private final HardwareUsage hardware = new HardwareUsage();
    private final Runnable hardwareTick = new Runnable() {
        @Override public void run() {
            updateHardware();
            handler.postDelayed(this, 3_000);
        }
    };
    private LinearLayout grid;
    /** Itens do Dock, para ligar a seta para baixo dos cartoes ao Dock (o FocusFinder nao acha sozinho). */
    private final List<View> dockItems = new ArrayList<>();

    private static HomeActivity current;

    /** Versao nova encontrada em segundo plano: pergunta uma vez por versao, se a tela inicial estiver aberta. */
    static void offerUpdate(Updater.Info info) {
        HomeActivity h = current;
        if (h == null || h.isFinishing() || Prefs.updateDismissed(h) >= info.code) return;
        h.runOnUiThread(() -> h.confirmUpdate(info, true));
    }

    private void confirmUpdate(Updater.Info info, boolean automatic) {
        String size = info.size > 0 ? " (" + Math.max(1, info.size / (1024 * 1024)) + " MB)" : "";
        dialog().setTitle("Nova versão do Projetor")
                .setMessage("A versão " + info.name + size + " está disponível. Seus ajustes continuam iguais.")
                .setPositiveButton("Atualizar", (x, y) -> {
                    App.inst.controller.hud.message("Baixando a atualização…");
                    Updater.install(this, info, (i, err) -> App.inst.controller.hud.message(err));
                })
                .setNegativeButton("Depois", (x, y) -> {
                    if (automatic) Prefs.setUpdateDismissed(this, info.code);
                })
                .show();
    }

    private void procurarAtualizacao() {
        App.inst.controller.hud.message("Procurando atualização…");
        Updater.check(this, (info, err) -> {
            if (err != null) App.inst.controller.hud.message(err);
            else if (info == null) App.inst.controller.hud.message("Você já tem a versão mais nova");
            else confirmUpdate(info, false);
        });
    }

    /** Troca de tema vinda de fora (voz): redesenha a tela inicial se estiver aberta. */
    static void refreshTheme() {
        HomeActivity h = current;
        if (h != null) h.runOnUiThread(h::recreate);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle b) {
        current = this;
        super.onCreate(b);
        // O Android repoe o idioma do firmware ao abrir a tela; o relogio usa o idioma padrao.
        java.util.Locale.setDefault(new java.util.Locale("pt", "BR"));
        setContentView(build());
        App.inst.startKeepAlive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        java.util.Locale.setDefault(new java.util.Locale("pt", "BR"));
        loadFavorites();
        applyHardwareVisibility();
        refreshInternet(null);
        handler.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hardwareTick);
        handler.removeCallbacks(statusTick);
        hardware.resetCpu();
    }

    private View build() {
        LinearLayout root = Ui.col(this);
        root.setBackground(Ui.wallpaper(this));
        root.setPadding(Ui.dp(56), Ui.dp(26), Ui.dp(56), Ui.dp(22));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        root.addView(topBar(), new LinearLayout.LayoutParams(-1, Ui.dp(26)));

        LinearLayout main = Ui.row(this);
        main.setGravity(Gravity.TOP);
        main.setClipChildren(false);
        main.addView(clockColumn(), new LinearLayout.LayoutParams(Ui.dp(330), -1));
        grid = Ui.col(this);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setGravity(Gravity.CENTER_VERTICAL);
        grid.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        grid.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL || b - t != oldB - oldT) sizeFavoriteRows();
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, -1, 1f);
        glp.leftMargin = Ui.dp(36);
        main.addView(grid, glp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        mlp.topMargin = Ui.dp(24);
        root.addView(main, mlp);

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, Ui.dp(84));
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.topMargin = Ui.dp(22);
        root.addView(dock(), dlp);

        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(false);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    // ---- Barra superior (estilo barra de menus do macOS) ----

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.text(this, "M SOLUCTION", 15, Ui.w600, Ui.TEXT));
        String installedVersion = installedVersion();
        if (!installedVersion.isEmpty()) {
            TextView version = Ui.text(this, "v" + installedVersion, 12, Ui.w500, Ui.MUTED);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = Ui.dp(8);
            bar.addView(version, vlp);
        }
        bar.addView(Ui.space(this, 1));
        statusBluetooth = status(bar, R.drawable.ic_bt);
        statusTransmitir = status(bar, R.drawable.ic_transmitir);
        statusControle = status(bar, R.drawable.ic_phone);
        ImageView wifi = Ui.icon(this, R.drawable.ic_wifi, 18, Ui.TEXT);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
        wlp.leftMargin = Ui.dp(16);
        bar.addView(wifi, wlp);
        return bar;
    }

    private ImageView statusBluetooth, statusTransmitir, statusControle;

    private ImageView status(LinearLayout bar, int icone) {
        ImageView v = Ui.icon(this, icone, 18, Ui.TEXT);
        v.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
        lp.leftMargin = Ui.dp(14);
        bar.addView(v, lp);
        return v;
    }

    /** Icones do topo: so aparecem enquanto o Bluetooth, a transmissao ou o controle estao em uso. */
    private void updateStatus() {
        statusBluetooth.setVisibility(Sistema.bluetoothConectado() ? View.VISIBLE : View.GONE);
        statusTransmitir.setVisibility(Sistema.transmitindo(this) ? View.VISIBLE : View.GONE);
        statusControle.setVisibility(App.inst.server.conectados().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 3000);
        }
    };

    private String installedVersion() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---- Coluna do relogio (estilo tela de bloqueio do iPhone) ----

    private View clockColumn() {
        LinearLayout c = Ui.col(this);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextSize(19);
        date.setTypeface(Ui.w500);
        date.setTextColor(Ui.MUTED);
        date.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ui.dp(10);
        dlp.leftMargin = Ui.dp(4);
        c.addView(date, dlp);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(112);
        time.setTypeface(Ui.w300);
        time.setTextColor(Ui.TEXT);
        time.setLetterSpacing(-0.04f);
        time.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(6);
        c.addView(time, tlp);

        c.addView(Ui.vspace(this, 1));
        selKind = Ui.text(this, "", 14, Ui.w600, Ui.ACCENT);
        c.addView(selKind);
        selName = Ui.text(this, "", 34, Ui.w700, Ui.TEXT);
        selName.setSingleLine(true);
        selName.setEllipsize(TextUtils.TruncateAt.END);
        selName.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(6);
        c.addView(selName, nlp);
        LinearLayout meters = Ui.row(this);
        temperatureUsage = new UsageRing(this, "Temp.");
        temperatureUsage.setTemperature(-1);
        cpuUsage = new UsageRing(this, "CPU");
        ramUsage = new UsageRing(this, "RAM");
        storageUsage = new UsageRing(this, "Espaço");
        internetUsage = new UsageRing(this, "Internet");
        internetUsage.setSpeed(Prefs.downloadKbps(this), Prefs.downloadCheckedAt(this));
        for (UsageRing ring : new UsageRing[]{temperatureUsage, cpuUsage, ramUsage, storageUsage, internetUsage}) {
            meters.addView(ring, new LinearLayout.LayoutParams(0, Ui.dp(66), 1f));
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(30);
        c.addView(meters, mlp);
        hardwarePanel = meters;
        return c;
    }

    // ---- Favoritos: artes horizontais, 4 colunas x 2 linhas ----

    private void loadFavorites() {
        grid.removeAllViews();
        List<Apps.Entry> favs = Apps.favorites(this);
        View first = null;
        // So as linhas em uso; o "+" ocupa a vaga seguinte e some com a grade cheia.
        int total = Math.min(ROWS * COLS, favs.size() + 1);
        int rows = (total + COLS - 1) / COLS;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = Ui.row(this);
            row.setClipChildren(false);
            for (int col = 0; col < COLS; col++) {
                int i = r * COLS + col;
                View v;
                if (i < favs.size() && i < ROWS * COLS) {
                    v = tile(favs.get(i));
                    if (first == null) first = v;
                } else if (i == favs.size()) {
                    v = addTile();
                } else {
                    v = new View(this);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (col > 0) lp.leftMargin = Ui.dp(14);
                row.addView(v, lp);
                // Sem nada abaixo, a seta desce para o item do Dock mais proximo.
                if (v.isFocusable() && i + COLS >= total && !dockItems.isEmpty()) {
                    if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
                    View target = dockItems.get(Math.min(dockItems.size() - 1, col * 2 + 1));
                    v.setNextFocusDownId(target.getId());
                }
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(60));
            if (r > 0) rlp.topMargin = Ui.dp(ROW_GAP_DP);
            grid.addView(row, rlp);
        }
        sizeFavoriteRows();
        if (favs.isEmpty()) {
            selKind.setText("Bem-vindo");
            selName.setText("Escolha seus apps");
        }
        if (first != null && getCurrentFocus() == null) first.requestFocus();
    }

    /** Proporcao de banner, reduzida se preciso para as 4 linhas caberem acima do Dock. */
    private void sizeFavoriteRows() {
        int width = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        if (width <= Ui.dp(14) * (COLS - 1)) return;
        int height = Math.round((width - Ui.dp(14) * (COLS - 1)) / (float) COLS * 9f / 16f);
        int area = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom() - Ui.dp(ROW_GAP_DP) * (ROWS - 1);
        if (area > 0) height = Math.min(height, area / ROWS);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View row = grid.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (lp.height == height) continue;
            lp.height = height;
            row.setLayoutParams(lp);
        }
    }

    private View tile(final Apps.Entry a) {
        FrameLayout t = new FrameLayout(this);
        t.setContentDescription(a.label);
        Drawable banner = Apps.banner(this, a.pkg);
        t.setBackground(Ui.round(banner != null ? 0xFF15161C : Ui.corDaLogo(a.pkg, a.icon), 12));
        t.setClipToOutline(true);
        ImageView art = new ImageView(this);
        if (banner != null) {
            art.setImageDrawable(banner);
            art.setScaleType(ImageView.ScaleType.FIT_XY);
        } else {
            art.setImageDrawable(a.icon);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        }
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.addView(art, new FrameLayout.LayoutParams(-1, -1));
        Ui.focusableIcon(t, () -> {
            selKind.setText(Apps.kind(a.pkg));
            selName.setText(a.label);
            App.inst.controller.onScreen(getPackageName(), a.label);
        });
        t.setOnClickListener(v -> open(Apps.launchIntent(this, a.pkg)));
        t.setOnLongClickListener(v -> {
            Prefs.toggleFav(this, a.pkg);
            App.inst.controller.hud.message(a.label + " removido dos favoritos");
            loadFavorites();
            return true;
        });
        return t;
    }

    private Intent escolherApps() {
        return new Intent(this, AppsActivity.class).putExtra(AppsActivity.EXTRA_ESCOLHER, true);
    }

    private View addTile() {
        LinearLayout t = Ui.col(this);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.glass(12);
        t.setBackground(bg);
        t.addView(Ui.icon(this, R.drawable.ic_plus, 26, Ui.TEXT));
        TextView addLabel = Ui.text(this, "Adicionar à tela inicial", 11, Ui.w500, Ui.MUTED);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-2, -2);
        addLp.topMargin = Ui.dp(4);
        t.addView(addLabel, addLp);
        t.setContentDescription("Adicionar à tela inicial");
        Ui.focusableIcon(t, () -> {
            selKind.setText("Tela inicial");
            selName.setText("Adicionar app");
        });
        t.setOnClickListener(v -> open(escolherApps()));
        return t;
    }

    // ---- Dock (estilo macOS, adaptativo por chipset) ----

    private View dock() {
        LinearLayout d = Ui.row(this);
        d.setBackground(Ui.glass(26));
        d.setElevation(Ui.dp(6));
        d.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        d.setClipChildren(false);

        // Detecta hardware e carrega intents compatíveis
        Hardware.Chipset chipset = Hardware.detectChipset();

        // Keystone: tenta pacotes específicos do chipset
        Intent keystoneIntent = Hardware.findAvailable(this, Hardware.getKeystoneIntents(chipset));
        if (keystoneIntent != null) {
            dockItem(d, "Projeção", R.drawable.ic_keystone, keystoneIntent);
        }

        // HDMI/Input: tenta pacotes específicos do chipset
        Intent hdmiIntent = Hardware.findAvailable(this, Hardware.getHdmiIntents(chipset));
        if (hdmiIntent != null) {
            dockItem(d, "HDMI", R.drawable.ic_hdmi, hdmiIntent);
        }

        // Itens universais (sempre disponíveis)
        dockItem(d, "Wi‑Fi", R.drawable.ic_wifi, new Intent(Settings.ACTION_WIFI_SETTINGS));
        dockItem(d, "Bluetooth", R.drawable.ic_bt, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        dockItem(d, "Ajustes", R.drawable.ic_settings, null).setOnClickListener(v -> settingsMenu());

        View sep = new View(this);
        sep.setBackgroundColor(Ui.dark ? 0x33FFFFFF : 0x221D1D1F);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(1), Ui.dp(52));
        slp.leftMargin = slp.rightMargin = Ui.dp(8);
        d.addView(sep, slp);

        dockItem(d, "Apps", R.drawable.ic_apps, new Intent(this, AppsActivity.class));
        dockItem(d, "Abertos", R.drawable.ic_abertos, null).setOnClickListener(v -> App.inst.controller.abrirAbertos());
        dockItem(d, "Controle", R.drawable.ic_phone, new Intent(this, ControleActivity.class));
        if (!Hardware.receptores(this).isEmpty()) {
            dockItem(d, "Transmitir", R.drawable.ic_transmitir, null).setOnClickListener(v -> transmitir());
        }
        dockItem(d, "Personalizar", R.drawable.ic_personalizar, new Intent(this, PersonalizarActivity.class));
        return d;
    }

    private boolean available(Intent i) {
        return getPackageManager().resolveActivity(i, 0) != null;
    }

    /** Item do Dock; atalhos de apps do fabricante que nao existem neste aparelho ficam de fora. */
    private View dockItem(LinearLayout dock, String label, int icon, final Intent intent) {
        LinearLayout it = Ui.col(this);
        if (intent != null && !available(intent)) return it;
        it.setContentDescription(label);
        it.setId(View.generateViewId());
        dockItems.add(it);
        it.setGravity(Gravity.CENTER);
        GradientDrawable bg = Ui.round(0x00000000, 16);
        it.setBackground(bg);
        it.addView(Ui.icon(this, icon, 26, Ui.TEXT));
        TextView t = Ui.text(this, label, 11, Ui.w500, Ui.MUTED);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(6);
        it.addView(t, tlp);
        it.setFocusable(true);
        it.setClickable(true);
        it.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Ui.FOCUS_BG : 0x00000000);
            t.setTextColor(has ? Ui.TEXT : Ui.MUTED);
            v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f).translationY(has ? -Ui.dp(4) : 0)
                    .setDuration(160).start();
            v.setElevation(has ? Ui.dp(8) : 0);
            if (has) App.inst.controller.onScreen(getPackageName(), label);
        });
        if (intent != null) it.setOnClickListener(v -> open(intent));
        dock.addView(it, new LinearLayout.LayoutParams(Ui.dp(76), Ui.dp(68)));
        return it;
    }

    // ---- Ajustes: os do Android e os do proprio app ----

    private android.app.AlertDialog.Builder dialog() {
        return new android.app.AlertDialog.Builder(this, Ui.dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private void settingsMenu() {
        String[] items = {"Configurações", "Testar velocidade da internet", "Procurar atualização", "Resetar ao original de fábrica"};
        dialog().setTitle("Ajustes").setItems(items, (dlg, which) -> {
            if (which == 0) {
                Intent tv = comp("com.android.tv.settings", "com.android.tv.settings.MainSettings");
                open(available(tv) ? tv : new Intent(Settings.ACTION_SETTINGS));
            } else if (which == 1) {
                testInternet();
            } else if (which == 2) {
                procurarAtualizacao();
            } else {
                dialog().setTitle("Resetar ao original de fábrica?")
                        .setMessage("A tela inicial e o modo mouse de fábrica voltam, e este app é apagado do projetor, "
                                + "com o PIN, a foto de fundo e os aparelhos salvos. Seus outros apps e arquivos não são apagados.")
                        .setPositiveButton("Resetar e apagar", (x, y) -> Sistema.restoreAndUninstall(this))
                        .setNegativeButton("Cancelar", null).show();
            }
        }).show();
    }

    /** Abre o app de receber transmissao; com mais de um, a pessoa escolhe pelo aparelho dela. */
    private void transmitir() {
        final java.util.Map<String, Intent> r = Hardware.receptores(this);
        if (r.size() == 1) {
            open(r.values().iterator().next());
            return;
        }
        final String[] nomes = r.keySet().toArray(new String[0]);
        dialog().setTitle("Transmitir de qual aparelho?").setItems(nomes, (d, w) -> open(r.get(nomes[w]))).show();
    }

    private static Intent comp(String pkg, String cls) {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls));
    }

    private void open(Intent i) {
        if (i == null) return;
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            App.inst.controller.hud.message("Não foi possível abrir");
        }
    }


    private void updateHardware() {
        temperatureUsage.setTemperature(Temperature.readHighest());
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        cpuUsage.setUsage(hardware.readCpu());
        ramUsage.setUsage(HardwareUsage.percent(mi.availMem, mi.totalMem));
        int storagePercent = -1;
        try {
            StatFs storage = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            storagePercent = HardwareUsage.percent(storage.getAvailableBytes(), storage.getTotalBytes());
        } catch (IllegalArgumentException e) {
            android.util.Log.w("Projetor", "Armazenamento indisponível", e);
        }
        storageUsage.setUsage(storagePercent);
    }

    private void applyHardwareVisibility() {
        handler.removeCallbacks(hardwareTick);
        hardware.resetCpu();
        boolean show = Prefs.showHardware(this);
        hardwarePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        temperatureUsage.setVisibility(Prefs.showRing(this, "temp") ? View.VISIBLE : View.GONE);
        cpuUsage.setVisibility(Prefs.showRing(this, "cpu") ? View.VISIBLE : View.GONE);
        ramUsage.setVisibility(Prefs.showRing(this, "ram") ? View.VISIBLE : View.GONE);
        storageUsage.setVisibility(Prefs.showRing(this, "espaco") ? View.VISIBLE : View.GONE);
        internetUsage.setVisibility(Prefs.showRing(this, "internet") ? View.VISIBLE : View.GONE);
        if (show) {
            cpuUsage.setUsage(-1);
            handler.post(hardwareTick);
            if (Prefs.showRing(this, "internet")) App.inst.testInternet(false);
            else App.inst.cancelInternetTest();
        } else {
            App.inst.cancelInternetTest();
        }
    }

    private void testInternet() {
        if (!Prefs.showHardware(this)) {
            App.inst.controller.hud.message("Ligue um indicador em Personalizar para testar a internet.");
            return;
        }
        if (App.inst.testingInternet) {
            App.inst.controller.hud.message("Teste de internet em andamento…");
            return;
        }
        String last = Prefs.downloadKbps(this) < 0 ? "Nenhum teste realizado."
                : "Último teste: " + UsageRing.speedText(Prefs.downloadKbps(this)) + " Mbps em "
                        + Prefs.downloadCheckedAt(this) + ".";
        dialog().setTitle("Testar velocidade da internet")
                .setMessage(last + "\n\nO teste estima o download de 20MB pela Cloudflare. "
                        + "Pode afetar vídeos em reprodução.")
                .setPositiveButton("Testar", (d, w) -> App.inst.testInternet(true))
                .setNegativeButton("Cancelar", null).show();
    }

    static void refreshInternet(String message) {
        HomeActivity h = current;
        if (h == null || h.isDestroyed() || h.isFinishing()) return;
        h.runOnUiThread(() -> {
            if (App.inst.testingInternet) h.internetUsage.setLoading();
            else h.internetUsage.setSpeed(Prefs.downloadKbps(h), Prefs.downloadCheckedAt(h));
            if (message != null && h.hasWindowFocus()) h.dialog().setTitle("Velocidade da internet")
                    .setMessage(message).setPositiveButton("OK", null).show();
        });
    }
}
