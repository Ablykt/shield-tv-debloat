package com.zen.home;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns Wikimedia Commons file names and metadata into short wallpaper captions:
 * "Gullfoss, Suðurland, Islandia, 2014-08-16, DD 119" -> "Gullfoss, Suðurland, Islandia".
 * Plain Java (no Android classes) so it can be tested on a computer.
 */
final class Captions {

    private static final int MAX_TITLE = 64, MAX_ARTIST = 40;
    private static final Pattern MONTH = Pattern.compile(
            "(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec|january|february|march|april|june|"
            + "july|august|september|october|november|december)\\.?");
    private static final Pattern CAMERA = Pattern.compile("(?i)(dd|img|dsc|dscf|pan|hdr|nr)\\.?");

    private Captions() { }

    /** objectName may be empty; fileTitle is "File:Some name.jpg". */
    static String title(String objectName, String fileTitle) {
        String s = plain(objectName);
        if (s.isEmpty()) {
            s = fileTitle.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]{3,4}$", "").replace('_', ' ');
        }
        int dashes = s.indexOf(" -- ");
        if (dashes > 0) s = s.substring(0, dashes);
        s = s.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
             .replaceAll("\\b\\d{4}-\\d{1,2}-\\d{1,2}-?|-\\d+\\b", " "); // "2019-07-20-Dingle", "Lighthouse-0673"

        // Drop every token holding a digit (dates, times, serial numbers) and camera codes.
        List<String> kept = new ArrayList<>();
        for (String t : s.trim().split("\\s+")) {
            String bare = t.replaceAll("[,.;:]+$", "");
            if (bare.matches(".*\\d.*") || CAMERA.matcher(bare).matches()) {
                // Keep a trailing comma so "Iceland, 2014-08-16, Vik" stays a list.
                if (t.endsWith(",") && !kept.isEmpty()) kept.set(kept.size() - 1, stripEnd(kept.get(kept.size() - 1)) + ",");
                continue;
            }
            kept.add(t);
        }
        while (!kept.isEmpty()) {
            String last = stripEnd(kept.get(kept.size() - 1));
            if (last.isEmpty() || MONTH.matcher(last).matches()) kept.remove(kept.size() - 1);
            else break;
        }
        s = String.join(" ", kept)
                .replaceAll("\\s*,(\\s*,)*\\s*", ", ")
                .replaceAll("^[\\s,.;:\\-–—]+|[\\s,.;:\\-–—]+$", "")
                .replaceAll("\\s{2,}", " ");
        if (s.isEmpty()) return "Featured nature photo";
        return shorten(s, MAX_TITLE);
    }

    /** "Artist · License · Wikimedia Commons", like the bundled captions. */
    static String credit(String artistHtml, String user, String license) {
        String a = plain(artistHtml).replaceAll("\\([^)]*\\)", "").replaceAll("https?://\\S+", "").trim();
        int by = a.toLowerCase().indexOf("taken by ");
        if (by >= 0) a = a.substring(by + 9).replaceFirst("[.,;].*$", "").trim();
        a = a.replaceAll("[\\s,.;:/]+$", "");
        if (a.isEmpty() || a.length() > MAX_ARTIST) a = user == null ? "" : user.trim();
        StringBuilder b = new StringBuilder(a);
        String lic = plain(license);
        if (!lic.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(lic);
        return b.append(b.length() > 0 ? " · " : "").append("Wikimedia Commons").toString();
    }

    /** Strips HTML tags and the common entities, collapses whitespace. */
    static String plain(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static String stripEnd(String t) { return t.replaceAll("[,.;:\\-–—]+$", ""); }

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        int comma = s.lastIndexOf(", ", max);
        if (comma > max / 2) return s.substring(0, comma);
        int space = s.lastIndexOf(' ', max - 1);
        return (space > max / 2 ? s.substring(0, space) : s.substring(0, max - 1)) + "…";
    }
}
