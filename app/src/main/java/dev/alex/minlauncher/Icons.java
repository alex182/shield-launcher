package dev.alex.minlauncher;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Home Assistant's "mdi:" icons, drawn from the bundled Material Design Icons
 * font (assets/mdi, refreshed by tools/update-mdi.sh). Fully offline.
 */
final class Icons {

    static final String FONT = "mdi/materialdesignicons-webfont.ttf";
    static final String NAMES = "mdi/names.tsv";

    private static Typeface typeface;
    private static Map<String, Integer> codepoints;

    private Icons() { }

    static synchronized Typeface typeface(Context c) {
        if (typeface == null) typeface = Typeface.createFromAsset(c.getAssets(), FONT);
        return typeface;
    }

    /** Codepoint for "mdi:lightbulb" or "lightbulb", or -1 if unknown. */
    static int codepoint(Context c, String name) {
        if (name == null) return -1;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("mdi:")) n = n.substring(4);
        if (n.isEmpty()) return -1;
        Integer cp = names(c).get(n);
        return cp == null ? -1 : cp;
    }

    private static synchronized Map<String, Integer> names(Context c) {
        if (codepoints != null) return codepoints;
        Map<String, Integer> m = new HashMap<>(8192);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open(NAMES), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab > 0) m.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1), 16));
            }
        } catch (IOException | NumberFormatException ignored) { }
        codepoints = m;
        return m;
    }

    /**
     * "{icon}  {label}" with the icon in the MDI font. A span rather than a
     * separate view, so the row's focus colour applies to icon and label alike.
     * Unknown or blank icons give the bare label.
     */
    static CharSequence withLabel(Context c, String icon, String label) {
        int cp = codepoint(c, icon);
        if (cp < 0) return label;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(new String(Character.toChars(cp)));
        sb.setSpan(new FontSpan(typeface(c), 1.25f), 0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("   ").append(label);
        return sb;
    }

    /** TypefaceSpan(Typeface) is API 28+; minSdk is 21. */
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface face;
        private final float scale;
        FontSpan(Typeface face, float scale) { this.face = face; this.scale = scale; }
        @Override public void updateDrawState(TextPaint p) { apply(p); }
        @Override public void updateMeasureState(TextPaint p) { apply(p); }
        private void apply(Paint p) {
            p.setTypeface(face);
            p.setTextSize(p.getTextSize() * scale);
        }
    }
}
