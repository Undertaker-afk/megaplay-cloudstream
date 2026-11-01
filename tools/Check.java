import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/**
 * Live end-to-end checks of everything MegaPlayProvider depends on (Tenrai + MegaPlay).
 * Mirrors the provider's logic step by step.
 *
 *   java tools/Check.java
 *
 * Exit code 1 if any REQUIRED check fails. WARN lines are informational
 * (e.g. a dub that MegaPlay doesn't have, or a title that only has one quality).
 */
public class Check {
    static final String TENRAI = "https://api.tenrai.org/v1";
    static final String MEGA = "https://megaplay.buzz";
    static final String REF_ANIKOTO = "https://anikotoapi.site/";
    static final String REF_PLAYER = "https://megaplay.buzz/";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0 Safari/537.36";

    static int failed = 0, warned = 0, passed = 0;
    static final List<String> summary = new ArrayList<>();

    record Resp(int code, String body) {}

    static Resp get(String url, String referer, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (range != null) c.setRequestProperty("Range", range);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] data = in == null ? new byte[0] : in.readNBytes(range != null ? 4096 : 8_000_000);
        return new Resp(code, new String(data, StandardCharsets.ISO_8859_1));
    }

    static void ok(String m) { passed++; System.out.println("  PASS  " + m); }
    static void warn(String m) { warned++; System.out.println("  WARN  " + m); }
    static void fail(String m) { failed++; summary.add(m); System.out.println("  FAIL  " + m); }
    static void req(boolean cond, String okMsg, String failMsg) { if (cond) ok(okMsg); else fail(failMsg); }

    static byte[] pad(String s, int n) { return Arrays.copyOf(s.getBytes(StandardCharsets.UTF_8), n); }

    static String decrypt(String enc) throws Exception {
        String padded = enc + "=".repeat((4 - enc.length() % 4) % 4);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(pad("i?LMTAx0Q6,:}50U", 32), "AES"),
                new IvParameterSpec(pad("W0;27ToaUpl_P%'c", 16)));
        return new String(c.doFinal(Base64.getUrlDecoder().decode(padded)), StandardCharsets.UTF_8);
    }

    static String find(String re, String s) {
        Matcher m = Pattern.compile(re, Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // ---- Tenrai ----

    static void tenrai(int mal, String label) throws Exception {
        System.out.println("\n[Tenrai] " + label + " (MAL " + mal + ")");
        Thread.sleep(500);
        Resp full = get(TENRAI + "/anime/" + mal + "/full", REF_ANIKOTO, null);
        req(full.code == 200 && full.body.contains("\"mal_id\":" + mal), "details endpoint", "details endpoint HTTP " + full.code + " for " + label);
        req(find("\"title\":\"([^\"]+)\"", full.body) != null, "has title", "no title for " + label);
        req(full.body.contains("large_image_url"), "has poster", "no poster for " + label);

        Resp eps = get(TENRAI + "/anime/" + mal + "/episodes?page=1", REF_ANIKOTO, null);
        if (eps.code == 200 && eps.body.contains("\"data\":[{")) ok("episode list");
        else warn("episode list empty (provider falls back to 1..N) for " + label);

        for (String ep : new String[]{"characters", "recommendations", "videos/episodes"}) {
            Thread.sleep(400);
            Resp r = get(TENRAI + "/anime/" + mal + "/" + ep, REF_ANIKOTO, null);
            if (r.code == 200 && r.body.contains("\"data\":[{")) ok(ep);
            else warn(ep + " empty/HTTP " + r.code + " for " + label);
        }
    }

    static void search() throws Exception {
        System.out.println("\n[Tenrai] search");
        Thread.sleep(500);
        Resp all = get(TENRAI + "/anime?q=edgerunners&limit=25", REF_ANIKOTO, null);
        req(all.body.contains("\"mal_id\":42310") && all.body.contains("\"mal_id\":61990"),
                "unfiltered search finds Edgerunners 1 and 2", "unfiltered search is missing Edgerunners (42310 / 61990)");
        Resp sfw = get(TENRAI + "/anime?q=edgerunners&limit=25&sfw", REF_ANIKOTO, null);
        if (!sfw.body.contains("\"mal_id\":42310")) ok("sfw filter hides the R+ title (as the setting expects)");
        else warn("sfw filter no longer hides R+ titles");
    }

    // ---- MegaPlay ----

    static String fileId(int mal, int ep, String lang) throws Exception {
        for (int i = 0; i < 3; i++) {
            Resp page = get(MEGA + "/stream/mal/" + mal + "/" + ep + "/" + lang, REF_ANIKOTO, null);
            String id = find("data-id=\"(\\d+)\"", page.body);
            if (id != null) return id;
            Thread.sleep(400L * (i + 1));
        }
        return null;
    }

    /** Full chain for one (title, episode, language). Returns false if the language isn't available. */
    static void stream(int mal, int ep, String lang, String label, boolean required) throws Exception {
        String tag = label + " ep" + ep + " " + lang;
        System.out.println("\n[MegaPlay] " + tag);
        String id = fileId(mal, ep, lang);
        if (id == null) {
            if (required) fail(tag + ": no player file id on embed page");
            else warn(tag + ": not available on MegaPlay");
            return;
        }
        ok("embed page -> file id " + id);

        Resp src = get(MEGA + "/stream/getSourcesNew?id=" + id, REF_PLAYER, null);
        String enc = find("\"enc\"\\s*:\\s*\"([^\"]+)\"", src.body);
        if (enc == null) { fail(tag + ": getSourcesNew has no enc (HTTP " + src.code + ")"); return; }
        ok("getSourcesNew returns enc");

        String master;
        try {
            String json = decrypt(enc);
            master = find("\"file\"\\s*:\\s*\"([^\"]+)\"", json);
            if (master == null) { fail(tag + ": decrypted payload has no file: " + json); return; }
            master = master.replace("\\/", "/");
            ok("decrypt -> " + master);
        } catch (Exception e) {
            fail(tag + ": decrypt failed (key/IV changed?) " + e);
            return;
        }

        int subs = src.body.split("\"kind\":\"captions\"", -1).length - 1;
        if (subs > 0) ok(subs + " subtitle tracks"); else warn(tag + ": no subtitle tracks");
        if (src.body.contains("\"intro\"")) ok("intro/outro timestamps present"); else warn(tag + ": no intro/outro data");

        Resp pl = get(master, REF_PLAYER, null);
        if (pl.code != 200 || !pl.body.startsWith("#EXTM3U")) { fail(tag + ": master playlist HTTP " + pl.code); return; }
        ok("master playlist");

        Resp noRef = get(master, null, null);
        if (noRef.code == 403) ok("CDN is referer-gated (extension must send Referer)");
        else warn(tag + ": CDN served master without Referer (HTTP " + noRef.code + ")");

        // Real variants only (not #EXT-X-I-FRAME-STREAM-INF).
        List<String[]> variants = new ArrayList<>();
        String[] lines = pl.body.split("\\r?\\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                String res = find("RESOLUTION=(\\d+x\\d+)", lines[i]);
                variants.add(new String[]{res == null ? "unknown" : res, lines[i + 1].trim()});
            }
        }
        if (variants.isEmpty()) { fail(tag + ": master has no playable variants"); return; }
        StringBuilder q = new StringBuilder();
        for (String[] v : variants) q.append(v[0]).append(' ');
        if (variants.size() > 1) ok(variants.size() + " qualities: " + q.toString().trim());
        else warn(tag + ": only one quality offered by MegaPlay for this title: " + q.toString().trim());

        for (String[] v : variants) {
            URL varUrl = new URL(new URL(master), v[1]);
            Resp vp = get(varUrl.toString(), REF_PLAYER, null);
            if (vp.code != 200 || !vp.body.contains("#EXTINF")) { fail(tag + " " + v[0] + ": variant playlist HTTP " + vp.code); continue; }
            String seg = null;
            for (String l : vp.body.split("\\r?\\n")) if (!l.isBlank() && !l.startsWith("#")) { seg = l.trim(); break; }
            if (seg == null) { fail(tag + " " + v[0] + ": variant has no segments"); continue; }
            Resp s = get(new URL(varUrl, seg).toString(), REF_PLAYER, "bytes=0-1023");
            req((s.code == 200 || s.code == 206) && s.body.length() > 0,
                    v[0] + ": first segment downloads (" + s.body.length() + " bytes)",
                    tag + " " + v[0] + ": segment HTTP " + s.code);
        }
    }

    // ---- Provider-specific regressions ----

    static void regressions() {
        System.out.println("\n[Provider] regressions");
        // CloudStream prefixes episode data with mainUrl; the provider must still recover the MAL id.
        for (String data : new String[]{"https://tenrai.net/61990|1|sub", "61990|1|sub"}) {
            String[] p = data.split("\\|");
            String mal = p[0].substring(p[0].lastIndexOf('/') + 1);
            req(p.length == 3 && mal.equals("61990") && p[1].equals("1") && p[2].equals("sub"),
                    "episode data parses: " + data, "episode data mis-parsed: " + data);
        }
    }

    public static void main(String[] args) throws Exception {
        regressions();
        search();

        // {MAL id, episode, label}: a mix of airing/finished/movie/R+ titles.
        Object[][] cases = {
                {61990, 1, "Cyberpunk: Edgerunners 2"},
                {42310, 1, "Cyberpunk: Edgerunners"},
                {52991, 1, "Sousou no Frieren"},
                {52299, 1, "Solo Leveling"},
                {32281, 1, "Kimi no Na wa (movie)"},
        };
        for (Object[] c : cases) {
            int mal = (Integer) c[0], ep = (Integer) c[1];
            String label = (String) c[2];
            try { tenrai(mal, label); } catch (Exception e) { fail("Tenrai " + label + ": " + e); }
            // Sub is required for every title; dub is optional (not every show is dubbed).
            for (String lang : new String[]{"sub", "dub"}) {
                try { stream(mal, ep, lang, label, lang.equals("sub")); }
                catch (Exception e) { if (lang.equals("sub")) fail(label + " " + lang + ": " + e); else warn(label + " " + lang + ": " + e); }
            }
        }

        System.out.println("\n==== " + passed + " passed, " + warned + " warnings, " + failed + " failed ====");
        for (String s : summary) System.out.println("  FAILED: " + s);
        System.exit(failed == 0 ? 0 : 1);
    }
}
