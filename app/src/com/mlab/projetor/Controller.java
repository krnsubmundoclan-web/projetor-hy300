package com.mlab.projetor;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executa os comandos vindos do celular. Teclas e toques passam pela Entrada, que escolhe o
 * caminho disponivel neste aparelho, sempre fora da main thread.
 */
final class Controller {
    private static final String TAG = "Projetor";

    private static final Map<String, Integer> KEYS = new HashMap<>();
    private static final Map<String, String[]> ALIASES = new HashMap<>();

    static {
        KEYS.put("up", KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("left", KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);
        KEYS.put("enter", KeyEvent.KEYCODE_ENTER);
        KEYS.put("back", KeyEvent.KEYCODE_BACK);
        KEYS.put("home", KeyEvent.KEYCODE_HOME);
        KEYS.put("menu", KeyEvent.KEYCODE_MENU);
        KEYS.put("del", KeyEvent.KEYCODE_DEL);
        KEYS.put("search", KeyEvent.KEYCODE_SEARCH);
        KEYS.put("playpause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KEYS.put("play", KeyEvent.KEYCODE_MEDIA_PLAY);
        KEYS.put("pause", KeyEvent.KEYCODE_MEDIA_PAUSE);
        KEYS.put("next", KeyEvent.KEYCODE_MEDIA_NEXT);
        KEYS.put("prev", KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        KEYS.put("fwd", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        KEYS.put("rew", KeyEvent.KEYCODE_MEDIA_REWIND);
        KEYS.put("power", KeyEvent.KEYCODE_POWER);
        KEYS.put("sleep", KeyEvent.KEYCODE_SLEEP);
        KEYS.put("wake", KeyEvent.KEYCODE_WAKEUP);

        // Versao de TV primeiro; varios aparelhos so tem a de celular.
        ALIASES.put("youtube", new String[]{"com.google.android.youtube.tv", "com.google.android.youtube"});
        ALIASES.put("netflix", new String[]{"com.netflix.ninja", "com.netflix.mediaclient"});
        ALIASES.put("disney", new String[]{"com.disney.disneyplus"});
        ALIASES.put("prime", new String[]{"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"});
        ALIASES.put("globoplay", new String[]{"com.globo.globotv"});
        ALIASES.put("spotify", new String[]{"com.spotify.tv.android", "com.spotify.music"});
        ALIASES.put("espn", new String[]{"com.espn.score_center"});
        ALIASES.put("navegador", new String[]{"com.tcl.browser", "com.android.chrome"});
    }

    private final Context ctx;
    final Entrada entrada;
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final WindowManager wm;
    final Hud hud;
    private final int sw, sh;

    /**
     * Seta do touchpad: uma camada transparente da tela inteira (nao recebe toques) com a seta
     * dentro. Mover e so deslizar a seta (translation, feito pela GPU); reposicionar a janela a
     * cada quadro travava num aparelho de 1 GB.
     */
    private android.widget.FrameLayout cursorLayer;
    private CursorView cursor;
    private volatile boolean cursorVisible;
    private volatile float cx, cy;
    private final Runnable hideCursor = this::removeCursor;

    private void removeCursor() {
        stopJoystick();
        if (cursorLayer != null) {
            try {
                wm.removeView(cursorLayer);
            } catch (RuntimeException ignored) {
            }
            cursorLayer = null;
            cursor = null;
        }
        cursorVisible = false;
    }

    // Joystick do celular em modo cursor: o celular manda a velocidade (px/s) poucas vezes por
    // segundo e o projetor anima a seta a cada quadro da tela, independente da rede.
    private float joyVx, joyVy;
    private long joyLastMsg;
    private long lastFrameNanos;
    private boolean joyRunning;
    private final android.view.Choreographer.FrameCallback joyFrame = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!joyRunning) return;
            // Sem noticias do celular por 0,7 s (rede caiu, app fechou): para a seta.
            if (SystemClock.uptimeMillis() - joyLastMsg > 700) {
                stopJoystick();
                return;
            }
            float dt = lastFrameNanos == 0 ? 0 : Math.min(0.05f, (now - lastFrameNanos) / 1e9f);
            lastFrameNanos = now;
            if (dt > 0) applyMove(joyVx * dt, joyVy * dt);
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    Controller(Context c) {
        ctx = c.getApplicationContext();
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        hud = new Hud(ctx);
        entrada = new Entrada(ctx);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        cx = sw / 2f;
        cy = sh / 2f;
    }

    /** Mensagens curtas do WebSocket: k:tecla, m:dx,dy, c, lc, s:dy, t:texto, v:volume(0-100). */
    void command(String msg) {
        try {
            if (msg.startsWith("k:")) key(msg.substring(2));
            else if (msg.equals("cur:1")) main.post(() -> applyMove(0, 0));
            else if (msg.equals("cur:0")) main.post(() -> {
                main.removeCallbacks(hideCursor);
                removeCursor();
            });
            else if (msg.startsWith("m:")) {
                String[] p = msg.substring(2).split(",");
                move(Float.parseFloat(p[0]), Float.parseFloat(p[1]));
            } else if (msg.equals("c")) click(false);
            else if (msg.equals("lc")) click(true);
            else if (msg.startsWith("j:")) {
                String[] p = msg.substring(2).split(",");
                joystick(Float.parseFloat(p[0]), Float.parseFloat(p[1]));
            } else if (msg.startsWith("s:")) scroll(Float.parseFloat(msg.substring(2)));
            else if (msg.startsWith("t:")) text(msg.substring(2));
            else if (msg.startsWith("v:")) setVolume(Integer.parseInt(msg.substring(2).trim()));
        } catch (RuntimeException e) {
            Log.w(TAG, "comando invalido: " + msg, e);
        }
    }

    boolean key(String name) {
        if (name == null) return false;
        name = name.toLowerCase(Locale.ROOT).trim();
        switch (name) {
            case "vol_up":
            case "volup":
                volume("up");
                return true;
            case "vol_down":
            case "voldown":
                volume("down");
                return true;
            case "mute":
                volume("mute");
                return true;
            case "abertos":
                abrirAbertos();
                return true;
        }
        Integer code = KEYS.get(name);
        if (code == null) return false;
        final int k = code;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) onSelect();
        if (touchNav(k)) return true;
        queue.execute(() -> {
            if (!entrada.tecla(k)) avisarLimite();
        });
        return true;
    }

    /** Janela lateral de apps abertos: abre por cima do que estiver na tela; de novo, fecha. */
    void abrirAbertos() {
        main.post(() -> {
            AbertosActivity a = AbertosActivity.aberta;
            if (a != null) a.finish();
            else start(new Intent(ctx, AbertosActivity.class));
        });
    }

    private long lastLimitNotice;

    private void avisarLimite() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLimitNotice < 15_000) return;
        lastLimitNotice = now;
        hud.message("Controle limitado: veja Ajustes > Estado do controle");
    }

