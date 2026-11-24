package pe.vision2020.puenteyape;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Configuración y datos guardados en el celular (cola de envío y actividad reciente). */
final class Ajustes {
    static final String ARCHIVO = "puente";
    static final int MAX_COLA = 300;
    static final int MAX_ACTIVIDAD = 25;
    static final long VIGENCIA_COLA_MS = 48L * 3600 * 1000;   // avisos sin enviar se guardan 48 h

    private Ajustes() { }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
    }

    static final String SERVIDOR_DEFECTO = "https://ntfy.sh";

    static String url(Context c) { return prefs(c).getString("url", ""); }
    static String secreto(Context c) { return prefs(c).getString("secreto", ""); }
    static String servidor(Context c) {
        String s = prefs(c).getString("servidor", "");
        return s.isEmpty() ? SERVIDOR_DEFECTO : s;
    }
    static boolean internet(Context c) { return Cripto.valido(secreto(c)); }
    static String token(Context c) { return prefs(c).getString("token", ""); }
    static boolean activo(Context c) { return prefs(c).getBoolean("activo", true); }
    static boolean yape(Context c) { return prefs(c).getBoolean("yape", true); }
    static boolean plin(Context c) { return prefs(c).getBoolean("plin", true); }

    static void guardarConexion(Context c, String url, String token) {
        prefs(c).edit().putString("url", normalizarUrl(url)).putString("token", token == null ? "" : token.trim()).apply();
    }

    static void guardarInternet(Context c, String secreto, String servidor) {
        String sv = servidor == null ? "" : servidor.trim();
        while (sv.endsWith("/")) sv = sv.substring(0, sv.length() - 1);
        prefs(c).edit().putString("secreto", secreto == null ? "" : secreto.trim()).putString("servidor", sv)
                .putString("ack_desde", "").apply();
    }

    /** "192.168.1.5" -> "http://192.168.1.5:8765" ; quita la barra final. */
    static String normalizarUrl(String u) {
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String sinEsquema = u.substring(u.indexOf("://") + 3);
        if (!sinEsquema.contains(":")) u = u + ":8765";
        return u;
    }

    // ---------------------------------------------------------------- estado de la PC
    static void estadoPc(Context c, boolean ok, String texto) {
        prefs(c).edit().putBoolean("pc_ok", ok).putString("pc_txt", texto).putLong("pc_t", System.currentTimeMillis()).apply();
    }

    // ---------------------------------------------------------------- cola de envío
    static synchronized JSONArray cola(Context c) {
        try {
            return new JSONArray(prefs(c).getString("cola", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static synchronized void guardarCola(Context c, JSONArray a) {
        prefs(c).edit().putString("cola", a.toString()).apply();
    }

    static synchronized void encolar(Context c, JSONObject aviso) {
        JSONArray a = cola(c);
        JSONArray n = new JSONArray();
        long ahora = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && ahora - o.optLong("t", ahora) < VIGENCIA_COLA_MS) n.put(o);
        }
        n.put(aviso);
        while (n.length() > MAX_COLA) n.remove(0);
        guardarCola(c, n);
    }

    /** Marca cuándo se publicó por internet (para no repetir antes de 6 h). */
    static synchronized void marcarNube(Context c, String id, long t) {
        JSONArray a = cola(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try {
                    o.put("nube_t", t);
                } catch (JSONException ignored) { }
            }
        }
        guardarCola(c, a);
    }

    static synchronized void quitarDeCola(Context c, String id) {
        JSONArray a = cola(c);
        JSONArray n = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) n.put(o);
        }
        guardarCola(c, n);
    }

    // ---------------------------------------------------------------- actividad reciente (para la pantalla)
    static synchronized JSONArray actividad(Context c) {
        try {
            return new JSONArray(prefs(c).getString("actividad", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static synchronized void registrarActividad(Context c, String id, String titulo, String detalle, String estado) {
        JSONArray a = actividad(c);
        JSONArray n = new JSONArray();
        boolean hallado = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) {
                try {
                    o.put("estado", estado);
                    if (titulo != null) o.put("titulo", titulo);
                    if (detalle != null) o.put("detalle", detalle);
                } catch (JSONException ignored) { }
                hallado = true;
            }
            n.put(o);
        }
        if (!hallado) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("t", System.currentTimeMillis());
                o.put("titulo", titulo == null ? "" : titulo);
                o.put("detalle", detalle == null ? "" : detalle);
                o.put("estado", estado);
            } catch (JSONException ignored) { }
            JSONArray m = new JSONArray();
            m.put(o);
            for (int i = 0; i < n.length() && m.length() < MAX_ACTIVIDAD; i++) m.put(n.optJSONObject(i));
            n = m;
        }
        prefs(c).edit().putString("actividad", n.toString()).apply();
    }
}
