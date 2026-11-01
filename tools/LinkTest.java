import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/**
 * Replays MegaPlayProvider.loadLinks outside Android:
 *   java tools/LinkTest.java <malId> <episode> <sub|dub> [userAgent]
 */
public class LinkTest {
    static final String MEGA = "https://megaplay.buzz";
    static final String REF_ANIKOTO = "https://anikotoapi.site/";
    static final String REF_PLAYER = "https://megaplay.buzz/";
    static String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0 Safari/537.36";

    static String[] get(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new String[]{String.valueOf(code), body};
    }

    static byte[] pad(String s, int n) {
        return Arrays.copyOf(s.getBytes(StandardCharsets.UTF_8), n);
    }

    public static void main(String[] a) throws Exception {
        String mal = a[0], ep = a[1], lang = a[2];
        if (a.length > 3) ua = a[3];
        System.out.println("UA: " + ua);

        String[] page = get(MEGA + "/stream/mal/" + mal + "/" + ep + "/" + lang, REF_ANIKOTO);
        Matcher m = Pattern.compile("data-id=\"(\\d+)\"").matcher(page[1]);
        System.out.println("1) embed page: HTTP " + page[0] + ", " + page[1].length() + "b, file id = " + (m.find() ? m.group(1) : "NONE"));
        if (!m.find(0)) { System.out.println(page[1].substring(0, Math.min(400, page[1].length()))); return; }
        String id = m.group(1);

        String[] src = get(MEGA + "/stream/getSourcesNew?id=" + id, REF_PLAYER);
        System.out.println("2) getSourcesNew: HTTP " + src[0] + ", " + src[1].length() + "b");
        Matcher e = Pattern.compile("\"enc\"\\s*:\\s*\"([^\"]+)\"").matcher(src[1]);
        if (!e.find()) { System.out.println("   no enc field: " + src[1].substring(0, Math.min(300, src[1].length()))); return; }

        byte[] raw = Base64.getUrlDecoder().decode(e.group(1));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(pad("i?LMTAx0Q6,:}50U", 32), "AES"), new IvParameterSpec(pad("W0;27ToaUpl_P%'c", 16)));
        String json = new String(c.doFinal(raw), StandardCharsets.UTF_8);
        System.out.println("3) decrypted: " + json);
        Matcher f = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!f.find()) return;
        String master = f.group(1).replace("\\/", "/");

        String[] pl = get(master, REF_PLAYER);
        System.out.println("4) master playlist: HTTP " + pl[0]);
        System.out.println(pl[1].length() > 600 ? pl[1].substring(0, 600) : pl[1]);
        String[] noRef = get(master, null);
        System.out.println("   (without Referer: HTTP " + noRef[0] + ")");
    }
}
