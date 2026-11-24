package pe.vision2020.puenteyape;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int VERDE = 0xFF188038, ROJO = 0xFFD93025, AMBAR = 0xFFE37400, GRIS = 0xFF9AA0A6;

    private TextView txtPermiso, txtPc, txtCola, txtAyuda;
    private View puntoPermiso, puntoPc, puntoCola;
    private Button btnPermiso, btnBateria, btnProbar;
    private EditText edUrl, edToken, edSecreto;
    private Switch swActivo, swYape, swPlin;
    private LinearLayout lista;

    // referencia fuerte: Android solo guarda referencias débiles de este oyente
    private final SharedPreferences.OnSharedPreferenceChangeListener cambios =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String k) {
                    runOnUiThread(new Runnable() { @Override public void run() { refrescar(); } });
                }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        txtPermiso = findViewById(R.id.txtPermiso);
        txtPc = findViewById(R.id.txtPc);
        txtCola = findViewById(R.id.txtCola);
        txtAyuda = findViewById(R.id.txtAyudaPermiso);
        puntoPermiso = findViewById(R.id.puntoPermiso);
        puntoPc = findViewById(R.id.puntoPc);
        puntoCola = findViewById(R.id.puntoCola);
        btnPermiso = findViewById(R.id.btnPermiso);
        btnBateria = findViewById(R.id.btnBateria);
        btnProbar = findViewById(R.id.btnProbar);
        edUrl = findViewById(R.id.edUrl);
        edToken = findViewById(R.id.edToken);
        edSecreto = findViewById(R.id.edSecreto);
        swActivo = findViewById(R.id.swActivo);
        swYape = findViewById(R.id.swYape);
        swPlin = findViewById(R.id.swPlin);
        lista = findViewById(R.id.listaActividad);

        edUrl.setText(Ajustes.url(this));
        edToken.setText(Ajustes.token(this));
        edSecreto.setText(Ajustes.secreto(this));
        swActivo.setChecked(Ajustes.activo(this));
        swYape.setChecked(Ajustes.yape(this));
        swPlin.setChecked(Ajustes.plin(this));

        CompoundButton.OnCheckedChangeListener opc = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) {
                Ajustes.prefs(MainActivity.this).edit()
                        .putBoolean("activo", swActivo.isChecked())
                        .putBoolean("yape", swYape.isChecked())
                        .putBoolean("plin", swPlin.isChecked()).apply();
            }
        };
        swActivo.setOnCheckedChangeListener(opc);
        swYape.setOnCheckedChangeListener(opc);
        swPlin.setOnCheckedChangeListener(opc);

        btnPermiso.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { abrirPermiso(); }
        });
        btnBateria.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pedirBateria(); }
        });
        btnProbar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ajustes.guardarConexion(MainActivity.this, edUrl.getText().toString(), edToken.getText().toString());
                String sec = edSecreto.getText().toString().trim();
                if (!sec.isEmpty() && !Cripto.valido(sec)) {
                    Toast.makeText(MainActivity.this, "El código de internet no es válido", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!sec.equals(Ajustes.secreto(MainActivity.this))) {
                    Ajustes.guardarInternet(MainActivity.this, sec, Ajustes.prefs(MainActivity.this).getString("servidor", ""));
                }
                edUrl.setText(Ajustes.url(MainActivity.this));
                probar();
            }
        });
        findViewById(R.id.btnReintentar).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Envio.enviarPendientes(MainActivity.this);
                Toast.makeText(MainActivity.this, "Reenviando pendientes…", Toast.LENGTH_SHORT).show();
            }
        });
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.txtVersion)).setText("VISIÓN 20/20 · Puente Yape v" + ver);
        procesarEnlace(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        procesarEnlace(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ajustes.prefs(this).registerOnSharedPreferenceChangeListener(cambios);
        if (tienePermiso() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {   // vuelve a conectar el servicio si el sistema lo había detenido
                NotificationListenerService.requestRebind(new ComponentName(this, OyenteNotificaciones.class));
            } catch (Exception ignored) { }
        }
        refrescar();
    }

    @Override
    protected void onPause() {
        Ajustes.prefs(this).unregisterOnSharedPreferenceChangeListener(cambios);
        super.onPause();
    }

    // ---------------------------------------------------------------- enlace por QR: vision2020://enlace?url=..&token=..
    private void procesarEnlace(Intent i) {
        if (i == null || i.getData() == null) return;
        Uri d = i.getData();
        if (!"vision2020".equals(d.getScheme())) return;
        String url = d.getQueryParameter("u");
        if (TextUtils.isEmpty(url)) url = d.getQueryParameter("url");
        String tok = d.getQueryParameter("t");
        if (TextUtils.isEmpty(tok)) tok = d.getQueryParameter("token");
        String sec = d.getQueryParameter("s");
        String srv = d.getQueryParameter("n");
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(tok)) {
            Toast.makeText(this, "El código QR no es válido", Toast.LENGTH_LONG).show();
            return;
        }
        Ajustes.guardarConexion(this, url, tok);
        Ajustes.guardarInternet(this, sec != null && Cripto.valido(sec) ? sec : "", srv == null ? "" : srv);
        edUrl.setText(Ajustes.url(this));
        edToken.setText(Ajustes.token(this));
        edSecreto.setText(Ajustes.secreto(this));
        i.setData(null);
        Toast.makeText(this, "Datos de la PC recibidos. Probando…", Toast.LENGTH_SHORT).show();
        probar();
    }

    private void probar() {
        btnProbar.setEnabled(false);
        btnProbar.setText("Probando…");
        Envio.probar(this, new Envio.Resultado() {
            @Override public void listo(boolean ok, String msg) {
                btnProbar.setEnabled(true);
                btnProbar.setText("Guardar y probar conexión");
                refrescar();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(ok ? "¡Conectado!" : "Sin conexión")
                        .setMessage(ok ? msg + "\n\nLos pagos de Yape que lleguen a este celular aparecerán en la PC."
                                : msg + "\n\n• El programa debe estar abierto en la PC.\n"
                                + "• Por WiFi: celular y PC en la misma red; permita el programa en el Firewall de Windows.\n"
                                + "• Por internet: la PC debe tener internet y la opción «Recibir también por internet» activa.")
                        .setPositiveButton("Aceptar", null)
                        .show();
            }
        });
    }

    // ---------------------------------------------------------------- permisos
    private boolean tienePermiso() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        ComponentName yo = new ComponentName(this, OyenteNotificaciones.class);
        for (String parte : s.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(parte);
            if (c != null && c.equals(yo)) return true;
        }
        return false;
    }

    private void abrirPermiso() {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, OyenteNotificaciones.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(this, "Si dice «Ajuste restringido», lea la ayuda amarilla de la app", Toast.LENGTH_LONG).show();
        }
    }

    private boolean bateriaLibre() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void pedirBateria() {
        if (bateriaLibre()) {
            Toast.makeText(this, "Listo: la app no tiene restricciones de batería", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- pantalla
    private void punto(View v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void refrescar() {
        boolean permiso = tienePermiso();
        punto(puntoPermiso, permiso ? VERDE : ROJO);
        txtPermiso.setText(permiso ? "Acceso a notificaciones: concedido" : "Falta el acceso a las notificaciones");
        btnPermiso.setVisibility(permiso ? View.GONE : View.VISIBLE);
        if (!permiso && Build.VERSION.SDK_INT >= 33) {
            txtAyuda.setVisibility(View.VISIBLE);
            txtAyuda.setText("¿Android no le deja activarlo («Ajuste restringido»)?\n"
                    + "1. Abra Ajustes → Aplicaciones → Puente Yape.\n"
                    + "2. Toque ⋮ (arriba a la derecha) → «Permitir ajustes restringidos».\n"
                    + "3. Vuelva aquí y toque «Dar acceso a las notificaciones».");
            txtAyuda.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            });
        } else {
            txtAyuda.setVisibility(View.GONE);
        }
        btnBateria.setText(bateriaLibre() ? "Batería: sin restricciones ✓" : "Evitar que el celular la cierre (batería)");

        SharedPreferences p = Ajustes.prefs(this);
        String via = Ajustes.internet(this) ? (Ajustes.url(this).isEmpty() ? " (internet)" : " (WiFi + internet)") : " (solo WiFi)";
        if (Ajustes.url(this).isEmpty() && !Ajustes.internet(this)) {
            punto(puntoPc, GRIS);
            txtPc.setText("PC sin configurar · escanee el QR de la PC");
        } else if (p.getLong("pc_t", 0) == 0) {
            punto(puntoPc, GRIS);
            txtPc.setText("PC enlazada" + via + " · sin probar");
        } else {
            boolean ok = p.getBoolean("pc_ok", false);
            punto(puntoPc, ok ? VERDE : ROJO);
            txtPc.setText(p.getString("pc_txt", "") + via + " · " + hora(p.getLong("pc_t", 0)));
        }
        int n = Ajustes.cola(this).length();
        punto(puntoCola, n == 0 ? VERDE : AMBAR);
        txtCola.setText(n == 0 ? "Sin avisos pendientes" : n + (n == 1 ? " aviso pendiente de enviar" : " avisos pendientes de enviar"));
        if (!Ajustes.activo(this)) {
            punto(puntoCola, GRIS);
            txtCola.setText("Puente desactivado");
        }
        pintarActividad();
    }

    private static String hora(long t) {
        if (t <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        return f.format(new Date(t));
    }

    private void pintarActividad() {
        lista.removeAllViews();
        JSONArray a = Ajustes.actividad(this);
        if (a.length() == 0) {
            TextView t = new TextView(this);
            t.setText("Aún no llegan pagos. Cuando reciba un Yape aparecerá aquí.");
            t.setTextColor(0xFF5F6368);
            t.setTextSize(14);
            lista.addView(t);
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.VERTICAL);
            fila.setPadding(0, dp(10), 0, dp(10));
            TextView t1 = new TextView(this);
            t1.setText(o.optString("titulo") + "   ·   " + hora(o.optLong("t")));
            t1.setTextSize(15);
            t1.setTextColor(0xFF13337A);
            t1.setTypeface(t1.getTypeface(), android.graphics.Typeface.BOLD);
            TextView t2 = new TextView(this);
            t2.setText(o.optString("detalle"));
            t2.setTextSize(13);
            t2.setTextColor(0xFF5F6368);
            t2.setMaxLines(2);
            t2.setEllipsize(TextUtils.TruncateAt.END);
            TextView t3 = new TextView(this);
            String est = o.optString("estado");
            t3.setText(est);
            t3.setTextSize(12);
            t3.setTextColor(est.startsWith("Recibido") ? VERDE : (est.startsWith("Enviado") || est.startsWith("Enviando")) ? GRIS : AMBAR);
            fila.addView(t1);
            fila.addView(t2);
            fila.addView(t3);
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(0xFFE6E9EF);
                lista.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
            lista.addView(fila);
        }
    }
}
