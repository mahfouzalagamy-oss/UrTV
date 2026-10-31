package sa.urtv.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Everything the user picks in settings, and the dashboard URL built from it. */
final class Prefs {
    /** The TV's own time zone (the page switches to the connection's one when the location is automatic). */
    static String tz() { return java.util.TimeZone.getDefault().getID(); }

    // The mosque whose Mawaqit timetable gives the adhan, iqama and Jumu'a times: {slug, display name}.
    // Until the user picks another one (or none), this one.
    static final String DEFAULT_MOSQUE_SLUG = "masjid-tawasul-kirchheim-unter-teck-73230-germany";
    static final String DEFAULT_MOSQUE_NAME = "مسجد الرحمن · كيرشهايم";

    /** "https://mawaqit.net/de/some-mosque?x" or "some-mosque" -> "some-mosque"; null when it is neither. */
    static String mosqueSlug(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("mawaqit\\.net/(?:[a-z]{2}(?:-[A-Za-z]{2})?/)?([A-Za-z0-9_-]+)").matcher(s);
        if (m.find()) s = m.group(1);
        return s.matches("[A-Za-z0-9_-]{3,160}") ? s : null;
    }

    /** The Arabic part of a Mawaqit name like "Masjid Alrahman  مسجد الرحمن - KIRCHHEIM", else the name. */
    static String mosqueLabel(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\u0600-\\u06FF][\\u0600-\\u06FF\\s]*").matcher(name);
        String best = "";
        while (m.find()) if (m.group().trim().length() > best.length()) best = m.group().trim();
        return best.length() >= 3 ? best : name.trim();
    }

    // TheSportsDB id, Arabic name; more teams come from the in-app search
    static final String[][] TEAM_PRESETS = {
        {"136013", "الهلال"}, {"136137", "المنتخب السعودي"},
    };

    // League name exactly as TheSportsDB spells it (checked from CI by .github/tools/team_ids.py), Arabic label.
    // Their clubs come from assets/shared/teams.json (Teams), which CI builds from these leagues' matches
    // (.github/tools/team_ids.py reads this list); a new league here needs its minimum club count there.
    static final String[][] LEAGUES = {
        {"Saudi-Arabian Pro League", "دوري روشن السعودي"},
        {"Saudi First Division League", "دوري يلو (الدرجة الأولى)"},
        {"English Premier League", "الدوري الإنجليزي"},
        {"Spanish La Liga", "الدوري الإسباني"},
        {"Italian Serie A", "الدوري الإيطالي"},
        {"German Bundesliga", "الدوري الألماني"},
        {"French Ligue 1", "الدوري الفرنسي"},
        {"Portuguese Primeira Liga", "الدوري البرتغالي"},
        {"Dutch Eredivisie", "الدوري الهولندي"},
        {"Turkish Super Lig", "الدوري التركي"},
        {"Egyptian Premier League", "الدوري المصري"},
        {"UAE Pro League", "الدوري الإماراتي"},
        {"Qatar Stars League", "الدوري القطري"},
        {"American Major League Soccer", "الدوري الأمريكي"},
    };

    // minutes between background photos (sent to the page as #photos=; 30 is what it always did)
    static final String[] PHOTO_MINUTES = {"5", "10", "15", "30", "60"};

    // minutes of idle before Android starts the screensaver (the TV's own screen_off_timeout; not stored here)
    static final String[] IDLE_MINUTES = {"1", "2", "5", "10", "15", "30"};

    /** May this app change system settings (API 23+, and only if the owner allowed it for the app)? */
    static boolean canWriteSettings(Context c) {
        return Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(c);
    }

    /** The TV's idle delay in ms; readable without any permission. -1 when the TV does not say. */
    static long idleMs(Context c) {
        return Settings.System.getLong(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1);
    }

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    /** {name, latitude, longitude}; Riyadh until the user picks */
    String[] city() {
        return new String[] {sp.getString("cityName", "الرياض"), sp.getString("cityLat", "24.6877"), sp.getString("cityLon", "46.7219")};
    }
    /** Picking a city by hand turns the automatic location off. */
    void setCity(String name, String lat, String lon) {
        sp.edit().putString("cityName", name).putString("cityLat", lat).putString("cityLon", lon)
            .putBoolean("autoLocation", false).apply();
    }

    /** City, weather and time zone from the internet connection; on until a city is picked by hand. */
    boolean autoLocation() { return sp.getBoolean("autoLocation", true); }
    void setAutoLocation(boolean v) { sp.edit().putBoolean("autoLocation", v).apply(); }

    /** {slug, name}; slug "" = no mosque (calculated times). */
    String[] mosque() {
        return new String[] {sp.getString("mosqueSlug", DEFAULT_MOSQUE_SLUG), sp.getString("mosqueName", DEFAULT_MOSQUE_NAME)};
    }
    void setMosque(String slug, String name) {
        sp.edit().putString("mosqueSlug", slug == null ? "" : slug).putString("mosqueName", name == null ? "" : name).apply();
    }

    // ---- the adhan (Adhan, AdhanService)

    /** on by default */
    boolean adhanOn() { return sp.getBoolean("adhanOn", true); }
    void setAdhanOn(boolean v) { sp.edit().putBoolean("adhanOn", v).apply(); }

    /** the voice for Dhuhr, Asr, Maghrib and Isha (a file in assets/adhan) */
    String adhanVoice() {
        String v = sp.getString("adhanVoice", Adhan.DEFAULT_VOICE);
        return Adhan.isChoice(v) ? v : Adhan.DEFAULT_VOICE;
    }
    void setAdhanVoice(String v) { sp.edit().putString("adhanVoice", v).apply(); }

    /** Fajr's voice; "" = no adhan at Fajr (the default: it is at night) */
    String adhanFajr() {
        String v = sp.getString("adhanFajr", "");
        return v.isEmpty() || Adhan.isChoice(v) ? v : "";
    }
    void setAdhanFajr(String v) { sp.edit().putString("adhanFajr", v).apply(); }

    /** the TV volume the adhan plays at (its own units; the volume before it comes back afterwards) */
    int adhanVolume() { return sp.getInt("adhanVolume", 10); }
    void setAdhanVolume(int v) { sp.edit().putInt("adhanVolume", v).apply(); }

    /** minutes before each adhan for the notice at the top right; 0 = off. 30 by default */
    int reminderMinutes() { return sp.getInt("reminderMinutes", 30); }
    void setReminderMinutes(int v) { sp.edit().putInt("reminderMinutes", v).apply(); }
    static final int[] REMINDER_MINUTES = {10, 15, 20, 30, 45, 60};

    /** «العصر · ٣:٤٩ م», written by Adhan.schedule for the settings screen */
    String nextAdhan() { return sp.getString("nextAdhan", ""); }
    void setNextAdhan(String v) { sp.edit().putString("nextAdhan", v).apply(); }

    static final int MAX_TEAMS = 10;

    /** Followed teams in priority order, "id|name". Migrates the old two team fields once. */
    List<String> teams() {
        if (!sp.contains("teams")) {
            List<String> v = new ArrayList<>();
            for (int slot = 1; slot <= 2; slot++) {
                String[] def = TEAM_PRESETS[slot - 1];
                String id = sp.getString("team" + slot + "Id", def[0]);
                if (!id.isEmpty()) v.add(id + "|" + sp.getString("team" + slot + "Name", def[1]));
            }
            return v;
        }
        return list("teams");
    }

    /** false when already followed or the list is full */
    boolean addTeam(String id, String name) {
        List<String> v = teams();
        for (String t : v) if (t.startsWith(id + "|")) return false;
        if (v.size() >= MAX_TEAMS) return false;
        v.add(id + "|" + name.replace("|", " ").replace(";", " "));
        setList("teams", v);
        return true;
    }

    // The card beside the clock: value sent to the page (#panel=), Arabic label
    static final String[][] PANELS = {
        {"auto", "تبديل تلقائي كل ٣٠ ثانية"}, {"verse", "آية أو حديث اليوم"}, {"prayers", "مواقيت الصلاة اليوم"}, {"forecast", "طقس الأيام القادمة"},
        {"matches", "مباريات فرقك القادمة"}, {"none", "لا شيء"},
    };

    /** Older builds had an on/off switch for the card; off becomes "none", otherwise the verse card. */
    /** «auto» (the four cards in turn) unless the owner picked one; older builds' off switch = none */
    String panel() { return known(sp.getString("panel", sp.getBoolean("showWeek", true) ? "auto" : "none"), "auto"); }

    /** a card that is no longer offered (the TV-shows card was removed) falls back to `instead` */
    private static String known(String v, String instead) {
        for (String[] p : PANELS) if (p[0].equals(v)) return v;
        return instead;
    }
    void setPanel(String v) { sp.edit().putString("panel", v).apply(); }

    static String panelLabel(String v) {
        for (String[] p : PANELS) if (p[0].equals(v)) return p[1];
        return PANELS[0][1];
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(key, "").split(";")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    void setList(String key, List<String> v) { sp.edit().putString(key, join(v, ";")).apply(); }

    String photoMinutes() { return sp.getString("photoMinutes", "30"); }
    void setPhotoMinutes(String v) { sp.edit().putString("photoMinutes", v).apply(); }

    /** a free football-data.org key ("" = none): the next five games of the clubs it covers. Only on this TV. */
    String fdKey() { return sp.getString("fdKey", ""); }
    void setFdKey(String v) { sp.edit().putString("fdKey", v == null ? "" : v.replaceAll("[^A-Za-z0-9]", "")).apply(); }

    /** darken the screensaver from 21:00 to 07:00; off unless the owner turns it on */
    boolean nightDim() { return sp.getBoolean("nightDim", false); }

    /** morning (Fajr to 09:00) and evening (21:00 to midnight) adhkar beside the clock, whatever the card; on by default */
    boolean adhkar() { return sp.getBoolean("adhkar", true); }
    void setAdhkar(boolean v) { sp.edit().putBoolean("adhkar", v).apply(); }
    void setNightDim(boolean v) { sp.edit().putBoolean("nightDim", v).apply(); }

    boolean autoUpdate() { return sp.getBoolean("autoUpdate", true); }
    void setAutoUpdate(boolean v) { sp.edit().putBoolean("autoUpdate", v).apply(); }

    SharedPreferences raw() { return sp; }

    /** The page reads its settings from the fragment; a ?query fails on file:// in WebView. */
    String dashboardUrl(Context c) {
        File remote = Updater.pageFile(c);
        // A downloaded page older than the installed app is stale: the APK carries a newer copy.
        long installed = 0;
        try { installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime; }
        catch (Exception ignored) { }
        String base = autoUpdate() && remote.isFile() && remote.lastModified() > installed
            ? Uri.fromFile(remote).toString()
            : "file:///android_asset/tv.html";
        return base + "#" + fragment();
    }

    /** Everything the page needs, as "city=..&lat=..&..."; also read by the launcher (ConfigProvider). */
    String fragment() {
        String[] city = city();
        return "city=" + Uri.encode(city[0]) + "&lat=" + city[1] + "&lon=" + city[2]
            + "&tz=" + Uri.encode(tz())
            + (autoLocation() ? "&loc=auto" : "")
            + (mosque()[0].isEmpty() ? "" : "&mosque=" + Uri.encode(mosque()[0]))
            + "&teams=" + join(teamIds(), ",")
            + "&panel=" + panel()
            + "&week=" + ("none".equals(panel()) ? "0" : "1")   // for a page older than #panel
            + "&photos=" + photoMinutes()
            + "&night=" + (nightDim() ? "1" : "0")
            + "&adhkar=" + (adhkar() ? "1" : "0")
            + (fdKey().isEmpty() ? "" : "&fdkey=" + fdKey() + "&fdnative=1");   // the app adds the key (FdProxy)
    }

    /** The card on the launcher's home screen: one of PANELS; the four in turn («auto») unless picked otherwise. */
    String homePanel() { return known(sp.getString("homePanel", "auto"), "auto"); }
    void setHomePanel(String v) { sp.edit().putString("homePanel", v).apply(); }

    List<String> teamIds() {
        List<String> ids = new ArrayList<>();
        for (String t : teams()) ids.add(t.split("\\|", 2)[0]);
        return ids;
    }

    static String join(List<String> v, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : v) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }
}
