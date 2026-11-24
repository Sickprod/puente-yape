package pe.vision2020.puenteyape;

import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado de los avisos que viajan por internet (idéntico al de nube.py en la PC).
 * secreto S → tema = "v2020-" + hex(HMAC(S,"tema"))[:24] ; Ke = HMAC(S,"cifrado") ; Km = HMAC(S,"firma")
 * flujo = HMAC(Ke, nonce‖contador) ; cifrado = datos XOR flujo ; sello = HMAC(Km,"v1"‖nonce‖cifrado)[:16]
 * mensaje = "v1." + base64url(nonce‖cifrado‖sello)
 */
final class Cripto {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom AZAR = new SecureRandom();

    final String tema;
    private final byte[] ke, km;

    Cripto(String secreto) throws Exception {
        byte[] s = Base64.getUrlDecoder().decode(secreto.trim());
        byte[] t = hmac(s, "tema".getBytes(UTF8));
        StringBuilder hex = new StringBuilder("v2020-");
        for (int i = 0; i < 12; i++) hex.append(String.format("%02x", t[i] & 0xff));
        tema = hex.toString();
        ke = hmac(s, "cifrado".getBytes(UTF8));
        km = hmac(s, "firma".getBytes(UTF8));
    }

    static boolean valido(String secreto) {
        try {
            return secreto != null && Base64.getUrlDecoder().decode(secreto.trim()).length >= 16;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hmac(byte[] k, byte[] m) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(k, "HmacSHA256"));
        return mac.doFinal(m);
    }

    private byte[] flujo(byte[] nonce, int n) throws Exception {
        byte[] out = new byte[n];
        int pos = 0, c = 0;
        while (pos < n) {
            byte[] bloque = new byte[nonce.length + 4];
            System.arraycopy(nonce, 0, bloque, 0, nonce.length);
            bloque[nonce.length] = (byte) (c >>> 24);
            bloque[nonce.length + 1] = (byte) (c >>> 16);
            bloque[nonce.length + 2] = (byte) (c >>> 8);
            bloque[nonce.length + 3] = (byte) c;
            byte[] h = hmac(ke, bloque);
            int k = Math.min(h.length, n - pos);
            System.arraycopy(h, 0, out, pos, k);
            pos += k;
            c++;
        }
        return out;
    }

    private byte[] sello(byte[] nonce, byte[] ct) throws Exception {
        byte[] m = new byte[2 + nonce.length + ct.length];
        m[0] = 'v';
        m[1] = '1';
        System.arraycopy(nonce, 0, m, 2, nonce.length);
        System.arraycopy(ct, 0, m, 2 + nonce.length, ct.length);
        return Arrays.copyOf(hmac(km, m), 16);
    }

    String cifrar(JSONObject obj) throws Exception {
        return cifrarTexto(obj.toString());
    }

    String cifrarTexto(String texto) throws Exception {
        byte[] datos = texto.getBytes(UTF8);
        byte[] nonce = new byte[16];
        AZAR.nextBytes(nonce);
        byte[] f = flujo(nonce, datos.length);
        byte[] ct = new byte[datos.length];
        for (int i = 0; i < datos.length; i++) ct[i] = (byte) (datos[i] ^ f[i]);
        byte[] s = sello(nonce, ct);
        byte[] todo = new byte[16 + ct.length + 16];
        System.arraycopy(nonce, 0, todo, 0, 16);
        System.arraycopy(ct, 0, todo, 16, ct.length);
        System.arraycopy(s, 0, todo, 16 + ct.length, 16);
        return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(todo);
    }

    /** null si no es auténtico. */
    JSONObject descifrar(String txt) {
        String t = descifrarTexto(txt);
        if (t == null) return null;
        try {
            return new JSONObject(t);
        } catch (Exception e) {
            return null;
        }
    }

    String descifrarTexto(String txt) {
        try {
            if (txt == null || !txt.startsWith("v1.") || txt.length() > 4000) return null;
            byte[] raw = Base64.getUrlDecoder().decode(txt.substring(3));
            if (raw.length < 33) return null;
            byte[] nonce = Arrays.copyOfRange(raw, 0, 16);
            byte[] ct = Arrays.copyOfRange(raw, 16, raw.length - 16);
            byte[] s = Arrays.copyOfRange(raw, raw.length - 16, raw.length);
            if (!MessageDigest.isEqual(s, sello(nonce, ct))) return null;
            byte[] f = flujo(nonce, ct.length);
            byte[] datos = new byte[ct.length];
            for (int i = 0; i < ct.length; i++) datos[i] = (byte) (ct[i] ^ f[i]);
            return new String(datos, UTF8);
        } catch (Exception e) {
            return null;
        }
    }
}
