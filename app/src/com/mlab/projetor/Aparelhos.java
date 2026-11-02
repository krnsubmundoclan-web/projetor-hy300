package com.mlab.projetor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Celulares salvos, para nao pedir o PIN de novo. Depois de acertar o PIN o aparelho recebe um
 * token proprio (aqui fica so o hash). O IP reconhece quem ja foi salvo mas perdeu o token, como o
 * app instalado na tela de inicio do iPhone, que nao enxerga os dados do Safari; quando o MAC e
 * conhecido ele tambem precisa bater, para outro aparelho que herde o IP nao entrar sozinho.
 */
final class Aparelhos {
    private static final String FILE = "aparelhos", KEY = "lista";
    private static final int MAX = 16, MAX_TOKENS = 3;
    private static final long IP_VALE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long GRAVAR_VISTO_MS = 60_000;
    static final int TOKEN_LEN = 49;

    static final class Item {
        String id, nome, ip, mac;
        long visto;
        final List<String> hashes = new ArrayList<>();
    }

    private static List<Item> cache;

    private Aparelhos() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static List<Item> carregar(Context c) {
        if (cache != null) return cache;
        List<Item> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id");
                it.nome = o.getString("nome");
                it.ip = o.getString("ip");
                it.mac = o.optString("mac", null);
                it.visto = o.getLong("visto");
                JSONArray h = o.getJSONArray("hashes");
                for (int j = 0; j < h.length(); j++) it.hashes.add(h.getString(j));
                l.add(it);
            }
        } catch (JSONException ignored) {
        }
        cache = l;
        return l;
    }

    private static void salvar(Context c) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : cache) {
                JSONObject o = new JSONObject();
                o.put("id", it.id).put("nome", it.nome).put("ip", it.ip).put("visto", it.visto);
                if (it.mac != null) o.put("mac", it.mac);
                o.put("hashes", new JSONArray(it.hashes));
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY, a.toString()).apply();
    }

    static synchronized List<Item> todos(Context c) {
        List<Item> l = new ArrayList<>(carregar(c));
        l.sort((x, y) -> Long.compare(y.visto, x.visto));
        return l;
    }

    /** Salva (ou atualiza) o aparelho deste IP e devolve um token novo para ele. */
    static synchronized String emitir(Context c, String ip, String userAgent) {
        List<Item> l = carregar(c);
        String mac = Net.mac(ip);
        Item it = null;
        for (Item x : l) if (x.ip.equals(ip) && mesmoMac(x.mac, mac)) it = x;
        if (it == null) {
            it = new Item();
            it.id = hex(8);
            l.add(it);
        }
        it.nome = nomeDe(userAgent);
        it.ip = ip;
        if (mac != null) it.mac = mac;
        return token(c, it);
    }

    private static String token(Context c, Item it) {
        String t = "d" + hex(24);
        it.hashes.add(hash(t));
        while (it.hashes.size() > MAX_TOKENS) it.hashes.remove(0);
        it.visto = System.currentTimeMillis();
        List<Item> l = carregar(c);
        while (l.size() > MAX) {
            Item velho = it;
            for (Item x : l) if (x != it && (velho == it || x.visto < velho.visto)) velho = x;
            l.remove(velho);
        }
        salvar(c);
        return t;
    }

    static synchronized boolean valido(Context c, String token, String ip) {
        String h = hash(token);
        for (Item it : carregar(c)) {
            for (String x : it.hashes) {
                if (!MessageDigest.isEqual(x.getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8))) continue;
                long agora = System.currentTimeMillis();
                if (!ip.equals(it.ip) || agora - it.visto > GRAVAR_VISTO_MS) {
                    it.ip = ip;
                    it.visto = agora;
                    salvar(c);
                }
                return true;
            }
        }
        return false;
    }

    /** Token novo para um aparelho salvo reconhecido pelo IP; null se nao for um deles. */
    static synchronized String porIp(Context c, String ip) {
        String mac = Net.mac(ip);
        long agora = System.currentTimeMillis();
        for (Item it : carregar(c)) {
            if (it.ip.equals(ip) && mesmoMac(it.mac, mac) && agora - it.visto < IP_VALE_MS) return token(c, it);
        }
        return null;
    }

    static synchronized void remover(Context c, String id) {
        Iterator<Item> i = carregar(c).iterator();
        while (i.hasNext()) if (i.next().id.equals(id)) i.remove();
        salvar(c);
    }

    private static boolean mesmoMac(String a, String b) {
        return a == null || b == null || a.equals(b);
    }

    /** Nome legivel a partir do navegador: "iPhone", "Android SM-S918B", "Windows". */
    static String nomeDe(String ua) {
        if (ua == null) return "Aparelho";
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("iPad")) return "iPad";
        int a = ua.indexOf("Android");
        if (a >= 0) {
            int fim = ua.indexOf(')', a);
            String[] p = (fim > 0 ? ua.substring(a, fim) : "").split(";");
            String modelo = p.length > 1 ? p[p.length - 1].replace("Build/", "").trim() : "";
            return modelo.isEmpty() || modelo.equals("K") || modelo.toLowerCase(Locale.ROOT).startsWith("wv") ? "Android" : "Android " + modelo;
        }
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Macintosh")) return "Mac";
        if (ua.contains("Linux")) return "Linux";
        return "Aparelho";
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static String hash(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format(Locale.ROOT, "%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
