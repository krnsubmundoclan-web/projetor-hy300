package com.mlab.projetor;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Detecção de hardware e compatibilidade multi-plataforma.
 * Suporta Allwinner (HY300), Rockchip (TX6), Amlogic (X96) e genéricos.
 */
final class Hardware {
    private static final String TAG = "Projetor.Hardware";

    enum Chipset {
        ALLWINNER,   // HY300, HY300 Pro
        ROCKCHIP,    // TX6, TX6 Mini
        AMLOGIC,     // X96 Max, H96 Max
        GENERIC      // Fallback para outros
    }

    private static Chipset cachedChipset = null;
    private static String cachedModel = null;

    private Hardware() {}

    /**
     * Detecta o chipset do dispositivo lendo propriedades do sistema.
     * Resultado é cacheado após a primeira chamada.
     */
    static Chipset detectChipset() {
        if (cachedChipset != null) return cachedChipset;

        String hardware = getProp("ro.hardware");
        String platform = getProp("ro.board.platform");
        String model = getProp("ro.product.model").toLowerCase();

        cachedModel = getProp("ro.product.model");

        // Allwinner: sunxi, aw, allwinner
        if (contains(hardware, "sunxi", "aw", "allwinner") ||
            contains(platform, "sunxi", "aw", "allwinner") ||
            contains(model, "hy300", "hy-300", "hy320", "hy-320", "hy300mini", "hy320mini")) {
            cachedChipset = Chipset.ALLWINNER;
        }
        // Rockchip: rk, rockchip
        else if (contains(hardware, "rk", "rockchip") ||
                 contains(platform, "rk", "rockchip") ||
                 contains(model, "tx6", "tanix")) {
            cachedChipset = Chipset.ROCKCHIP;
        }
        // Amlogic: amlogic, aml, gxm, gxl
        else if (contains(hardware, "amlogic", "aml", "gxm", "gxl") ||
                 contains(platform, "amlogic", "aml", "gxm", "gxl") ||
                 contains(model, "x96", "h96", "nexbox")) {
            cachedChipset = Chipset.AMLOGIC;
        }
        else {
            cachedChipset = Chipset.GENERIC;
        }

        Log.i(TAG, "Chipset detectado: " + cachedChipset + " (model=" + cachedModel +
              ", hardware=" + hardware + ", platform=" + platform + ")");
        return cachedChipset;
    }

    static String getModel() {
        if (cachedModel == null) detectChipset();
        return cachedModel;
    }

    /**
     * Retorna intents alternativos para ajustes de imagem/keystone baseado no chipset.
     * Tenta múltiplos pacotes conhecidos até encontrar um disponível.
     */
    static List<Intent> getImageAdjustmentIntents(Chipset chipset) {
        List<Intent> intents = new ArrayList<>();

        switch (chipset) {
            case ALLWINNER:
                // HY300 e similares Allwinner
                intents.add(comp("com.gls.Gsettings", "com.gls.Gsettings.MainActivity"));
                intents.add(comp("com.softwinner.display", "com.softwinner.display.MainActivity"));
                break;
            case ROCKCHIP:
                // TX6 e Rockchip
                intents.add(comp("com.rockchip.display", "com.rockchip.display.MainActivity"));
                intents.add(comp("com.android.settings", "com.android.settings.DisplaySettings"));
                break;
            case AMLOGIC:
                // X96, H96 e Amlogic
                intents.add(comp("com.amlogic.display", "com.amlogic.display.MainActivity"));
                intents.add(comp("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
                break;
            default:
                // Genérico: usa Settings do Android
                intents.add(new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
                break;
        }

        // Fallback universal
        intents.add(new Intent(android.provider.Settings.ACTION_SETTINGS));
        return intents;
    }

    static List<Intent> getKeystoneIntents(Chipset chipset) {
        List<Intent> intents = new ArrayList<>();

        switch (chipset) {
            case ALLWINNER:
                intents.add(comp("com.telanda.keystone", "com.telanda.keystone.MainActivity"));
                break;
            case ROCKCHIP:
                intents.add(comp("com.rockchip.keystone", "com.rockchip.keystone.MainActivity"));
                break;
            case AMLOGIC:
                intents.add(comp("com.amlogic.keystone", "com.amlogic.keystone.MainActivity"));
                break;
            default:
                // Firmware que se identifica como outro aparelho (o HY300 diz ser ADT-3): tenta todos.
                intents.add(comp("com.telanda.keystone", "com.telanda.keystone.MainActivity"));
                intents.add(comp("com.rockchip.keystone", "com.rockchip.keystone.MainActivity"));
                intents.add(comp("com.amlogic.keystone", "com.amlogic.keystone.MainActivity"));
                break;
        }
        return intents;
    }

    static List<Intent> getHdmiIntents(Chipset chipset) {
        List<Intent> intents = new ArrayList<>();

        switch (chipset) {
            case ALLWINNER:
                intents.add(comp("com.softwinner.awsource", "com.softwinner.awsource.MainActivity"));
                break;
            case ROCKCHIP:
                intents.add(comp("com.rockchip.hdmi", "com.rockchip.hdmi.MainActivity"));
                break;
            case AMLOGIC:
                intents.add(comp("com.amlogic.input", "com.amlogic.input.MainActivity"));
                break;
            default:
                break;
        }
        return intents;
    }

    /**
     * Encontra o primeiro intent disponível da lista.
     */
    static Intent findAvailable(Context ctx, List<Intent> intents) {
        PackageManager pm = ctx.getPackageManager();
        for (Intent i : intents) {
            if (pm.resolveActivity(i, 0) != null) {
                return i;
            }
        }
        return null;
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object val = get.invoke(null, key);
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean contains(String s, String... needles) {
        if (s == null || s.isEmpty()) return false;
        s = s.toLowerCase();
        for (String n : needles) {
            if (s.contains(n.toLowerCase())) return true;
        }
        return false;
    }

    private static Intent comp(String pkg, String cls) {
        return new Intent(Intent.ACTION_MAIN).setComponent(new android.content.ComponentName(pkg, cls));
    }

    /** Apps de receber transmissao deste aparelho: {para quem serve, pacote}. */
    private static final String[][] RECEPTORES = {
            {"iPhone, iPad e Mac (AirPlay)", "com.waxrain.airplaydmr2"},
            {"Android e Windows (Miracast)", "com.softwinner.miracastReceiver"},
            {"Android e Windows (Miracast)", "com.android.tv.miracast"},
            {"Celular e TV (AnyCast)", "com.softwinner.anycast"},
    };

    /** So os que existem aqui, cada um com a Intent que abre. */
    static java.util.Map<String, Intent> receptores(Context ctx) {
        java.util.Map<String, Intent> out = new java.util.LinkedHashMap<>();
        for (String[] r : RECEPTORES) {
            Intent i = Apps.launchIntent(ctx, r[1]);
            if (i != null && !out.containsKey(r[0])) out.put(r[0], i);
        }
        return out;
    }
}