    /** App de celular que ignora setas (a Netflix do HY300): setas viram arrastos e OK vira toque. */
    private boolean touchNav(int k) {
        if (!TOUCH_NAV.equals(currentPkg())) return false;
        final float y = cursorVisible ? cy : sh / 2f, x = cursorVisible ? cx : sw / 2f;
        int v = Ui.dp(220), h = Ui.dp(300);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                drag(sw / 2f, sh / 2f, sw / 2f, sh / 2f + v);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                drag(sw / 2f, sh / 2f, sw / 2f, sh / 2f - v);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                drag(x, y, clamp(x + h, 1, sw - 2), y);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                drag(x, y, clamp(x - h, 1, sw - 2), y);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                click(false);
                return true;
            default:
                return false;
        }
    }

    private void drag(final float x0, final float y0, final float x1, final float y1) {
        queue.execute(() -> {
            if (!entrada.arrasto(x0, y0, x1, y1)) avisarLimite();
        });
    }

    // Volume vindo do celular: sem a barra do sistema (FLAG_SHOW_UI); quem mostra e o nosso aviso.
    void volume(String dir) {
        int adj = "up".equals(dir) ? AudioManager.ADJUST_RAISE
                : "down".equals(dir) ? AudioManager.ADJUST_LOWER
                : AudioManager.ADJUST_TOGGLE_MUTE;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adj, 0);
        showVolume();
    }

    void setVolume(int percent) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.max(0, Math.min(max, Math.round(percent / 100f * max)));
        if (v > 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        showVolume();
    }

    private void showVolume() {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        hud.volume(Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max),
                audio.isStreamMute(AudioManager.STREAM_MUSIC));
    }

    // ---- Touchpad: cursor desenhado por cima de tudo + toques injetados nessa posicao ----

    /** Quanto o cursor ja "empurrou" alem da borda de cima/baixo (vira rolagem da pagina). */
    private float edgePush;
    private long lastEdgeScroll;

    /** Velocidade do joystick em px/s; (0,0) para a seta. */
    void joystick(final float vx, final float vy) {
        main.post(() -> {
            joyLastMsg = SystemClock.uptimeMillis();
            joyVx = vx;
            joyVy = vy;
            if (vx == 0 && vy == 0) {
                stopJoystick();
                applyMove(0, 0);
                return;
            }
            if (!joyRunning) {
                joyRunning = true;
                lastFrameNanos = 0;
                android.view.Choreographer.getInstance().postFrameCallback(joyFrame);
            }
        });
    }

    private void stopJoystick() {
        joyRunning = false;
        joyVx = joyVy = 0;
        android.view.Choreographer.getInstance().removeFrameCallback(joyFrame);
    }

    void move(final float dx, final float dy) {
        main.post(() -> applyMove(dx, dy));
    }

    /** Move a seta (na thread principal) e trata a rolagem pela borda do navegador. */
    private void applyMove(float dx, float dy) {
        {
            if (!ensureCursor()) return;
            cx = clamp(cx + dx, 0, sw - 1);
            float ny = cy + dy;
            boolean atEdge = ny > sh - 1 || ny < 0;
            String prof = atEdge ? profile(currentPkg()) : "";
            if (atEdge && ("web".equals(prof) || "toque".equals(prof))) {
                // Cursor encostado na borda (so em navegador): empurrar com vontade rola a pagina,
                // no maximo uma vez a cada 0,7 s, para nao "descer sem parar".
                edgePush += ny > sh - 1 ? ny - (sh - 1) : ny;
                long now = SystemClock.uptimeMillis();
                if (Math.abs(edgePush) >= Ui.dp(60) && now - lastEdgeScroll > 700) {
                    scrollFrom(cx, sh / 2f, edgePush > 0 ? -Ui.dp(200) : Ui.dp(200));
                    lastEdgeScroll = now;
                    edgePush = 0;
                }
            } else {
                edgePush = 0;
            }
            cy = clamp(ny, 0, sh - 1);
            cursor.setTranslationX(cx);
            cursor.setTranslationY(cy);
            cursorVisible = true;
            main.removeCallbacks(hideCursor);
            main.postDelayed(hideCursor, CURSOR_MS);
        }
    }

    void click(final boolean longPress) {
        if (!cursorVisible) {
            String prof = profile(currentPkg());
            if (!"toque".equals(prof) && !"web".equals(prof)) {
                // Sem cursor na tela, "clique" no touchpad vira OK do controle.
                key("ok");
                return;
            }
            // App de toque nao tem OK: a seta some parada, mas o clique continua valendo onde ela estava.
            main.post(() -> applyMove(0, 0));
        }
        final float x = cx, y = cy;
        main.removeCallbacks(hideCursor);
        main.postDelayed(hideCursor, CURSOR_MS);
        queue.execute(() -> {
            if (!entrada.toque(x, y, longPress)) avisarLimite();
        });
    }

    /** Rolagem com dois dedos: vira um arrasto curto a partir da posicao do cursor. */
    void scroll(final float dy) {
        scrollFrom(cursorVisible ? cx : sw / 2f, sh / 2f, dy);
    }

    /** Arrasto vertical de dy pixels comecando em (x, y0); dy negativo faz a pagina descer. */
    private void scrollFrom(final float x, final float y0, final float dy) {
        drag(x, y0, x, clamp(y0 + dy, 1, sh - 2));
    }

    private boolean ensureCursor() {
        if (cursorLayer != null) return true;
        try {
            android.widget.FrameLayout layer = new android.widget.FrameLayout(ctx);
            CursorView c = new CursorView(ctx);
            layer.addView(c, new android.widget.FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(30), Gravity.TOP | Gravity.START));
            c.setTranslationX(cx);
            c.setTranslationY(cy);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(layer, lp);
            cursorLayer = layer;
            cursor = c;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "sem permissao para o cursor", e);
            cursorLayer = null;
            cursor = null;
            return false;
        }
    }

    private static final long CURSOR_MS = 30_000;
    private static final String TOUCH_NAV = "com.netflix.mediaclient";

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Digita texto. O teclado virtual do Android so mapeia ASCII, entao acentos
     * sao removidos ("ação" vira "acao"), o que funciona bem em buscas.
     */
    void text(String s) {
        final String plain = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\x20-\\x7E]", "");
        if (plain.isEmpty()) return;
        queue.execute(() -> {
            if (!entrada.texto(plain)) avisarLimite();
        });
    }

    // ---- Abrir apps, links e arquivos ----

    boolean openApp(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String n = Apps.plain(name);
        switch (n) {
            case "inicio":
            case "home":
                return key("home");
            case "configuracoes":
            case "ajustes":
                return start(new Intent(Intent.ACTION_MAIN).setComponent(
                        new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")));
            case "keystone":
                return start(Hardware.findAvailable(ctx, Hardware.getKeystoneIntents(Hardware.detectChipset())));
            case "imagem":
                return start(Hardware.findAvailable(ctx, Hardware.getImageAdjustmentIntents(Hardware.detectChipset())));
            case "hdmi":
                return start(Hardware.findAvailable(ctx, Hardware.getHdmiIntents(Hardware.detectChipset())));
            case "tv":
            case "tv ao vivo":
            case "televisao":
            case "canais":
                return start(Apps.launchIntent(ctx, Apps.TV_PKG));
            case "wifi":
                return start(new Intent(Settings.ACTION_WIFI_SETTINGS));
            case "bluetooth":
                return start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
        String pkg = installedAlias(n.replace(" ", ""));
        if (pkg == null && name.contains(".")) pkg = name.trim();
        if (pkg == null) pkg = Apps.findByName(ctx, name);
        if (pkg == null) return false;
        return start(Apps.launchIntent(ctx, pkg));
    }

    /** A versao instalada de um app conhecido ("netflix"), ou null. */
    String installedAlias(String alias) {
        String[] pkgs = ALIASES.get(alias);
        if (pkgs == null) return null;
        for (String p : pkgs) if (Apps.launchIntent(ctx, p) != null) return p;
        return null;
    }

    /** Abre a TV ao vivo ja no canal pedido ("globo", "cultura"). */
    boolean openChannel(String name) {
        Intent i = Apps.launchIntent(ctx, Apps.TV_PKG);
        i.putExtra(TvActivity.EXTRA_CHANNEL, name);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return start(i);
    }

    boolean openUrl(String u) {
        if (u == null) return false;
        u = u.trim();
        if (u.isEmpty()) return false;
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) u = "https://" + u;
        Uri uri = Uri.parse(u);
        // So links da web: o app roda como sistema, e esquemas como intent:, file: ou content:
        // abririam telas e arquivos internos do aparelho.
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String pkg = null;
        if (host.endsWith("youtube.com") || host.equals("youtu.be")) pkg = installedAlias("youtube");
        else if (host.endsWith("netflix.com")) pkg = installedAlias("netflix");
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        if (pkg != null) {
            i.setPackage(pkg);
            if (start(i)) return true;
            i.setPackage(null);
        }
        return start(i);
    }

    boolean openFile(File f) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName()).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = "*/*";
        MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // Abre direto no app do sistema, sem a pergunta "Abrir com".
        String pkg = mime.startsWith("image/") ? "com.android.gallery3d"
                : mime.startsWith("video/") ? "com.softwinner.TvdVideo"
                : mime.startsWith("audio/") ? "com.android.music" : null;
        if (pkg != null) {
            i.setPackage(pkg);
            if (start(i)) return true;
            i.setPackage(null);
        }
        return start(i);
    }

    private boolean start(Intent i) {
        if (i == null) return false;
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "nao abriu " + i, e);
            return false;
        }
    }

    // ---- Tela atual: o controle do celular se adapta a ela ----

    /** Recebe o estado da tela (JSON) sempre que muda; o WebServer repassa aos celulares. */
    interface ScreenListener {
        void onScreenChanged(String json);
    }

    private volatile ScreenListener screenListener;
    private volatile String screenPkg = "";
    private volatile String screenFocus = "";

    void setScreenListener(ScreenListener l) {
        screenListener = l;
    }

    // ---- Recentes: so entra o que foi assistido de verdade (5 minutos no mesmo titulo/app/canal) ----

    private static final long WATCHED_MS = 5 * 60 * 1000;
    private String watchPkg = "", watchTitle = "";
    private final Runnable watchCommit = this::commitWatch;

    private void commitWatch() {
        if (!watchPkg.isEmpty()) Prefs.addRecent(ctx, watchPkg, watchTitle);
    }

    /** Comeca a contar uma sessao; se continuar igual por 5 minutos, vai para os Recentes. */
    void watch(String pkg, String title) {
        final String t = title == null ? "" : title;
        main.post(() -> {
            if (pkg.equals(watchPkg) && t.equals(watchTitle)) return;
            main.removeCallbacks(watchCommit);
            watchPkg = pkg;
            watchTitle = t;
            main.postDelayed(watchCommit, WATCHED_MS);
        });
    }

    void stopWatch() {
        main.post(() -> {
            main.removeCallbacks(watchCommit);
            watchPkg = "";
            watchTitle = "";
        });
    }

    /** OK num app de video: o titulo focado passa a ser o que esta sendo assistido. */
    void onSelect() {
        String pkg = screenPkg, focus = screenFocus;
        if (!watchable(pkg) || focus.isEmpty() || pkg.equals(ctx.getPackageName())) return;
        watch(pkg, focus);
    }

    String screenApp() {
        return currentPkg();
    }

    String screenName() {
        String pkg = currentPkg();
        if (pkg.equals(ctx.getPackageName())) return TvActivity.visible ? "TV ao vivo" : "Tela inicial";
        Apps.Entry e = pkg.isEmpty() ? null : Apps.entry(ctx, pkg);
        return e != null ? e.label : "nada";
    }

    boolean isMuted() {
        return audio.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /** Chamado pelo KeyService: app em primeiro plano e (se houver) o item focado. */
    void onScreen(String pkg, String focus) {
        boolean appChanged = !pkg.equals(screenPkg);
        if (appChanged) {
            screenPkg = pkg;
            screenFocus = "";
            // A TV ao vivo (o proprio app) conta os canais por conta propria.
            if (!pkg.equals(ctx.getPackageName())) {
                if (watchable(pkg)) watch(pkg, null);
                else stopWatch();
            } else if (!TvActivity.visible) {
                stopWatch(); // voltou para a tela inicial
            }
        }
        if (focus != null) {
            if (focus.equals(screenFocus) && !appChanged) return;
            screenFocus = focus;
        } else if (!appChanged) {
            return;
        }
        ScreenListener l = screenListener;
        if (l != null) l.onScreenChanged(screenJson());
    }

    /**
     * inicio (nossa tela inicial), video (apps de streaming), web (navegadores), toque (app de
     * celular sem versao de TV, ex.: a Netflix de celular que vem no HY300: so anda com cursor) ou geral.
     */
    static String profile(String pkg) {
        String self = App.inst.getPackageName();
        if (self.equals(pkg) && TvActivity.visible) return "video";
        if (pkg == null || pkg.isEmpty() || pkg.equals(self)) return "inicio";
        if (pkg.contains("browser") || pkg.contains("chrome") || pkg.contains("webview")) return "web";
        if (touchOnly(pkg)) return "toque";
        if (pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("disney") || pkg.contains("amazonvideo")
                || pkg.contains("globo") || pkg.contains("spotify") || pkg.contains("espn") || pkg.contains("TvdVideo")
                || pkg.contains("gallery") || pkg.contains("hbo") || pkg.contains("paramount")
                || pkg.contains("pluto") || pkg.contains("twitch") || pkg.contains("iptv") || pkg.contains("tv.android")) {
            return "video";
        }
        return "geral";
    }

    private static final java.util.Map<String, Boolean> touchCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** App sem entrada no menu da TV (LEANBACK_LAUNCHER) = feito para celular, navega por toque. */
    private static boolean touchOnly(String pkg) {
        Boolean v = touchCache.get(pkg);
        if (v != null) return v;
        boolean touch = false;
        try {
            android.content.pm.PackageManager pm = App.inst.getPackageManager();
            Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            touch = pm.queryIntentActivities(tv, 0).isEmpty() && !pm.queryIntentActivities(phone, 0).isEmpty();
        } catch (RuntimeException ignored) {
        }
        touchCache.put(pkg, touch);
        return touch;
    }

    /** Apps em que vale guardar "o que estava assistindo" (Recentes). */
    static boolean watchable(String pkg) {
        String p = profile(pkg);
        return "video".equals(p) || ("toque".equals(p) && (pkg.contains("netflix") || pkg.contains("globo")
                || pkg.contains("disney") || pkg.contains("amazon") || pkg.contains("youtube")));
    }

    /**
     * App em primeiro plano. Pergunta ao Android primeiro (o app e de sistema, entao ve os outros
     * apps): neste aparelho os eventos de acessibilidade dos outros apps nao chegam, e o screenPkg
     * ficava parado na tela inicial.
     */
    private String currentPkg() {
        // Sem permissao de sistema a lista so traz o proprio app; ai vale o que a acessibilidade viu.
        if (!Entrada.temSistema(ctx)) return screenPkg;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (!t.isEmpty() && t.get(0).topActivity != null) return t.get(0).topActivity.getPackageName();
        } catch (Exception ignored) {
        }
        return screenPkg;
    }

    private String screenJson() {
        String pkg = currentPkg();
        Apps.Entry e = pkg.isEmpty() ? null : Apps.entry(ctx, pkg);
        String name = pkg.equals(ctx.getPackageName()) ? (TvActivity.visible ? "TV ao vivo" : "Tela inicial")
                : e != null ? e.label : pkg;
        return "{\"tipo\":\"tela\",\"app\":" + Json.str(pkg)
                + ",\"nome\":" + Json.str(name)
                + ",\"perfil\":" + Json.str(profile(pkg))
                + ",\"foco\":" + Json.str(pkg.equals(screenPkg) ? screenFocus : "") + "}";
    }

    // ---- Estado para a pagina e para os Atalhos ----

    String stateJson() {
        String screen = screenJson();
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return "{\"ok\":true," + screen.substring(1, screen.length() - 1)
                + ",\"volume\":" + Math.round(vol * 100f / Math.max(1, max))
                + ",\"mudo\":" + audio.isStreamMute(AudioManager.STREAM_MUSIC)
                + ",\"entrada\":" + Json.str(entrada.modo())
                + ",\"escuro\":" + Ui.dark + "}";
    }

    String appsJson() {
        List<String> favs = Prefs.favs(ctx);
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Apps.Entry e : Apps.all(ctx, true)) {
            if (sb.length() > 1) sb.append(',');
            int fav = favs.indexOf(e.pkg);
            int color = Ui.appColor(e.pkg, e.icon, fav >= 0 ? fav : i);
            sb.append("{\"pkg\":").append(Json.str(e.pkg))
                    .append(",\"nome\":").append(Json.str(e.label))
                    .append(",\"fav\":").append(fav)
                    .append(",\"cor\":").append(Json.str(String.format("#%06X", color & 0xFFFFFF)))
                    .append('}');
            i++;
        }
        return sb.append(']').toString();
    }

    private final Map<String, byte[]> iconCache = new HashMap<>();

    /** Icone do app em PNG 96x96 para a pagina do celular (cacheado). */
    synchronized byte[] iconPng(String pkg) {
        if (pkg == null) return null;
        byte[] cached = iconCache.get(pkg);
        if (cached != null) return cached;
        Apps.Entry e = Apps.entry(ctx, pkg);
        if (e == null || e.icon == null) return null;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        e.icon.setBounds(0, 0, 96, 96);
        e.icon.draw(c);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
        b.recycle();
        byte[] png = bo.toByteArray();
        iconCache.put(pkg, png);
        return png;
    }
}
