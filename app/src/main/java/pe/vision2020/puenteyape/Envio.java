package pe.vision2020.puenteyape;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envía los avisos a la PC.
 *  1) Por WiFi (directo a http://IP:puerto/yape) — inmediato; la respuesta 200 es la confirmación.
 *  2) Si no hay WiFi con la PC, por INTERNET: se publica cifrado en ntfy y la PC confirma después.
 * Ningún aviso sale de la cola hasta que la PC lo confirma.
 */
final class Envio {
    interface Resultado { void listo(boolean ok, String mensaje); }

    private static final ExecutorService HILO = Executors.newSingleThreadExecutor();
    private static final Handler PRINCIPAL = new Handler(Looper.getMainLooper());
    private static final int TIEMPO_WIFI_MS = 3500;
    private static final int TIEMPO_NUBE_MS = 15000;
    private static final long REPUBLICAR_MS = 6L * 3600 * 1000;   // si la PC no confirma, se vuelve a publicar a las 6 h
    private static final long LEER_CONFIRMACIONES_MS = 20_000;     // como máximo cada 20 s (límites del servidor gratuito)

    private Envio() { }

    static String equipo() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String mo = Build.MODEL == null ? "" : Build.MODEL;
        String r = mo.toLowerCase().startsWith(m.toLowerCase()) ? mo : (m + " " + mo);
        return r.trim().isEmpty() ? "Celular" : r.trim();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String leer(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < 262144) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    static String explicarError(Exception e) {
        String t = e.getClass().getSimpleName();
        if (t.contains("Timeout")) return "La PC no responde";
        if (t.contains("ConnectException") || t.contains("NoRouteToHost")) return "La PC no está en esta red";
        if (t.contains("UnknownHost")) return "Sin internet";
        return "Sin conexión (" + t + ")";
    }

