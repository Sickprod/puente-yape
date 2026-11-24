package pe.vision2020.puenteyape;

import android.app.Notification;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Servicio que Android avisa con cada notificación. Toma las de Yape/Plin y las pone en la cola de envío. */
public class OyenteNotificaciones extends NotificationListenerService {
    private static final long REINTENTO_MS = 30_000;
    private static final long ANTIRREBOTE_MS = 10 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> recientes = new LinkedHashMap<>();
    private final Runnable reintento = new Runnable() {
        @Override public void run() {
            if (Ajustes.cola(OyenteNotificaciones.this).length() > 0) {
                Envio.enviarPendientes(OyenteNotificaciones.this);
            }
            handler.postDelayed(this, REINTENTO_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        handler.removeCallbacks(reintento);
        handler.postDelayed(reintento, 3000);
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(reintento);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(reintento);
        super.onDestroy();
    }

    private static String cad(Bundle b, String k) {
        CharSequence cs = b.getCharSequence(k);
        return cs == null ? "" : cs.toString().trim();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            procesar(sbn);
        } catch (Exception ignored) {
            // nunca dejar caer el servicio por una notificación rara
        }
    }

    private void procesar(StatusBarNotification sbn) throws Exception {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        if (!Ajustes.activo(this)) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        if (ex == null) return;
        String titulo = cad(ex, Notification.EXTRA_TITLE);
        String texto = cad(ex, Notification.EXTRA_BIG_TEXT);
        if (texto.isEmpty()) texto = cad(ex, Notification.EXTRA_TEXT);
        if (texto.isEmpty()) {
            CharSequence[] lineas = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lineas != null) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence l : lineas) if (l != null) sb.append(l).append(' ');
                texto = sb.toString().trim();
            }
        }
        String completo = (titulo.isEmpty() ? "" : titulo + " · ") + texto;
        String paquete = sbn.getPackageName();
        String medio = Filtro.medio(paquete, completo, Ajustes.yape(this), Ajustes.plin(this));
        if (medio == null) return;

        // antirrebote: la misma notificación puede llegar varias veces al actualizarse
        String clave = paquete + "|" + completo;
        long ahora = System.currentTimeMillis();
        synchronized (recientes) {
            Iterator<Map.Entry<String, Long>> it = recientes.entrySet().iterator();
            while (it.hasNext()) if (ahora - it.next().getValue() > ANTIRREBOTE_MS) it.remove();
            if (recientes.containsKey(clave)) return;
            recientes.put(clave, ahora);
        }

        String id = Integer.toHexString((paquete + "|" + sbn.getPostTime() + "|" + completo).hashCode())
                + "-" + Long.toHexString(sbn.getPostTime());
        JSONObject aviso = new JSONObject();
        aviso.put("id", id);
        aviso.put("t", ahora);
        aviso.put("texto", completo);
        aviso.put("paquete", paquete);
        aviso.put("app", Filtro.esYape(paquete) ? "Yape" : paquete);
        aviso.put("medio", medio);
        Ajustes.encolar(this, aviso);
        String monto = Filtro.monto(completo);
        Ajustes.registrarActividad(this, id, medio + (monto.isEmpty() ? "" : " · " + monto), completo, "Enviando…");
        Envio.enviarPendientes(this);
    }
}
