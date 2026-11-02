package com.mlab.projetor;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

/**
 * TV ao vivo: canais abertos do Brasil (iptv-org) em tela cheia.
 * Cima/baixo troca de canal; OK ou esquerda abre a lista; voltar fecha a lista ou sai.
 */
public class TvActivity extends Activity {
    static final String EXTRA_CHANNEL = "canal";
    static volatile boolean visible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Iptv.Channel> channels = new ArrayList<>();
    /** O que a lista mostra: todos os canais ou so os da busca. */
    private final List<Iptv.Channel> shown = new ArrayList<>();
    private android.widget.EditText busca;
    private int versaoListas;
    private VideoView video;
    private LinearLayout panel, pill;
    private ListView list;
    private TextView pillName, pillInfo, status;
    private ProgressBar spinner;
    private int current = -1;
    private SharedPreferences prefs;

    private final Runnable hidePill = () -> pill.animate().alpha(0f).setDuration(300).start();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("projetor", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setOnPreparedListener(mp -> {
            spinner.setVisibility(View.GONE);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            spinner.setVisibility(View.GONE);
            showPill(currentName(), "Canal indisponível agora · escolha outro");
            showList();
            return true;
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                spinner.setVisibility(View.GONE);
            }
            return false;
        });

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(44), Ui.dp(44), Gravity.CENTER));

        // Pilula com o canal atual (topo, some sozinha).
        pill = Ui.col(this);
        pill.setPadding(Ui.dp(22), Ui.dp(14), Ui.dp(26), Ui.dp(14));
        GradientDrawable pbg = Ui.round(0xE62C2C2E, 22);
        pbg.setStroke(Ui.dp(1), 0x26FFFFFF);
        pill.setBackground(pbg);
        pillName = Ui.text(this, "", 22, Ui.w700, 0xFFFFFFFF);
        pillInfo = Ui.text(this, "", 13, Ui.w500, 0xFFB0B0B5);
        pill.addView(pillName);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = Ui.dp(4);
        pill.addView(pillInfo, ilp);
        pill.setAlpha(0f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        plp.leftMargin = plp.topMargin = Ui.dp(40);
        root.addView(pill, plp);

        // Painel de canais (vidro escuro a esquerda).
        panel = Ui.col(this);
        panel.setPadding(Ui.dp(24), Ui.dp(28), Ui.dp(16), Ui.dp(20));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF0101014, 0xD8101014});
        panel.setBackground(bg);
        TextView title = Ui.text(this, "TV ao vivo", 26, Ui.w700, 0xFFFFFFFF);
        panel.addView(title);
        status = Ui.text(this, "Carregando canais…", 13, Ui.w500, 0xFF98989D);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.dp(6);
        slp.bottomMargin = Ui.dp(14);
        panel.addView(status, slp);

        LinearLayout barra = Ui.row(this);
        busca = new android.widget.EditText(this);
        busca.setHint("Buscar canal");
        busca.setHintTextColor(0xFF98989D);
        busca.setTextColor(0xFFFFFFFF);
        busca.setTextSize(15);
        busca.setSingleLine(true);
        busca.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        GradientDrawable bbg = Ui.round(0x26FFFFFF, 12);
        busca.setBackground(bbg);
        busca.setOnFocusChangeListener((v, has) -> bbg.setStroke(has ? Ui.dp(2) : 0, 0xFFFFFFFF));
        busca.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                filtrar(e.toString());
            }
        });
        barra.addView(busca, new LinearLayout.LayoutParams(0, Ui.dp(44), 1f));
        LinearLayout listas = Ui.row(this);
        listas.setGravity(Gravity.CENTER);
        GradientDrawable lbg = Ui.round(0x26FFFFFF, 12);
        listas.setBackground(lbg);
        listas.addView(Ui.text(this, "Listas", 14, Ui.w600, 0xFFFFFFFF));
        listas.setFocusable(true);
        listas.setClickable(true);
        listas.setOnFocusChangeListener((v, has) -> lbg.setStroke(has ? Ui.dp(2) : 0, 0xFFFFFFFF));
        listas.setOnClickListener(v -> menuListas());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(84), Ui.dp(44));
        llp.leftMargin = Ui.dp(8);
        barra.addView(listas, llp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.bottomMargin = Ui.dp(12);
        panel.addView(barra, blp);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(Ui.round(0x332C2C2E, 14));
        list.setDrawSelectorOnTop(false);
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            play(shown.get(pos).pos);
            handler.postDelayed(this::hideList, 900);
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(panel, new FrameLayout.LayoutParams(Ui.dp(440), -1, Gravity.START));

        setContentView(root);
        loadChannels();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = Ui.row(TvActivity.this);
                row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
                TextView num = Ui.text(TvActivity.this, "", 13, Ui.w600, 0xFF98989D);
                num.setMinWidth(Ui.dp(34));
                row.addView(num);
                LinearLayout txt = Ui.col(TvActivity.this);
                TextView name = Ui.text(TvActivity.this, "", 16, Ui.w600, 0xFFFFFFFF);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                TextView group = Ui.text(TvActivity.this, "", 12, Ui.w400, 0xFF98989D);
                txt.addView(name);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
                glp.topMargin = Ui.dp(2);
                txt.addView(group, glp);
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            Iptv.Channel ch = shown.get(i);
            ((TextView) row.getChildAt(0)).setText(String.valueOf(ch.pos + 1));
            LinearLayout txt = (LinearLayout) row.getChildAt(1);
            TextView name = (TextView) txt.getChildAt(0);
            name.setText(ch.name);
            name.setTextColor(ch.pos == current ? Ui.ACCENT_DARK : 0xFFFFFFFF);
            TextView group = (TextView) txt.getChildAt(1);
            group.setText(ch.group);
            group.setVisibility(ch.group.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    };

    /** Mostra so os canais com esse nome; vazio mostra todos. */
    private void filtrar(String q) {
        String want = Apps.plain(q).replace(" ", "");
        shown.clear();
        for (Iptv.Channel ch : channels) {
            if (want.isEmpty() || Apps.plain(ch.name).replace(" ", "").contains(want)) shown.add(ch);
        }
        adapter.notifyDataSetChanged();
        if (!channels.isEmpty()) {
            status.setText(want.isEmpty() ? resumo() : shown.isEmpty() ? "Nenhum canal com esse nome"
                    : shown.size() + (shown.size() == 1 ? " canal" : " canais"));
        }
    }

    private String resumo() {
        return channels.size() + " canais" + (Iptv.listas(this).isEmpty() ? " abertos · iptv-org" : "");
    }

    // ---- Listas de canais adicionadas pela pessoa ----

    private android.app.AlertDialog.Builder dialog() {
        return new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void menuListas() {
        final List<String> l = Iptv.listas(this);
        String[] itens = new String[l.size() + 1];
        itens[0] = "Adicionar lista";
        for (int i = 0; i < l.size(); i++) itens[i + 1] = "Remover: " + l.get(i);
        dialog().setTitle("Listas de canais").setItems(itens, (d, w) -> {
            if (w == 0) {
                novaLista();
            } else {
                Iptv.remover(this, l.get(w - 1));
                recarregar();
            }
        }).show();
    }

    private void novaLista() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("http://…");
        in.setSingleLine(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), 0);
        box.addView(in);
        dialog().setTitle("Adicionar lista")
                .setMessage("Cole o link de uma lista M3U, até 10 MB. Use só listas que você tem direito de assistir. "
                        + "Dá para digitar pelo teclado do controle do celular.")
                .setView(box)
                .setPositiveButton("Adicionar", (x, y) -> {
                    final String url = in.getText().toString();
                    status.setText("Baixando a lista…");
                    new Thread(() -> {
                        final String erro = Iptv.adicionar(this, url);
                        handler.post(() -> {
                            if (isDestroyed()) return;
                            App.inst.controller.hud.message(erro != null ? erro : "Lista adicionada");
                            if (erro == null) recarregar();
                            else status.setText(resumo());
                        });
                    }, "iptv-lista").start();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    /** Lista mudou: recarrega sem trocar o canal que esta passando. */
    private void recarregar() {
        versaoListas = Iptv.versao;
        new Thread(() -> {
            final List<Iptv.Channel> novos = Iptv.load(this);
            handler.post(() -> {
                if (isDestroyed()) return;
                String tocando = current >= 0 && current < channels.size() ? channels.get(current).url : null;
                channels.clear();
                channels.addAll(novos);
                current = -1;
                for (Iptv.Channel ch : channels) if (ch.url.equals(tocando)) current = ch.pos;
                filtrar(busca.getText().toString());
            });
        }, "iptv").start();
    }

    private void loadChannels() {
        versaoListas = Iptv.versao;
        new Thread(() -> {
            List<Iptv.Channel> loaded = Iptv.load(this);
            handler.post(() -> {
                channels.clear();
                channels.addAll(loaded);
                filtrar(busca.getText().toString());
                if (channels.isEmpty()) {
                    status.setText("Não foi possível carregar os canais. Verifique a internet.");
                    spinner.setVisibility(View.GONE);
                    return;
                }
                status.setText(resumo());
                int start = find(getIntent().getStringExtra(EXTRA_CHANNEL));
                if (start < 0) start = Math.min(prefs.getInt("tv_canal", -1), channels.size() - 1);
                if (start >= 0) {
                    play(start);
                    hideList();
                } else {
                    spinner.setVisibility(View.GONE);
                    showList();
                }
            });
        }, "iptv").start();
    }

    /** Procura um canal pelo nome falado ("globo", "cultura"). */
    private int find(String q) {
        if (q == null || q.trim().isEmpty()) return -1;
        String want = Apps.plain(q).replace(" ", "");
        for (int i = 0; i < channels.size(); i++) {
            if (Apps.plain(channels.get(i).name).replace(" ", "").startsWith(want)) return i;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (Apps.plain(channels.get(i).name).replace(" ", "").contains(want)) return i;
        }
        return -1;
    }

    private void play(int i) {
        if (channels.isEmpty()) return;
        i = (i + channels.size()) % channels.size();
        current = i;
        prefs.edit().putInt("tv_canal", i).apply();
        Iptv.Channel ch = channels.get(i);
        spinner.setVisibility(View.VISIBLE);
        video.setVideoURI(Uri.parse(ch.url));
        video.start();
        adapter.notifyDataSetChanged();
        if (busca.getText().length() == 0) list.setSelection(Math.max(0, i - 3));
        showPill(ch.name, (i + 1) + (ch.group.isEmpty() ? "" : " · " + ch.group));
        App.inst.controller.onScreen(getPackageName(), ch.name);
        App.inst.controller.watch(Apps.TV_PKG, ch.name);
    }

    private String currentName() {
        return current >= 0 && current < channels.size() ? channels.get(current).name : "TV ao vivo";
    }

    private void showPill(String name, String info) {
        pillName.setText(name);
        pillInfo.setText(info);
        pill.animate().cancel();
        pill.setAlpha(1f);
        handler.removeCallbacks(hidePill);
        handler.postDelayed(hidePill, 3500);
    }

    private boolean listVisible() {
        return panel.getVisibility() == View.VISIBLE;
    }

    private void showList() {
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(180).start();
        list.requestFocus();
        if (current >= 0 && busca.getText().length() == 0) list.setSelection(current);
    }

    private void hideList() {
        if (current < 0) return;
        panel.animate().alpha(0f).setDuration(180).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        video.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (listVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK && current >= 0) {
                hideList();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && current >= 0 && list.hasFocus()) {
                hideList();
                return true;
            }
            // A lista pode ser longa demais para subir ate a busca.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && list.hasFocus()) {
                busca.requestFocus();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                play(current + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                play(current - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MENU:
                showList();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        App.inst.controller.onScreen(getPackageName(), currentName());
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        video.stopPlayback();
        App.inst.controller.stopWatch();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int i = find(intent.getStringExtra(EXTRA_CHANNEL));
        if (i >= 0) {
            play(i);
            hideList();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (versaoListas != Iptv.versao) recarregar();
        if (current >= 0) play(current);
    }
}
