package android.content;
import android.content.res.AssetManager;
import java.io.File;
/** JVM test stub: assets dir + one in-memory prefs store. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final AssetManager assets;
    private final SharedPreferences prefs;
    public Context(File assetsBase, SharedPreferences prefs) {
        this.assets = new AssetManager(assetsBase);
        this.prefs = prefs;
    }
    public AssetManager getAssets() { return assets; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
}