    private static HttpURLConnection abrir(String url, int tiempo) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(tiempo);
        con.setReadTimeout(tiempo);
        con.setRequestProperty("User-Agent", "VISION2020-PuenteYape/2");
        return con;
    }

    private static int post(HttpURLConnection con, byte[] datos, String tipo) throws Exception {
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", tipo);
        con.setFixedLengthStreamingMode(datos.length);
        OutputStream os = con.getOutputStream();
        os.write(datos);
        os.close();
        return con.getResponseCode();
    }

    // ================================================================ prueba de conexión
    static void probar(final Context ctx, final Resultado r) {
        final Context c = ctx.getApplicationContext();
        HILO.execute(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                StringBuilder msg = new StringBuilder();
                String base = Ajustes.url(c);
                // 1) WiFi
                if (!base.isEmpty()) {
                    HttpURLConnection con = null;
                    try {
                        con = abrir(base + "/ping?token=" + enc(Ajustes.token(c)) + "&equipo=" + enc(equipo()), TIEMPO_WIFI_MS);
                        int code = con.getResponseCode();
                        if (code == 200) {
                            JSONObject j = new JSONObject(leer(con.getInputStream()));
                            ok = j.optBoolean("ok", false);
                            msg.append(ok ? "WiFi: conectado a " + j.optString("negocio", "la PC") : "WiFi: respuesta inesperada");
                        } else if (code == 401) {
                            msg.append("WiFi: clave incorrecta (vuelva a escanear el QR)");
                        } else {
                            msg.append("WiFi: la PC respondió ").append(code).append(" (actualice el programa a la v14.3)");
                        }
                    } catch (Exception e) {
                        msg.append("WiFi: ").append(explicarError(e));
                    } finally {
                        if (con != null) con.disconnect();
                    }
                }
                // 2) Internet
                if (Ajustes.internet(c)) {
                    if (msg.length() > 0) msg.append("\n");
                    String rn = probarInternet(c);
                    if (rn.startsWith("OK:")) {
                        ok = true;
                        msg.append("Internet: conectado a ").append(rn.substring(3));
                    } else {
                        msg.append("Internet: ").append(rn);
                    }
                } else if (base.isEmpty()) {
                    msg.append("Falta enlazar con la PC (escanee el QR)");
                }
                Ajustes.estadoPc(c, ok, ok ? "Conectado" : msg.toString().replace("\n", " · "));
                if (ok) enviarPendientesAhora(c, true);
                final boolean fOk = ok;
                final String fMsg = msg.toString();
                if (r != null) PRINCIPAL.post(new Runnable() { @Override public void run() { r.listo(fOk, fMsg); } });
            }
        });
    }

    /** Publica un "ping" cifrado y espera la respuesta "pong" de la PC (hasta ~16 s). */
    private static String probarInternet(Context c) {
        try {
            Cripto k = new Cripto(Ajustes.secreto(c));
            String srv = Ajustes.servidor(c);
            String ref = UUID.randomUUID().toString().substring(0, 12);
            JSONObject ping = new JSONObject();
            ping.put("tipo", "ping");
            ping.put("id", ref);
            ping.put("equipo", equipo());
            ping.put("t", System.currentTimeMillis());
            HttpURLConnection con = abrir(srv + "/" + k.tema, TIEMPO_NUBE_MS);
            int code = post(con, k.cifrar(ping).getBytes("UTF-8"), "text/plain");
            con.disconnect();
            if (code == 429) return "el servidor pide esperar un momento (límite gratuito)";
            if (code < 200 || code >= 300) return "el servidor respondió " + code;
            for (int i = 0; i < 8; i++) {
                Thread.sleep(2000);
                HttpURLConnection g = abrir(srv + "/" + k.tema + "-r/json?poll=1&since=2m", TIEMPO_NUBE_MS);
                if (g.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(g.getInputStream(), "UTF-8"));
                    String linea;
                    while ((linea = br.readLine()) != null) {
                        JSONObject ev = new JSONObject(linea);
                        JSONObject m = k.descifrar(ev.optString("message"));
                        if (m != null && "pong".equals(m.optString("tipo")) && ref.equals(m.optString("ref"))) {
                            br.close();
                            g.disconnect();
                            return "OK:" + m.optString("negocio", "la PC");
                        }
                    }
                    br.close();
                }
                g.disconnect();
            }
            return "la PC no respondió (¿programa abierto y con internet?)";
        } catch (Exception e) {
            return explicarError(e);
        }
    }

    // ================================================================ envío de la cola
    static void enviarPendientes(Context ctx) {
        final Context c = ctx.getApplicationContext();
        HILO.execute(new Runnable() {
            @Override public void run() { enviarPendientesAhora(c, false); }
        });
    }

    private static void enviarPendientesAhora(Context c, boolean forzarConfirmaciones) {
        JSONArray cola = Ajustes.cola(c);
        if (cola.length() == 0) return;
        String base = Ajustes.url(c);
        boolean wifi = !base.isEmpty();
        boolean nube = Ajustes.internet(c);
        boolean hayEsperando = false;
        Cripto k = null;
        if (nube) {
            try {
                k = new Cripto(Ajustes.secreto(c));
            } catch (Exception e) {
                nube = false;
            }
        }
        long ahora = System.currentTimeMillis();
        for (int i = 0; i < cola.length(); i++) {
            JSONObject o = cola.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            // ---- 1) WiFi directo
            if (wifi) {
                HttpURLConnection con = null;
                try {
                    con = abrir(base + "/yape?token=" + enc(Ajustes.token(c)), TIEMPO_WIFI_MS);
                    JSONObject cuerpo = new JSONObject();
                    cuerpo.put("texto", o.optString("texto"));
                    cuerpo.put("medio", o.optString("medio"));
                    cuerpo.put("id", id);
                    cuerpo.put("equipo", equipo());
                    int code = post(con, cuerpo.toString().getBytes("UTF-8"), "application/json; charset=utf-8");
                    String resp = code == 200 ? leer(con.getInputStream()).trim() : "";
                    if (code == 200) {
                        Ajustes.quitarDeCola(c, id);
                        Ajustes.registrarActividad(c, id, null, null,
                                "ignorado".equals(resp) ? "La PC no lo reconoció como pago" : "Recibido por la PC (WiFi) ✓");
                        Ajustes.estadoPc(c, true, "Conectado por WiFi");
                        continue;
                    } else if (code == 401) {
                        Ajustes.estadoPc(c, false, "Clave incorrecta: vuelva a escanear el QR");
                        wifi = false;
                    } else if (code == 429) {
                        wifi = false;                                 // la PC pide calma: se reintenta luego
                    } else {
                        wifi = false;
                    }
                } catch (Exception e) {
                    wifi = false;                                     // no hay PC en esta red: probar por internet
                } finally {
                    if (con != null) con.disconnect();
                }
            }
            // ---- 2) Internet (cifrado)
            if (nube && k != null) {
                long nt = o.optLong("nube_t", 0);
                if (nt > 0 && ahora - nt < REPUBLICAR_MS) {
                    hayEsperando = true;
                    continue;
                }
                HttpURLConnection con = null;
                try {
                    JSONObject m = new JSONObject();
                    m.put("tipo", "pago");
                    m.put("id", id);
                    m.put("texto", o.optString("texto"));
                    m.put("medio", o.optString("medio"));
                    m.put("equipo", equipo());
                    m.put("t", o.optLong("t", ahora));
                    con = abrir(Ajustes.servidor(c) + "/" + k.tema, TIEMPO_NUBE_MS);
                    int code = post(con, k.cifrar(m).getBytes("UTF-8"), "text/plain");
                    if (code >= 200 && code < 300) {
                        Ajustes.marcarNube(c, id, ahora);
                        Ajustes.registrarActividad(c, id, null, null, "Enviado por internet · esperando a la PC");
                        Ajustes.estadoPc(c, true, "Enviando por internet");
                        hayEsperando = true;
                    } else {
                        Ajustes.registrarActividad(c, id, null, null,
                                code == 429 ? "Pendiente · límite del servidor, se reintenta" : "Pendiente · error " + code);
                        nube = false;
                    }
                } catch (Exception e) {
                    Ajustes.registrarActividad(c, id, null, null, "Pendiente · " + explicarError(e));
                    Ajustes.estadoPc(c, false, explicarError(e));
                    nube = false;
                } finally {
                    if (con != null) con.disconnect();
                }
            } else if (!wifi) {
                Ajustes.registrarActividad(c, id, null, null, "Pendiente · la PC no está en esta red");
            }
        }
        if (hayEsperando && k != null) leerConfirmaciones(c, k, forzarConfirmaciones);
    }

    /** Lee las confirmaciones ("ack") que la PC publica en el tema de respuesta. */
    private static void leerConfirmaciones(Context c, Cripto k, boolean forzar) {
        long ahora = System.currentTimeMillis();
        long ultima = Ajustes.prefs(c).getLong("ack_t", 0);
        if (!forzar && ahora - ultima < LEER_CONFIRMACIONES_MS) return;
        Ajustes.prefs(c).edit().putLong("ack_t", ahora).apply();
        String desde = Ajustes.prefs(c).getString("ack_desde", "");
        HttpURLConnection g = null;
        try {
            g = abrir(Ajustes.servidor(c) + "/" + k.tema + "-r/json?poll=1&since=" + (desde.isEmpty() ? "12h" : enc(desde)),
                    TIEMPO_NUBE_MS);
            if (g.getResponseCode() != 200) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(g.getInputStream(), "UTF-8"));
            String linea, ultimoId = desde;
            while ((linea = br.readLine()) != null) {
                JSONObject ev;
                try {
                    ev = new JSONObject(linea);
                } catch (Exception e) {
                    continue;
                }
                if (!"message".equals(ev.optString("event"))) continue;
                ultimoId = ev.optString("id", ultimoId);
                JSONObject m = k.descifrar(ev.optString("message"));
                if (m == null || !"ack".equals(m.optString("tipo"))) continue;
                JSONArray ids = m.optJSONArray("ids");
                if (ids == null) continue;
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i);
                    Ajustes.quitarDeCola(c, id);
                    Ajustes.registrarActividad(c, id, null, null, "Recibido por la PC (internet) ✓");
                }
                Ajustes.estadoPc(c, true, "Conectado por internet");
            }
            br.close();
            Ajustes.prefs(c).edit().putString("ack_desde", ultimoId).apply();
        } catch (Exception ignored) {
            // sin internet: se intentará en la próxima vuelta
        } finally {
            if (g != null) g.disconnect();
        }
    }
}
