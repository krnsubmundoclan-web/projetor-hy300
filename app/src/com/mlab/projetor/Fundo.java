package com.mlab.projetor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Fundo da tela inicial: foto da pessoa (ja cortada no tamanho da tela) ou uma cor pronta. */
final class Fundo {
    static final String[] CORES = {"Padrão", "Grafite", "Azul-noite", "Vinho", "Verde-floresta", "Preto"};
    private static final int[][] GRADIENTES = {null, {0xFF2B2D33, 0xFF15161A}, {0xFF14264F, 0xFF0A1022},
            {0xFF4A1426, 0xFF1D0910}, {0xFF123A2C, 0xFF081A14}, {0xFF000000, 0xFF000000}};
    static final String[] ESCURECER = {"Leve", "Médio", "Forte"};
    private static final int[] ESCURECER_ALFA = {0x40, 0x73, 0xA6};
    static final String[] DESCANSO = {"Padrão do sistema", "Igual ao fundo", "Outra foto", "Cor sólida"};
    private static final int[] COR_PADRAO = {0xFF27306A, 0xFF2A1540};
    private static final int MAX_FOTOS = 60;

    private static Bitmap cache;

    private Fundo() {}

    private static File arquivo(Context c) {
        return new File(c.getFilesDir(), "fundo.jpg");
    }

    private static File arquivoDescanso(Context c) {
        return new File(c.getFilesDir(), "descanso.jpg");
    }

    private static Drawable cor(int i) {
        return gradienteDrawable(i > 0 && i < GRADIENTES.length ? GRADIENTES[i] : COR_PADRAO);
    }

    private static Drawable arquivoComoDrawable(Context c, File f) {
        Bitmap b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
        return b == null ? null : new BitmapDrawable(c.getResources(), b);
    }

    /** O que a tela de descanso mostra, conforme a escolha em Personalizar. */
    static Drawable descanso(Context c) {
        Drawable d = null;
        switch (Prefs.descansoModo(c)) {
            case 1:
                d = arquivoComoDrawable(c, arquivo(c));
                if (d == null) d = cor(Prefs.fundoCor(c));
                break;
            case 2:
                d = arquivoComoDrawable(c, arquivoDescanso(c));
                break;
            case 3:
                d = cor(Prefs.descansoCor(c));
                break;
            default:
        }
        return d != null ? d : cor(0);
    }

    static boolean temFoto(Context c) {
        return arquivo(c).isFile();
    }

    /** Gradiente da cor escolhida; null = fundo padrao do tema. */
    static int[] gradiente(Context c) {
        int i = Prefs.fundoCor(c);
        return i > 0 && i < GRADIENTES.length ? GRADIENTES[i] : null;
    }

    /** A foto com um veu por cima para o texto continuar legivel; null se nao houver foto. */
    static synchronized Drawable foto(Context c) {
        File f = arquivo(c);
        if (!f.isFile()) return null;
        if (cache == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            cache = BitmapFactory.decodeFile(f.getPath(), o);
            if (cache == null) return null;
        }
        int alfa = ESCURECER_ALFA[Math.min(ESCURECER_ALFA.length - 1, Prefs.fundoEscurecer(c))];
        int veu = (alfa << 24) | (Ui.dark ? 0x000000 : 0xFFFFFF);
        BitmapDrawable d = new BitmapDrawable(c.getResources(), cache);
        return new LayerDrawable(new Drawable[]{d, new ColorDrawable(veu)});
    }

    /** Corta no centro e reduz para o tamanho da tela (nao guarda a foto original). */
    static synchronized boolean importar(Context c, File origem) {
        if (!recortar(c, origem, arquivo(c))) return false;
        cache = null;
        return true;
    }

    static synchronized boolean importarDescanso(Context c, File origem) {
        return recortar(c, origem, arquivoDescanso(c));
    }

    private static boolean recortar(Context c, File origem, File destino) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(origem.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        int amostra = 1;
        while (o.outWidth / (amostra * 2) >= w && o.outHeight / (amostra * 2) >= h) amostra *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = amostra;
        Bitmap src;
        try {
            src = BitmapFactory.decodeFile(origem.getPath(), d);
        } catch (OutOfMemoryError e) {
            return false;
        }
        if (src == null) return false;
        float escala = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(w / escala), ch = Math.round(h / escala);
        int x = (src.getWidth() - cw) / 2, y = (src.getHeight() - ch) / 2;
        Bitmap saida = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new Canvas(saida).drawBitmap(src, new Rect(x, y, x + cw, y + ch), new Rect(0, 0, w, h), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        File tmp = new File(c.getFilesDir(), "fundo.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            saida.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            return false;
        } finally {
            saida.recycle();
        }
        return tmp.renameTo(destino);
    }

    static synchronized void remover(Context c) {
        //noinspection ResultOfMethodCallIgnored
        arquivo(c).delete();
        cache = null;
    }

    /** Fotos da memoria do projetor e de pendrives, das mais novas para as mais antigas. */
    static List<File> fotosDoAparelho() {
        List<File> achadas = new ArrayList<>();
        File sd = Environment.getExternalStorageDirectory();
        for (String d : new String[]{Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_DOWNLOADS}) {
            buscar(new File(sd, d), 3, achadas);
        }
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File v : volumes) {
                if (!v.getName().equals("emulated") && !v.getName().equals("self")) buscar(v, 3, achadas);
            }
        }
        achadas.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return achadas.size() > MAX_FOTOS ? new ArrayList<>(achadas.subList(0, MAX_FOTOS)) : achadas;
    }

    private static void buscar(File dir, int fundo, List<File> out) {
        File[] l = dir.listFiles();
        if (l == null) return;
        Arrays.sort(l);
        for (File f : l) {
            if (out.size() >= MAX_FOTOS * 4) return;
            if (f.isDirectory()) {
                if (fundo > 0 && !f.getName().startsWith(".")) buscar(f, fundo - 1, out);
            } else {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f);
            }
        }
    }

    static GradientDrawable gradienteDrawable(int[] cores) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, cores);
    }
}
