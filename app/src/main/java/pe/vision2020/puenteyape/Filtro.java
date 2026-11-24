package pe.vision2020.puenteyape;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decide qué notificaciones son pagos RECIBIDOS de Yape o Plin. El programa de la PC hace la lectura final. */
final class Filtro {
    static final String PAQUETE_YAPE = "com.bcp.innovacxion.yapeapp";

    private static final Pattern MONTO = Pattern.compile("S\\s*/\\s*\\.?\\s*([0-9][0-9.,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENVIADO_POR_MI = Pattern.compile(
            "\\b(yapeaste|enviaste|pagaste|realizaste|transferiste)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECIBIDO = Pattern.compile(
            "(te\\s+(envi[oó]|yape[oó]|transfiri[oó]|pag[oó]|plin)|recibiste|te\\s+yapearon|has\\s+recibido|te\\s+lleg[oó])",
            Pattern.CASE_INSENSITIVE);

    /** Solo se aceptan avisos de Plin desde apps de bancos y cajas (nunca de WhatsApp, SMS, correo, etc.). */
    private static final String[] BANCOS = {"interbank", "bbva", "scotia", "banbif", "pichincha", "caja", "mibanco",
            "bancodelanacion", "bn.pe", "bcp", "plin", "financiera", "compartamos", "alfin", "ripley", "falabella"};

    private Filtro() { }

    static boolean esBanco(String paquete) {
        if (paquete == null) return false;
        String p = paquete.toLowerCase(Locale.ROOT);
        for (String b : BANCOS) if (p.contains(b)) return true;
        return false;
    }

    static boolean esYape(String paquete) {
        return paquete != null && (PAQUETE_YAPE.equals(paquete) || paquete.toLowerCase(Locale.ROOT).contains("yape"));
    }

    /** Devuelve "Yape", "Plin" o null si el aviso no interesa. */
    static String medio(String paquete, String texto, boolean yape, boolean plin) {
        if (texto == null || texto.trim().isEmpty()) return null;
        String low = texto.toLowerCase(Locale.ROOT);
        if (!MONTO.matcher(texto).find()) return null;
        if (ENVIADO_POR_MI.matcher(low).find()) return null;
        if (esYape(paquete)) {
            return yape && RECIBIDO.matcher(low).find() ? "Yape" : null;
        }
        if (!esBanco(paquete)) return null;                    // seguridad: un chat no puede "crear" pagos
        if (plin && low.contains("plin") && RECIBIDO.matcher(low).find()) return "Plin";
        if (yape && low.contains("yape") && RECIBIDO.matcher(low).find()) return "Yape";   // Yape avisado por la app del BCP
        return null;
    }

    /** "S/ 25.50" para mostrar en la lista. */
    static String monto(String texto) {
        Matcher m = MONTO.matcher(texto == null ? "" : texto);
        return m.find() ? "S/ " + m.group(1).replaceAll("[.,]$", "") : "";
    }
}
