package android.content.res;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
/** JVM test stub: serves files from a directory the way Android serves assets/. */
public class AssetManager {
    private final File base;
    public AssetManager(File base) { this.base = base; }
    public InputStream open(String name) throws IOException {
        return new FileInputStream(new File(base, name));
    }
}
