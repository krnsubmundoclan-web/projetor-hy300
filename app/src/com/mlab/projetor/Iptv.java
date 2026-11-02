package com.mlab.projetor;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canais da TV ao vivo: lista de canais abertos do Brasil (iptv-org) mais as listas M3U que a
 * pessoa adicionar, com cache local. Listas grandes demais sao recusadas: o aparelho tem 1 GB.
 */
final class Iptv {
    static final String LIST_URL = "https://iptv-org.github.io/iptv/countries/br.m3u";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_CANAIS = 5000;
    private static final String KEY = "listas_iptv";
    private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"");
    /** Muda quando a pessoa adiciona ou tira uma lista, para a TV recarregar. */
    static volatile int versao;

    static final class Channel {
        final String name, group, url;
        /** Posicao na lista completa (a busca mostra so uma parte). */
        int pos;

        Channel(String name, String group, String url) {
            this.name = name;
            this.group = group;
            this.url = url;
        }
    }

    private Iptv() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("projetor", Context.MODE_PRIVATE);
    }

    static List<String> listas(Context c) {
        List<String> out = new ArrayList<>();
        for (String l : sp(c).getString(KEY, "").split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    private static File cache(Context c, String url) {
        return new File(c.getFilesDir(), "iptv_" + Integer.toHexString(url.hashCode()) + ".m3u");
    }

    /** Baixa e confere antes de guardar. Devolve o erro para mostrar, ou null se deu certo. */
    static String adicionar(Context c, String url) {
        url = url == null ? "" : url.trim();
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "O link precisa começar com http ou https";
        List<String> atuais = listas(c);
        if (atuais.contains(url)) return "Essa lista já foi adicionada";
        File f = cache(c, url);
        try {
            download(url, f);
        } catch (DemaisException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return "Lista grande demais para o projetor (limite de 10 MB)";
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return "Não foi possível baixar a lista";
        }
        if (ler(f).isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return "Esse link não tem uma lista de canais M3U";
        }
        atuais.add(url);
        salvar(c, atuais);
        return null;
    }

    static void remover(Context c, String url) {
        List<String> atuais = listas(c);
        if (atuais.remove(url)) {
            salvar(c, atuais);
            //noinspection ResultOfMethodCallIgnored
            cache(c, url).delete();
        }
    }

    private static void salvar(Context c, List<String> l) {
        sp(c).edit().putString(KEY, String.join("\n", l)).apply();
        versao++;
    }

    /** Usa o cache do dia; senao baixa. Sem internet, cai no cache antigo. Chamar fora da main thread. */
    static List<Channel> load(Context c) {
        List<Channel> out = new ArrayList<>(carregar(LIST_URL, new File(c.getFilesDir(), "iptv_br.m3u")));
        for (String url : listas(c)) out.addAll(carregar(url, cache(c, url)));
        for (int i = 0; i < out.size(); i++) out.get(i).pos = i;
        return out;
    }

    private static List<Channel> carregar(String url, File f) {
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            File tmp = new File(f.getPath() + ".tmp");
            try {
                download(url, tmp);
                if (!tmp.renameTo(f)) throw new IOException("sem acesso");
            } catch (IOException | RuntimeException ignored) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return ler(f);
    }

    private static List<Channel> ler(File f) {
        if (!f.isFile()) return new ArrayList<>();
        try (InputStream in = new FileInputStream(f)) {
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    static List<Channel> parse(BufferedReader r) throws IOException {
        List<Channel> out = new ArrayList<>();
        String name = null, group = "", raw;
        while ((raw = r.readLine()) != null && out.size() < MAX_CANAIS) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 ? line.substring(comma + 1).trim() : "Canal";
                // Tira sufixos tecnicos como "(1080p)" e "[Geo-blocked]".
                name = name.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", "").trim();
                Matcher m = GROUP.matcher(line);
                group = m.find() ? m.group(1).split(";")[0] : "";
            } else if (!line.isEmpty() && !line.startsWith("#") && name != null) {
                // So endereco da web: o app roda como sistema, e uma lista de fora nao pode abrir
                // arquivos internos do aparelho (file:, content:).
                String u = line.toLowerCase(Locale.ROOT);
                if (u.startsWith("http://") || u.startsWith("https://")) out.add(new Channel(name, group, line));
                name = null;
            }
        }
        return out;
    }

    private static final class DemaisException extends IOException {
    }

    /** Salva direto no arquivo, sem carregar a lista inteira na memoria. */
    private static void download(String url, File dest) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(15000);
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new DemaisException();
                out.write(buf, 0, n);
            }
        } finally {
            con.disconnect();
        }
    }
}
