package com.mlab.projetor;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreta comandos falados em portugues e devolve uma frase curta para a Siri/pagina falar.
 * Entende numeros por extenso ("volume trinta"), dois comandos juntos ("abre a netflix e coloca
 * o volume em 30"), busca no app aberto e, se nao reconhecer o comando, faz uma busca.
 */
final class Voice {
    static final class Result {
        final boolean ok;
        final String speech;

        Result(boolean ok, String speech) {
            this.ok = ok;
            this.speech = speech;
        }
    }

    private static final String VERBS = "abr|abe|coloc|bot|aument|diminu|abaix|baix|sob|volume|paus|par|continu|busc|procur"
            + "|pesquis|lig|deslig|volt|avanc|canal|mud|silenc|tema|modo|tir|entr|vai|ir |quero|assist|toc|pul|proxim|inicio";
    private static final Pattern SPLIT = Pattern.compile("\\s+(?:e depois|depois|e entao|e)\\s+(?=(?:" + VERBS + "))");
    private static final Pattern VOLUME_NUM = Pattern.compile("volume\\s+(?:(?:em|para|pra|no|na|a)\\s+)?(\\d{1,3})");
    private static final Pattern PERCENT = Pattern.compile("^(\\d{1,3})\\s*(?:por cento|%)?$");
    private static final Pattern SEARCH = Pattern.compile(
            "^(?:buscar|busca|busque|pesquisar|pesquisa|pesquise|procurar|procura|procure|quero ver|quero assistir|"
                    + "assistir|assiste|tocar|toca|toque|ver|passar|passa|achar|acha|encontrar|encontra)\\s+(.+)$");
    /** Pedido claro de busca: o resto da frase e titulo ("procura o silencio dos inocentes"), nao comando. */
    private static final Pattern SEARCH_FIRST = Pattern.compile(
            "^(?:buscar|busca|busque|pesquisar|pesquisa|pesquise|procurar|procura|procure|quero ver|quero assistir|"
                    + "assistir|assiste|achar|acha|encontrar|encontra)\\s+.+$");
    private static final Pattern WHERE = Pattern.compile("^(.+?)\\s+(?:no|na|do|da|pelo|pela)\\s+(youtube|netflix|spotify)$");
    private static final Pattern CHANNEL = Pattern.compile("^(?:canal|no canal|o canal|coloca o canal|bota o canal|abre o canal|abrir o canal|muda pro canal|muda para o canal)\\s+(.+)$");
    private static final Pattern OPEN = Pattern.compile(
            "^(?:abrir|abre|abra|abri|entrar|entra|entre|ir|vai|va|coloca|colocar|coloque|bota|botar|bote|liga|ligar|inicia|iniciar|mostra|mostrar)"
                    + "\\s+(?:(?:no|na|o|a|em|pro|pra|para o|para a|para)\\s+)?(.+)$");

    private static final Map<String, Integer> NUM = new HashMap<>();

    static {
        String[] units = {"zero", "um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze",
                "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove"};
        for (int i = 0; i < units.length; i++) NUM.put(units[i], i);
        NUM.put("uma", 1);
        NUM.put("duas", 2);
        NUM.put("catorze", 14);
        String[] tens = {"vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa"};
        for (int i = 0; i < tens.length; i++) NUM.put(tens[i], 20 + 10 * i);
        NUM.put("cem", 100);
        NUM.put("cento", 100);
    }

    private final Context ctx;
    private final Controller ctl;

    Voice(Context ctx, Controller ctl) {
        this.ctx = ctx.getApplicationContext();
        this.ctl = ctl;
    }

    Result run(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new Result(false, "Não ouvi nada.");
        String s = normalize(raw);
        if (s.isEmpty()) return new Result(false, "Não ouvi nada.");
        List<String> parts = new ArrayList<>();
        for (String p : SPLIT.split(s)) if (!p.trim().isEmpty()) parts.add(p.trim());
        StringBuilder speech = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < parts.size(); i++) {
            Result r = single(parts.get(i));
            any |= r.ok;
            if (speech.length() > 0) speech.append(' ');
            speech.append(r.speech);
            // Abriu um app e ainda ha outro comando: espera o app aparecer antes de continuar.
            if (r.ok && i < parts.size() - 1 && r.speech.startsWith("Abrindo")) sleep(1500);
        }
        return new Result(any, speech.toString());
    }

    private static String normalize(String raw) {
        String s = Apps.plain(raw).replaceAll("[.,!?;:\"]", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceFirst("^(?:(?:ei|ok|oi|e ai)\\s+)?(?:projetor\\s*)?", "")
                .replaceFirst("^(?:por favor|eu quero que voce|voce pode|pode|consegue|da pra|me)\\s+", "")
                .replaceFirst("\\s+(?:por favor|ai|pra mim)$", "");
        return s.trim();
    }

    /** "vinte e cinco" -> "25", "trinta" -> "30", "cem" -> "100". */
    static String words2digits(String s) {
        String[] w = s.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            Integer n = NUM.get(w[i]);
            if (n != null) {
                int total = n;
                if (n >= 20 && n < 100 && i + 2 < w.length && w[i + 1].equals("e")) {
                    Integer u = NUM.get(w[i + 2]);
                    if (u != null && u < 10) {
                        total += u;
                        i += 2;
                    }
                }
                w[i] = String.valueOf(total);
                if (out.length() > 0) out.append(' ');
                out.append(total);
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(w[i]);
        }
        return out.toString();
    }

    private Result single(String s) {
        // ---- Informacoes ----
        if (s.matches(".*\\b(que horas|horas sao|hora e|que hora)\\b.*")) {
            return new Result(true, "São " + new SimpleDateFormat("HH:mm", new Locale("pt", "BR")).format(new Date()) + ".");
        }
        if (s.matches(".*\\bo que (?:esta|ta|tá)? ?(?:passando|tocando|na tela|aberto)\\b.*")) {
            String name = ctl.screenName();
            return new Result(true, "Na tela agora: " + name + ".");
        }

        if (SEARCH_FIRST.matcher(s).matches()) {
            Matcher q = SEARCH.matcher(s);
            if (q.find()) return search(q.group(1).trim());
        }

        // ---- Volume ----
        // Numeros por extenso so viram digitos em comandos ("uma linda mulher" continua titulo).
        String digits = words2digits(s);
        Matcher m = VOLUME_NUM.matcher(digits);
        if (m.find()) return volumeTo(Integer.parseInt(m.group(1)));
        if (s.matches(".*\\bvolume\\b.*\\b(maximo|todo|tudo|no talo)\\b.*") || s.matches(".*\\b(maximo|o mais alto)\\b.*volume.*")) return volumeTo(100);
        if (s.matches(".*\\bvolume\\b.*\\b(minimo|baixinho)\\b.*")) return volumeTo(10);
        if (s.matches(".*\\bvolume\\b.*\\b(metade|meio)\\b.*")) return volumeTo(50);
        if (s.matches(".*\\b(tira do mudo|desmuta|desmutar|volta o som|volta com o som|liga o som|com som)\\b.*")) {
            if (ctl.isMuted()) ctl.volume("mute");
            return new Result(true, "Som de volta.");
        }
        if (s.matches("^(?:(?:coloca|colocar|deixa|deixar|bota|botar|fica|ficar|modo|no|em|o|a|de)\\s+)*(?:mudo|silencio|sem som|tira o som|muta|mutar|desliga o som|cala a boca|cala)\\b.*")) {
            if (!ctl.isMuted()) ctl.volume("mute");
            return new Result(true, "Mudo.");
        }
        boolean little = s.matches(".*\\b(um pouco|pouquinho|um pouquinho)\\b.*");
        boolean lot = s.matches(".*\\b(bastante|muito|bem)\\b.*");
        int steps = little ? 1 : lot ? 6 : 3;
        if (s.matches(".*\\b(aumenta|aumentar|aumente|sobe o volume|subir o volume|mais alto|mais volume)\\b.*") || s.equals("alto")) {
            for (int i = 0; i < steps; i++) ctl.volume("up");
            return new Result(true, "Aumentei.");
        }
        if (s.matches(".*\\b(diminui|diminuir|diminua|abaixa|abaixar|abaixe|baixa|baixar|mais baixo|menos volume|baixinho)\\b.*")) {
            for (int i = 0; i < steps; i++) ctl.volume("down");
            return new Result(true, "Diminuí.");
        }

        // ---- TV ao vivo ----
        if (s.matches(".*\\b(proximo canal|sobe (o )?canal|canal (de )?cima|canal seguinte)\\b.*")) {
            if (!TvActivity.visible) ctl.openApp("tv");
            else ctl.key("up");
            return new Result(true, "Próximo canal.");
        }
        if (s.matches(".*\\b(canal anterior|desce (o )?canal|canal (de )?baixo|volta (o )?canal)\\b.*")) {
            if (!TvActivity.visible) ctl.openApp("tv");
            else ctl.key("down");
            return new Result(true, "Canal anterior.");
        }
        if (s.matches("^(?:abre |abrir |coloca |liga |ver |assistir )?(?:a )?(tv ao vivo|tv aberta|televisao|tv)$")) {
            ctl.openApp("tv");
            return new Result(true, "Abrindo a TV ao vivo.");
        }
        m = CHANNEL.matcher(digits);
        if (m.find()) {
            String ch = m.group(1).trim();
            if (ctl.openChannel(ch)) return new Result(true, "Canal " + ch + ".");
        }

        // ---- Reproducao ----
        if (s.matches("^(pausa|pausar|pause|pausa ai|para|parar|pare|segura)( o video| o filme| a musica| ai)?$")) {
            ctl.key("pause");
            return new Result(true, "Pausado.");
        }
        if (s.matches("^(continua|continuar|continue|play|da play|retoma|retomar|despausa|despausar|solta|volta a tocar)( o video| o filme| a musica)?$")) {
            ctl.key("play");
            return new Result(true, "Continuando.");
        }
        if (s.matches(".*\\b(proximo episodio|proximo video|proxima musica|proximo|pula|pular|passa pro proximo)\\b.*")) {
            ctl.key("next");
            return new Result(true, "Próximo.");
        }
        if (s.matches(".*\\b(avanca|avancar|avance|adianta|adiantar|pra frente|para frente)\\b.*")) {
            for (int i = 0; i < (lot ? 3 : 1); i++) ctl.key("fwd");
            return new Result(true, "Avançando.");
        }
        if (s.matches(".*\\b(retrocede|retroceder|volta um pouco|volta o video|voltar o video|pra tras|para tras|rebobina|volta \\d+ segundos)\\b.*")) {
            for (int i = 0; i < (lot ? 3 : 1); i++) ctl.key("rew");
            return new Result(true, "Voltando.");
        }

        // ---- Sistema e navegacao ----
        if (s.matches("^(desliga|desligar|desligue|apaga|apagar|dormir|vai dormir|modo de espera)( o projetor| tudo)?$")) {
            ctl.key("power");
            return new Result(true, "Desligando o projetor.");
        }
        if (s.matches(".*\\b(tema|modo) (escuro|noturno|dark)\\b.*")) return theme(true);
        if (s.matches(".*\\b(tema|modo) (claro|diurno|light)\\b.*")) return theme(false);
        if (s.matches("^(inicio|tela inicial|home|menu inicial|vai pro inicio|volta pro inicio|voltar pro inicio|voltar para o inicio|sair de tudo)$")) {
            ctl.key("home");
            return new Result(true, "Tela inicial.");
        }
        if (s.matches("^(volta|voltar|volte|sair|sai|fecha|fechar|fecha isso)$")) {
            ctl.key("back");
            return new Result(true, "Ok.");
        }
        String dir = direction(s);
        if (dir != null) {
            ctl.key(dir);
            return new Result(true, "Ok.");
        }

        // ---- Buscar ----
        m = SEARCH.matcher(s);
        if (m.find()) return search(m.group(1).trim());

        // ---- Abrir app (se nao for app, vira busca: "coloca interestelar") ----
        m = OPEN.matcher(s);
        if (m.find()) {
            String name = m.group(1).trim();
            if (ctl.openApp(name)) return new Result(true, "Abrindo " + name + ".");
            return search(name);
        }
        if (ctl.openApp(s)) return new Result(true, "Abrindo " + s + ".");

        // Nada reconhecido: frase com cara de titulo vira busca.
        if (s.length() >= 3) return search(s);
        return new Result(false, "Não entendi.");
    }

    private Result volumeTo(int v) {
        v = Math.max(0, Math.min(100, v));
        if (v > 0 && ctl.isMuted()) ctl.volume("mute");
        ctl.setVolume(v);
        return new Result(true, "Volume em " + v + "%.");
    }

    private Result theme(boolean dark) {
        Prefs.setDark(ctx, dark);
        Ui.applyTheme(dark);
        HomeActivity.refreshTheme();
        return new Result(true, dark ? "Tema escuro." : "Tema claro.");
    }

    private static String direction(String s) {
        switch (s) {
            case "cima": case "pra cima": case "para cima": case "sobe": return "up";
            case "baixo": case "pra baixo": case "para baixo": case "desce": return "down";
            case "esquerda": case "pra esquerda": case "para a esquerda": case "para esquerda": return "left";
            case "direita": case "pra direita": case "para a direita": case "para direita": return "right";
            case "ok": case "confirmar": case "confirma": case "seleciona": case "selecionar": case "entra": case "enter": return "ok";
            default: return null;
        }
    }

    /** Busca no app pedido; senao no app de video aberto (YouTube/Netflix/Spotify); senao no YouTube. */
    private Result search(String q) {
        String where = null;
        Matcher m = WHERE.matcher(q);
        if (m.find()) {
            q = m.group(1).trim();
            where = m.group(2);
        }
        if (where == null) {
            String pkg = ctl.screenApp();
            if (pkg.contains("netflix")) where = "netflix";
            else if (pkg.contains("spotify")) where = "spotify";
            else where = "youtube";
        }
        if (q.isEmpty()) return new Result(false, "Buscar o quê?");
        switch (where) {
            case "netflix": {
                String nf = ctl.installedAlias("netflix");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/search?q=" + Uri.encode(q)));
                if (nf != null && start(i.setPackage(nf))) return new Result(true, "Buscando " + q + " na Netflix.");
                break;
            }
            case "spotify": {
                String sp = ctl.installedAlias("spotify");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)));
                if (sp != null && start(i.setPackage(sp))) return new Result(true, "Buscando " + q + " no Spotify.");
                break;
            }
            default:
        }
        String yt = ctl.installedAlias("youtube");
        if (yt != null && (start(new Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra(SearchManager.QUERY, q))
                || start(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).setPackage(yt)))) {
            return new Result(true, "Buscando " + q + " no YouTube.");
        }
        return new Result(false, "Não consegui buscar " + q + ".");
    }

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
