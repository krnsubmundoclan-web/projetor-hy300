package com.mlab.projetor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Tokens visuais (inspirados no iOS/macOS, tema claro e escuro) e fabricas de views. */
final class Ui {
    /** Azul do tema escuro, usado em telas que sao sempre escuras (TV ao vivo). */
    static final int ACCENT_DARK = 0xFF0A84FF;
    static boolean dark;
    static int BG, CARD, TEXT, MUTED, SOFT, ACCENT, RING, GLASS, GLASS_EDGE, FOCUS_BG;
    static int[] WALLPAPER;

    /** Paleta de cores do sistema da Apple, usada para apps sem cor de marca conhecida. */
    private static final int[] PALETTE = {
            0xFF007AFF, 0xFF34C759, 0xFFFF9500, 0xFFAF52DE, 0xFFFF2D55, 0xFF5AC8FA, 0xFFFFCC00, 0xFF30B0C7,
    };
    private static final Map<String, Integer> BRAND = new HashMap<>();

    static {
        BRAND.put("com.google.android.youtube.tv", 0xFFFF3B30);
        BRAND.put("com.netflix.mediaclient", 0xFF1C1C1E);
        BRAND.put("com.disney.disneyplus", 0xFF0A3D91);
        BRAND.put("com.amazon.amazonvideo.livingroom", 0xFF00A8E1);
        BRAND.put("com.globo.globotv", 0xFFFF2D55);
        BRAND.put("com.spotify.tv.android", 0xFF1DB954);
        BRAND.put("com.espn.score_center", 0xFFFF9500);
        BRAND.put("com.tcl.browser", 0xFF5856D6);
        BRAND.put(Apps.TV_PKG, 0xFF5E5CE6);
    }

    static Typeface w300, w400, w500, w600, w700;
    static float density = 1f;

