"""Executa a logica real de favoritos com armazenamento e apps locais em memoria."""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = {
    'android/content/Context.java': '''package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE = 0;
 public abstract SharedPreferences getSharedPreferences(String name, int mode);
}''',
    'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 String getString(String k, String d); boolean getBoolean(String k, boolean d); int getInt(String k, int d); long getLong(String k, long d);
 Editor edit();
 interface Editor {
 Editor putString(String k, String v); Editor putBoolean(String k, boolean v); Editor putInt(String k, int v); Editor putLong(String k, long v);
 Editor remove(String k); boolean commit(); void apply();
 }
}''',
    'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager { public static final int COMPONENT_ENABLED_STATE_DEFAULT = 0; }''',
    'com/mlab/projetor/Apps.java': '''package com.mlab.projetor;
final class Apps {
 static final String TV_PKG = "tv.aovivo";
 static Object entry(android.content.Context c, String p) {
 return p.equals(TV_PKG) || p.equals("com.globo.globotv") || p.startsWith("app") ? p : null;
 }
}''',
    'com/mlab/projetor/FavoritesTest.java': '''package com.mlab.projetor;
import android.content.*;
import java.util.*;
public class FavoritesTest extends Context implements SharedPreferences, SharedPreferences.Editor {
 Map<String,Object> data = new HashMap<>();
 public SharedPreferences getSharedPreferences(String n,int m) { return this; }
 public String getString(String k,String d) { return (String)data.getOrDefault(k,d); }
 public boolean getBoolean(String k,boolean d) { return (Boolean)data.getOrDefault(k,d); }
 public int getInt(String k,int d) { return (Integer)data.getOrDefault(k,d); }
 public long getLong(String k,long d) { return (Long)data.getOrDefault(k,d); }
 public SharedPreferences.Editor edit() { return this; }
 public SharedPreferences.Editor putString(String k,String v) { data.put(k,v); return this; }
 public SharedPreferences.Editor putBoolean(String k,boolean v) { data.put(k,v); return this; }
 public SharedPreferences.Editor putInt(String k,int v) { data.put(k,v); return this; }
 public SharedPreferences.Editor putLong(String k,long v) { data.put(k,v); return this; }
 public SharedPreferences.Editor remove(String k) { data.remove(k); return this; }
 public boolean commit() { return true; } public void apply() {}
 static void check(boolean ok) { if (!ok) throw new AssertionError(); }
 public static void main(String[] args) {
 FavoritesTest c = new FavoritesTest();
 // Graficos visiveis por padrao; a escolha e preservada ao recriar o contexto.
 check(Prefs.showHardware(c));
 Prefs.setShowHardware(c,false); check(!Prefs.showHardware(c));
 FavoritesTest restored = new FavoritesTest(); restored.data = c.data;
 check(!Prefs.showHardware(restored));
 Prefs.setShowHardware(restored,true); check(Prefs.showHardware(c));
 check(Prefs.downloadKbps(c)==-1); check(Prefs.downloadCheckedAt(c).isEmpty());
 Prefs.setDownloadResult(c,25500,"29/10 12:00");
 check(Prefs.downloadKbps(restored)==25500);
 check(Prefs.downloadCheckedAt(restored).equals("29/10 12:00"));
 check(Prefs.downloadAttemptAt(c)==0);
 Prefs.setDownloadAttemptAt(c,123456789L); check(Prefs.downloadAttemptAt(restored)==123456789L);
 // Dois favoritos instalados entre os oito padroes; seis novos devem caber sem substituicao.
 for (int i=1;i<=6;i++) check(Prefs.toggleFav(c,"app"+i));
 List<String> eight = Prefs.favs(c);
 check(eight.size()==8);
 check(eight.contains("com.globo.globotv") && eight.contains(Apps.TV_PKG));
 for (int i=1;i<=6;i++) check(eight.contains("app"+i));
 check(!Prefs.toggleFav(c,"app3")); check(Prefs.favs(c).size()==7);
 check(Prefs.toggleFav(c,"app3")); check(Prefs.favs(c).size()==8);
 // Preserva o contrato existente ao adicionar o nono: substitui o ultimo favorito.
 check(Prefs.toggleFav(c,"app7")); check(Prefs.favs(c).size()==8);
 check(!Prefs.favs(c).contains("app3")); check(Prefs.favs(c).contains("app7"));
 List<String> reordered = Prefs.favs(c);
 Collections.swap(reordered, 0, 1);
 Prefs.reorderFavs(c, reordered);
 check(Prefs.favs(c).equals(reordered));
 // Apps temporariamente ausentes permanecem guardados ao reordenar os visiveis.
 c.data.put("favs", "app1,missing,app2");
 Prefs.reorderFavs(c, Arrays.asList("app2", "app1"));
 check(Prefs.favs(c).equals(Arrays.asList("app2", "missing", "app1")));
 Prefs.reorderFavs(c, Arrays.asList("app1"));
 check(Prefs.favs(c).equals(Arrays.asList("app2", "missing", "app1")));
 System.out.println("Favoritos e preferencia persistente dos graficos OK");
 }
}''',
}
with tempfile.TemporaryDirectory() as tmp:
    folder = pathlib.Path(tmp)
    for name, source in SOURCES.items():
        target = folder / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
    subprocess.run(['javac', '-d', tmp, *map(str, folder.rglob('*.java')),
                    str(ROOT / 'src/com/mlab/projetor/Prefs.java')], check=True)
    subprocess.run(['java', '-cp', tmp, 'com.mlab.projetor.FavoritesTest'], check=True)
