package android.content;
import java.util.HashMap;
import java.util.Map;
/** JVM test stub: in-memory SharedPreferences. */
public class SharedPreferences {
    private final Map<String, Object> map = new HashMap<String, Object>();
    public void seed(String key, Object value) { map.put(key, value); } // test-only
    public String getString(String key, String def) {
        Object v = map.get(key);
        return v == null ? def : (String) v;
    }
    public int getInt(String key, int def) {
        Object v = map.get(key);
        return v == null ? def : ((Integer) v).intValue();
    }
    public Editor edit() { return new Editor(); }
    public class Editor {
        public Editor putString(String key, String v) { map.put(key, v); return this; }
        public Editor putInt(String key, int v) { map.put(key, Integer.valueOf(v)); return this; }
        public void apply() { }
    }
}