    private Ui() {}

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        w300 = font(c, 300);
        w400 = font(c, 400);
        w500 = font(c, 500);
        w600 = font(c, 600);
        w700 = font(c, 700);
        applyTheme(Prefs.dark(c));
    }

    static void applyTheme(boolean isDark) {
        dark = isDark;
        if (isDark) {
            BG = 0xFF000000;
            CARD = 0xFF1C1C1E;
            TEXT = 0xFFF5F5F7;
            MUTED = 0xFF98989D;
            SOFT = 0xFF2C2C2E;
            ACCENT = 0xFF0A84FF;
            RING = 0xFFFFFFFF;
            GLASS = 0xB32C2C2E;
            GLASS_EDGE = 0x26FFFFFF;
            FOCUS_BG = 0xFF48484A;
            WALLPAPER = new int[]{0xFF0B1022, 0xFF140E22, 0xFF050507};
        } else {
            BG = 0xFFF5F5F7;
            CARD = 0xFFFFFFFF;
            TEXT = 0xFF1D1D1F;
            MUTED = 0xFF6E6E73;
            SOFT = 0xFFE8E8ED;
            ACCENT = 0xFF007AFF;
            RING = 0xFF1D1D1F;
            GLASS = 0xB3FFFFFF;
            GLASS_EDGE = 0x80FFFFFF;
            FOCUS_BG = 0xFFFFFFFF;
            WALLPAPER = new int[]{0xFFE6ECFF, 0xFFF6ECF6, 0xFFFFF3E6};
        }
    }

    private static Typeface font(Context c, int weight) {
        Typeface tf = null;
        try {
            tf = new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' 28")
                    .build();
        } catch (Exception ignored) {
        }
        if (tf == null) tf = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        return tf;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /**
     * Papel de parede: degrade suave, dois brilhos de cor difusos e uma textura de granulado
     * bem fina por cima. O granulado e um bitmap pequeno gerado uma vez e repetido (barato).
     */
    static Drawable wallpaper(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR, WALLPAPER);
        GradientDrawable glowA = glow(dark ? 0x804A6CF0 : 0x80A9C1FF, dp(520));
        GradientDrawable glowB = glow(dark ? 0x70A04BD8 : 0x70FFC9A8, dp(560));
        // Brilho atras dos cartoes: e o que torna a transparencia deles perceptivel.
        GradientDrawable glowC = glow(dark ? 0x6630B0C7 : 0x6699E2D0, dp(380));
        BitmapDrawable grain = new BitmapDrawable(c.getResources(), grain());
        grain.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{base, glowA, glowB, glowC, grain});
        ld.setLayerSize(1, dp(1040), dp(1040));
        ld.setLayerInsetLeft(1, -dp(420));
        ld.setLayerInsetTop(1, -dp(520));
        ld.setLayerSize(2, dp(1120), dp(1120));
        ld.setLayerGravity(2, Gravity.BOTTOM | Gravity.END);
        ld.setLayerInsetRight(2, -dp(460));
        ld.setLayerInsetBottom(2, -dp(620));
        ld.setLayerSize(3, dp(760), dp(760));
        ld.setLayerInsetLeft(3, dp(560));
        ld.setLayerInsetTop(3, -dp(120));
        return ld;
    }

    private static GradientDrawable glow(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{color, color & 0x00FFFFFF});
        g.setGradientRadius(radius);
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    private static Bitmap grainBitmap;
    private static boolean grainDark;

    private static Bitmap grain() {
        if (grainBitmap != null && grainDark == dark) return grainBitmap;
        int size = 160;
        int[] px = new int[size * size];
        Random r = new Random(7);
        int rgb = dark ? 0xFFFFFF : 0x000000;
        for (int i = 0; i < px.length; i++) {
            // Alfa entre 0 e ~5%: so se percebe de perto, quebra a chapa lisa do degrade.
            int a = (int) (Math.abs(r.nextGaussian()) * (dark ? 6 : 7));
            px[i] = (Math.min(a, 16) << 24) | rgb;
        }
        grainBitmap = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        grainDark = dark;
        return grainBitmap;
    }

    /** Vidro colorido: mais opaco no topo, mais transparente embaixo (o fundo aparece por tras). */
    private static GradientDrawable glassFill(int color, float radiusPx) {
        int rgb = color & 0x00FFFFFF;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(0xB0 << 24) | rgb, (0x70 << 24) | rgb});
        g.setCornerRadius(radiusPx);
        g.setStroke(dp(1), 0x40FFFFFF);
        return g;
    }

    /** Material "vidro" translucido do Dock. */
    static GradientDrawable glass(float radiusDp) {
        GradientDrawable g = round(GLASS, radiusDp);
        g.setStroke(dp(1), GLASS_EDGE);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static ImageView icon(Context c, int res, int sizeDp, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static View space(Context c, float weight) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, weight));
        return v;
    }

    static View vspace(Context c, float weight) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, weight));
        return v;
    }

    /** Menu contextual dos apps, com as mesmas cores, fonte e foco da tela inicial. */
    static void appMenu(Context c, View anchor, String title, String[] labels, Runnable[] actions) {
        LinearLayout panel = col(c);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = round(CARD, 18);
        background.setStroke(dp(1), GLASS_EDGE);
        panel.setBackground(background);

        TextView heading = text(c, title, 17, w600, TEXT);
        heading.setSingleLine(true);
        heading.setPadding(dp(10), dp(6), dp(10), dp(12));
        panel.addView(heading);

        PopupWindow popup = new PopupWindow(panel, dp(290), -2, true);
        popup.setBackgroundDrawable(round(Color.TRANSPARENT, 18));
        popup.setElevation(dp(12));
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            TextView option = text(c, labels[i], 16, w500, TEXT);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(dp(16), 0, dp(12), 0);
            GradientDrawable row = round(SOFT, 11);
            option.setBackground(row);
            focusable(option, row, 1.02f, null);
            option.setFocusableInTouchMode(true);
            final Runnable action = actions[i];
            option.setOnClickListener(v -> {
                popup.dismiss();
                action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
            if (i > 0) lp.topMargin = dp(7);
            panel.addView(option, lp);
            if (first == null) first = option;
        }
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        View firstOption = first;
        if (firstOption != null) panel.post(firstOption::requestFocus);
    }

    /** Favoritos: o foco muda apenas o tamanho, preservando a arte sem contorno. */
    static void focusableIcon(final View v, final Runnable onFocus) {
        focusableIcon(v, 1f, onFocus);
    }

    static void focusableIcon(final View v, final float restScale, final Runnable onFocus) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setScaleX(restScale);
        v.setScaleY(restScale);
        v.setOnFocusChangeListener((view, has) -> {
            float scale = has ? Math.max(1.15f, restScale) : restScale;
            view.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
            if (has && onFocus != null) onFocus.run();
        });
    }

    /** Foco do controle remoto ao estilo Apple TV: cresce, ganha sombra e um contorno fino. */
    static void focusable(final View v, final GradientDrawable bg, final float scale, final Runnable onFocus) {
        focusable(v, bg, scale, onFocus, 0);
    }

    /**
     * Com glassColor (cartoes de vidro): em repouso fica translucido e sem sombra
     * (a sombra apareceria atraves da transparencia); no foco fica solido, com sombra.
     */
    static void focusable(final View v, final GradientDrawable bg, final float scale, final Runnable onFocus,
                          final int glassColor) {
        final boolean glass = glassColor != 0 && bg != null;
        final int restElevation = glass ? 0 : dp(2);
        // Dois drawables separados: trocar de degrade para cor solida no mesmo GradientDrawable
        // deixa o shader antigo pendurado e o cartao focado nao fica opaco.
        final Drawable rest = glass ? glassFill(glassColor, bg.getCornerRadius()) : bg;
        if (glass) {
            bg.setColor(glassColor);
            bg.setStroke(dp(3), RING);
            v.setBackground(rest);
        }
        v.setFocusable(true);
        v.setClickable(true);
        v.setElevation(restElevation);
        v.setOnFocusChangeListener((view, has) -> {
            if (has) {
                if (glass) view.setBackground(bg);
                else if (bg != null) bg.setStroke(dp(3), RING);
                view.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
                view.setElevation(dp(16));
                if (onFocus != null) onFocus.run();
            } else {
                if (glass) view.setBackground(rest);
                else if (bg != null) bg.setStroke(0, 0);
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                view.setElevation(restElevation);
            }
        });
    }

    /** Cor do "cartao de tinta" do app: cor da marca, senao a cor dominante do icone. */
    static int appColor(String pkg, Drawable icon, int index) {
        Integer brand = BRAND.get(pkg);
        if (brand != null) return brand;
        Integer fromIcon = dominant(icon);
        return fromIcon != null ? fromIcon : PALETTE[index % PALETTE.length];
    }

    private static Integer dominant(Drawable d) {
        if (d == null) return null;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            int[] count = new int[12];
            float[][] sum = new float[12][3];
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;
                    int k = (int) (hsv[0] / 30f) % 12;
                    count[k]++;
                    sum[k][0] += hsv[0];
                    sum[k][1] += hsv[1];
                    sum[k][2] += hsv[2];
                }
            }
            int best = 0;
            for (int k = 1; k < 12; k++) if (count[k] > count[best]) best = k;
            if (count[best] < 12) return null;
            float n = count[best];
            float v = Math.max(0.5f, Math.min(0.85f, sum[best][2] / n));
            return Color.HSVToColor(new float[]{sum[best][0] / n, Math.min(0.85f, sum[best][1] / n), v});
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Texto escuro sobre cores claras (amarelo, ciano), branco nas demais. */
    static int onColor(int c) {
        double l = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
        return l > 0.62 ? 0xFF1D1D1F : 0xFFFFFFFF;
    }
}
