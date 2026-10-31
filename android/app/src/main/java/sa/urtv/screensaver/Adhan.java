package sa.urtv.screensaver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The adhan at each prayer time, even while another app plays or the screen is off (AdhanService plays it).
 *
 * Times: the chosen mosque's Mawaqit timetable (the same one the screen shows), else Aladhan for the place. They
 * are kept in files/adhan-times.json, so the adhan keeps its time on days without internet. One exact alarm is
 * set for the next adhan; when it fires, AdhanReceiver plays it and sets the one after.
 */
final class Adhan {
    private Adhan() {}

    // file in assets/adhan (40 kb/s mono, loudness-normalised so one volume level sounds the same), Arabic name;
    // the Fajr ones carry «الصلاة خير من النوم» and are listed first for Fajr
    static final String[][] VOICES = {
        {"makki-1", "الحرم المكي ١"},
        {"makki-2", "الحرم المكي ٢"},
        {"madani", "الحرم المدني"},
        {"aqsa", "القدس"},
        {"ahmad-jalal-yahya", "أحمد جلال يحيى"},
        {"voice-7", "أذان ٧"},
        {"voice-8", "أذان ٨"},
        {"fajr-trabulsi", "أحمد الطرابلسي · أذان الفجر"},
    };
    static final String[] FAJR_FIRST = {"fajr-trabulsi"};

    static final String DEFAULT_VOICE = "makki-1";
    static final String DEFAULT_FAJR = "fajr-trabulsi";
    // volume steps offered (in the TV's own units, capped at its maximum); 10 unless the owner picks another
    static final int[] VOLUMES = {5, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100};

    // «عشوائي»: each adhan in another of the voices (never the same twice in a row); Fajr's list takes it too
    static final String RANDOM = "random";
    static final String RANDOM_LABEL = "عشوائي · صوت مختلف كل أذان";

    /** a voice, or RANDOM (what the settings store) */
    static boolean isChoice(String id) { return RANDOM.equals(id) || isVoice(id); }

    /** the file to play for a stored choice: RANDOM picks one now. Fajr may get a Fajr voice; the others never
     *  (it carries «الصلاة خير من النوم»). */
    static String resolve(Context c, String choice, boolean fajr) {
        if (!RANDOM.equals(choice)) return choice;
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (String[] v : VOICES) {
            boolean fajrOnly = java.util.Arrays.asList(FAJR_FIRST).contains(v[0]);
            if (fajr || !fajrOnly) pool.add(v[0]);
        }
        android.content.SharedPreferences sp = c.getSharedPreferences("adhan-random", Context.MODE_PRIVATE);
        String last = sp.getString("last", "");
        if (pool.size() > 1) pool.remove(last);
        String pick = pool.get(new java.util.Random().nextInt(pool.size()));
        sp.edit().putString("last", pick).apply();
        return pick;
    }

    static boolean isVoice(String id) {
        for (String[] v : VOICES) if (v[0].equals(id)) return true;
        return false;
    }

    static String label(String id) {
        if (RANDOM.equals(id)) return RANDOM_LABEL;
        for (String[] v : VOICES) if (v[0].equals(id)) return v[1];
        return id;
    }

    // ---------------------------------------------------------------- times

    private static File file(Context c) { return new File(c.getFilesDir(), "adhan-times.json"); }

