package com.mlab.projetor;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Prefs {
    static final String[] DEFAULT_FAVS = {
            "com.google.android.youtube.tv",
            "com.netflix.mediaclient",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.globo.globotv",
            "com.spotify.tv.android",
            "com.espn.score_center",
            Apps.TV_PKG,
    };
    static final int MAX_FAVS = 8;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("projetor", Context.MODE_PRIVATE);
    }

    /**
     * PIN de 6 digitos exigido pela pagina do celular e pela API dos Atalhos.
     * Cada aparelho sorteia o seu na primeira execucao (nada de PIN padrao igual para todos).
     */
    static synchronized String pin(Context c) {
        String p = sp(c).getString("pin", null);
        // Versoes antigas guardavam um PIN de 4 digitos na mesma chave: troca por um de 6.
        return p != null && p.matches("\\d{6}") ? p : newPin(c);
    }

    /** Sorteia e grava um PIN novo; o anterior deixa de valer na hora. */
    static synchronized String newPin(Context c) {
        String p = String.format(java.util.Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1_000_000));
        sp(c).edit().putString("pin", p).remove("pin_fixo").commit();
        return p;
    }

    /** Tela inicial do fabricante, guardada antes do app assumir (para voltar a ela). */
    static String originalHome(Context c) {
        return sp(c).getString("home_original", null);
    }

    static void setOriginalHome(Context c, String pkg) {
        sp(c).edit().putString("home_original", pkg).apply();
    }

    /** Guarda uma vez os ajustes que o app altera, para a remocao poder restaura-los exatamente. */
    static synchronized boolean saveSystemState(Context c, String services, int accessibility, int mouse) {
        SharedPreferences p = sp(c);
        if (p.getBoolean("sistema_original_salvo", false)) return true;
        SharedPreferences.Editor e = p.edit()
                .putBoolean("a11y_original_null", services == null)
                .putInt("a11y_ativo_original", accessibility)
                .putInt("mouse_original", mouse)
                .putBoolean("sistema_original_salvo", true);
        if (services == null) e.remove("a11y_original");
        else e.putString("a11y_original", services);
        return e.commit();
    }

    static boolean systemStateSaved(Context c) {
        return sp(c).getBoolean("sistema_original_salvo", false);
    }

    static String originalAccessibilityServices(Context c) {
        SharedPreferences p = sp(c);
        return p.getBoolean("a11y_original_null", false) ? null : p.getString("a11y_original", "");
    }

    static int originalAccessibilityEnabled(Context c) {
        return sp(c).getInt("a11y_ativo_original", 0);
    }

    static int originalMouseState(Context c) {
        return sp(c).getInt("mouse_original", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
    }

    /** O app ja se definiu como tela inicial uma vez (nao insiste depois). */
    static boolean homeClaimed(Context c) {
        return sp(c).getBoolean("home_definida", false);
    }

    static void setHomeClaimed(Context c) {
        sp(c).edit().putBoolean("home_definida", true).apply();
    }

    /** Ultima versao em que a pessoa respondeu "Depois" ao aviso automatico. */
    static int updateDismissed(Context c) {
        return sp(c).getInt("update_depois", 0);
    }

    static void setUpdateDismissed(Context c, int code) {
        sp(c).edit().putInt("update_depois", code).apply();
    }

    static boolean dark(Context c) {
        return sp(c).getBoolean("dark", true);
    }

    static void setDark(Context c, boolean dark) {
        sp(c).edit().putBoolean("dark", dark).apply();
    }

    static boolean showHardware(Context c) {
        return sp(c).getBoolean("mostrar_hardware", true);
    }

    static void setShowHardware(Context c, boolean show) {
        sp(c).edit().putBoolean("mostrar_hardware", show).apply();
    }

    static int downloadKbps(Context c) {
        return sp(c).getInt("download_kbps", -1);
    }

    static long downloadAttemptAt(Context c) {
        return sp(c).getLong("download_tentativa_em", 0);
    }

    static void setDownloadAttemptAt(Context c, long now) {
        sp(c).edit().putLong("download_tentativa_em", now).apply();
    }

    static String downloadCheckedAt(Context c) {
        return sp(c).getString("download_medido_em", "");
    }

    static void setDownloadResult(Context c, int kbps, String checkedAt) {
        sp(c).edit().putInt("download_kbps", kbps)
                .putString("download_medido_em", checkedAt).apply();
    }

    /** Usa o decodificador OMX no lugar do Codec2 (contorna o erro 5.0 da Netflix nos anuncios). */
    static boolean netflixFix(Context c) {
        return sp(c).getBoolean("netflix_fix", true);
    }

    // ---- Recentes: "pacote|titulo" (titulo pode ser vazio), mais novo primeiro ----

    static final int MAX_RECENTS = 12;

    static synchronized List<String[]> recents(Context c) {
        List<String[]> out = new ArrayList<>();
        String s = sp(c).getString("assistidos", "");
        for (String line : s.split("\n")) {
            int bar = line.indexOf('|');
            if (bar > 0) out.add(new String[]{line.substring(0, bar), line.substring(bar + 1)});
        }
        return out;
    }

    /** Registra algo assistido; um titulo novo do mesmo app substitui a entrada so com o app. */
    static synchronized void addRecent(Context c, String pkg, String title) {
        title = title == null ? "" : title.replace('\n', ' ').replace('|', ' ').trim();
        List<String[]> list = recents(c);
        // So abriu o app e ele ja e o mais recente: nada muda (nao apaga o titulo que estava la).
        if (title.isEmpty() && !list.isEmpty() && list.get(0)[0].equals(pkg)) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            String[] r = list.get(i);
            boolean same = r[0].equals(pkg) && (r[1].equals(title) || r[1].isEmpty());
            if (same) list.remove(i);
        }
        list.add(0, new String[]{pkg, title});
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_RECENTS, list.size()); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(list.get(i)[0]).append('|').append(list.get(i)[1]);
        }
        sp(c).edit().putString("assistidos", sb.toString()).apply();
    }

    static synchronized List<String> favs(Context c) {
        String s = sp(c).getString("favs", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_FAVS));
        if (s.contains("com.tcl.browser") && !s.contains(Apps.TV_PKG)) {
            s = s.replace("com.tcl.browser", Apps.TV_PKG);
            sp(c).edit().putString("favs", s).apply();
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Adiciona ou remove dos favoritos. Retorna true se ficou favorito. */
    static synchronized boolean toggleFav(Context c, String pkg) {
        List<String> l = favs(c);
        boolean added;
        if (l.remove(pkg)) {
            added = false;
        } else {
            // Apps ausentes nao ocupam as oito vagas exibidas na home.
            l.removeIf(p -> Apps.entry(c, p) == null);
            if (l.size() >= MAX_FAVS) l.remove(l.size() - 1);
            l.add(pkg);
            added = true;
        }
        sp(c).edit().putString("favs", String.join(",", l)).apply();
        return added;
    }

    /** Reordena apenas os apps visiveis; os ausentes mantem suas posicoes guardadas. */
    static synchronized void reorderFavs(Context c, List<String> visibleOrder) {
        List<String> saved = favs(c);
        List<String> visible = new ArrayList<>();
        for (String pkg : saved) {
            if (Apps.entry(c, pkg) != null && visible.size() < MAX_FAVS) visible.add(pkg);
        }
        if (visible.size() != visibleOrder.size() || !visible.containsAll(visibleOrder)
                || !visibleOrder.containsAll(visible)) return;
        int next = 0;
        for (int i = 0; i < saved.size(); i++) {
            if (Apps.entry(c, saved.get(i)) != null && next < visibleOrder.size())
                saved.set(i, visibleOrder.get(next++));
        }
        sp(c).edit().putString("favs", String.join(",", saved)).apply();
    }
}