    private static JSONObject read(Context c) {
        try {
            File f = file(c);
            if (!f.isFile()) return null;
            InputStream in = new java.io.FileInputStream(f);
            return new JSONObject(new String(readAll(in), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context c, JSONObject j) throws Exception {
        File tmp = new File(c.getFilesDir(), "adhan-times.tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try { o.write(j.toString().getBytes("UTF-8")); } finally { o.close(); }
        if (!tmp.renameTo(file(c))) throw new Exception("rename");
    }

    static AdhanTimes.Days days(final JSONObject j) {
        final JSONArray cal = j == null ? null : j.optJSONArray("calendar");
        return (y, m, d) -> {
            JSONObject month = cal == null ? null : cal.optJSONObject(m - 1);
            JSONArray day = month == null ? null : month.optJSONArray(String.valueOf(d));
            if (day == null || day.length() < 6) return null;
            String[] t = new String[6];
            for (int i = 0; i < 6; i++) t[i] = day.optString(i, "");
            return t;
        };
    }

    /** Today's {fajr, dhuhr, maghrib} in minutes after midnight, from the stored timetable; null if not known.
     *  The launcher greets by them (ConfigProvider): صباح الخير from Fajr, طاب يومك from Dhuhr, مساء الخير from Maghrib. */
    static int[] today(Context c) {
        java.util.Calendar n = java.util.Calendar.getInstance();
        String[] t = days(read(c)).day(n.get(java.util.Calendar.YEAR), n.get(java.util.Calendar.MONTH) + 1,
            n.get(java.util.Calendar.DAY_OF_MONTH));
        if (t == null) return null;
        int[] f = AdhanTimes.hm(t[0]), d = AdhanTimes.hm(t[2]), m = AdhanTimes.hm(t[4]);
        if (f == null || d == null || m == null) return null;
        return new int[] {f[0] * 60 + f[1], d[0] * 60 + d[1], m[0] * 60 + m[1]};
    }

    /** Blocking (network): fetch the timetable when the source changed or it is old. */
    static synchronized void refresh(Context c, boolean force) {
        Prefs p = new Prefs(c);
        JSONObject cur = read(c);
        long now = System.currentTimeMillis();
        String slug = p.mosque()[0];
        try {
            if (!slug.isEmpty()) {
                String src = "mawaqit:" + slug;
                if (!force && cur != null && src.equals(cur.optString("source")) && now - cur.optLong("at") < 20 * 3600_000L)
                    return;
                String conf = AdhanTimes.confData(get("https://mawaqit.net/en/" + slug));
                if (conf == null) return;
                JSONArray cal = new JSONObject(conf).optJSONArray("calendar");
                if (cal == null || cal.length() != 12) return;
                write(c, new JSONObject().put("source", src).put("at", now).put("calendar", cal));
                return;
            }
            // no mosque: Aladhan for the place, this month and the next
            double[] ll = place(p);
            if (ll == null) return;
            String tz = TimeZone.getDefault().getID();
            String src = String.format(Locale.US, "aladhan:%.3f,%.3f", ll[0], ll[1]);
            Calendar cal = Calendar.getInstance();
            int month = cal.get(Calendar.MONTH);
            boolean same = cur != null && src.equals(cur.optString("source"));
            JSONArray old = same ? cur.optJSONArray("calendar") : null;
            JSONObject thisMonth = old == null ? null : old.optJSONObject(month);
            if (!force && thisMonth != null && thisMonth.length() > 0 && now - cur.optLong("at") < 3 * 86400_000L) return;
            JSONArray months = new JSONArray();
            for (int i = 0; i < 12; i++) {
                JSONObject kept = old == null ? null : old.optJSONObject(i);
                months.put(kept != null ? kept : new JSONObject());
            }
            for (int k = 0; k < 2; k++) {
                Calendar m = (Calendar) cal.clone();
                m.set(Calendar.DAY_OF_MONTH, 1);
                m.add(Calendar.MONTH, k);
                String url = String.format(Locale.US,
                    "https://api.aladhan.com/v1/calendar/%d/%d?latitude=%.4f&longitude=%.4f&method=%d&timezonestring=%s",
                    m.get(Calendar.YEAR), m.get(Calendar.MONTH) + 1, ll[0], ll[1], "Asia/Riyadh".equals(tz) ? 4 : 3,
                    android.net.Uri.encode(tz));
                JSONArray data = new JSONObject(get(url)).getJSONArray("data");
                JSONObject days = new JSONObject();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject d = data.getJSONObject(i), t = d.getJSONObject("timings");
                    int day = Integer.parseInt(d.getJSONObject("date").getJSONObject("gregorian").getString("day"));
                    days.put(String.valueOf(day), new JSONArray().put(t.getString("Fajr")).put(t.getString("Sunrise"))
                        .put(t.getString("Dhuhr")).put(t.getString("Asr")).put(t.getString("Maghrib")).put(t.getString("Isha")));
                }
                months.put(m.get(Calendar.MONTH), days);
            }
            write(c, new JSONObject().put("source", src).put("at", now).put("calendar", months));
        } catch (Exception ignored) {
            // keep the file we have: its timetable still holds for the coming days
        }
    }

    /** {lat, lon}: the connection's place when the location is automatic, else the city picked in settings */
    private static double[] place(Prefs p) {
        try {
            if (p.autoLocation()) {
                JSONObject j = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
                return new double[] {Double.parseDouble(j.getString("latitude")), Double.parseDouble(j.getString("longitude"))};
            }
            String[] c = p.city();
            return new double[] {Double.parseDouble(c[1]), Double.parseDouble(c[2])};
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the alarm

    static final String ACTION = "sa.urtv.screensaver.ADHAN";

    static final String REMIND = "sa.urtv.screensaver.ADHAN_REMINDER";

    private static PendingIntent alarm(Context c, int prayer, long at) {
        Intent i = new Intent(c, AdhanReceiver.class).setAction(ACTION).putExtra("prayer", prayer).putExtra("at", at);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, 7, i, flags);
    }

    /** the notice before an adhan: its own alarm (request code 9), `at` = the adhan's time */
    private static PendingIntent reminder(Context c, int prayer, long at) {
        Intent i = new Intent(c, AdhanReceiver.class).setAction(REMIND).putExtra("prayer", prayer).putExtra("at", at);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, 9, i, flags);
    }

    private static void exact(Context c, AlarmManager am, long when, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) throw new SecurityException("no exact alarms");
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent show = PendingIntent.getActivity(c, 8, new Intent(c, SettingsActivity.class), flags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pi);   // exact, and wakes an idle TV
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    /** The notice before the next adhan (all five prayers; it only shows while the screen is on). */
    private static void scheduleReminder(Context c, Prefs p, AlarmManager am, JSONObject times) {
        int mins = p.reminderMinutes();
        if (mins <= 0) { am.cancel(reminder(c, -1, 0)); return; }
        long lead = mins * 60_000L;
        long[] n = AdhanTimes.next(days(times), System.currentTimeMillis() + lead + 30_000L, TimeZone.getDefault(), true);
        if (n == null) { am.cancel(reminder(c, -1, 0)); return; }
        exact(c, am, n[0] - lead, reminder(c, (int) n[1], n[0]));
    }

    /** Arms the one alarm for the next adhan from the stored timetable (no network). */
    static synchronized void schedule(Context c) {
        Prefs p = new Prefs(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        JSONObject times = read(c);
        scheduleReminder(c, p, am, times);
        if (!p.adhanOn()) {
            am.cancel(alarm(c, -1, 0));
            p.setNextAdhan("");
            return;
        }
        long[] n = AdhanTimes.next(days(times), System.currentTimeMillis() + 30_000L, TimeZone.getDefault(),
            !p.adhanFajr().isEmpty());
        if (n == null) {
            am.cancel(alarm(c, -1, 0));
            p.setNextAdhan("لا توجد مواقيت بعد (يحتاج اتصالاً بالإنترنت مرة)");
            return;
        }
        exact(c, am, n[0], alarm(c, (int) n[1], n[0]));
        p.setNextAdhan(AdhanTimes.NAMES[(int) n[1]] + " · " + AdhanTimes.clock(n[0], TimeZone.getDefault()));
    }

    /** Refresh (if needed) and re-arm, off the main thread. */
    static void update(Context c, final boolean force) {
        final Context app = c.getApplicationContext();
        new Thread(() -> { refresh(app, force); schedule(app); }).start();
    }

    // ---------------------------------------------------------------- net

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            return new String(readAll(con.getInputStream()), "UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }
}
