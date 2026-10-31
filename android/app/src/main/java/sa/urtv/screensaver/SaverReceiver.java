package sa.urtv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The screensaver's settings from ADB, for agents (people use SettingsActivity). Only the shell can send
 * these: the manifest requires android.permission.DUMP. optimize/saver.sh wraps them.
 *
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.GET
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.CITIES
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.SET
 *       [--es city 'الرس'] [--es lat 25.86 --es lon 43.49] [--es teams '136013:Al-Hilal,150643:Al-Zulfi']
 *       [--es panel prayers] [--es home_panel none]
 *       [--es photos 30] [--es idle 10] [--es autoloc true|false]
 *       [--es mosque https://mawaqit.net/de/SLUG | SLUG | none] [--es mosque_name 'مسجد الرحمن']
 *       [--es adhan true|false] [--es adhan_voice makki-1] [--es adhan_fajr off|fajr-trabulsi] [--es adhan_volume 10]
 *       [--es night true|false]   (dim the screensaver from 21:00 to 07:00; off by default)
 *       [--es adhkar true|false]  (morning and evening adhkar beside the clock in their hours; on by default)
 *       [--es fdkey KEY | none]   (football-data.org: the next five games of the clubs it covers)
 *       [--es reminder 30]   (minutes before each adhan for the notice at the top right; 0 = off)
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.REMINDER_TEST   (shows it now)
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.ADHAN_TEST   (plays it now)
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.ADHAN_STOP
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.UNADMIN      (before uninstalling)
 *   am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.UPDATE
 *
 * photos = minutes between background photos (Prefs.PHOTO_MINUTES). idle = minutes before the TV starts the
 * screensaver (Prefs.IDLE_MINUTES): this is the system's screen_off_timeout, which a receiver (it runs as this
 * app, not as the shell) may write only when the app holds WRITE_SETTINGS; otherwise it says so and
 * optimize/saver.sh sets it from the shell instead. Nothing changes unless the extra is given.
 *
 * The answer is in the "data" of am's output. Unknown values are refused with a message, nothing half-saved.
 */
public class SaverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        String action = String.valueOf(i.getAction());
        switch (action) {
            case "sa.urtv.screensaver.GET":
                setResultData(describe(c, p));
                return;
            case "sa.urtv.screensaver.CITIES": {
                StringBuilder b = new StringBuilder();
                for (Cities.Region r : Cities.REGIONS) {
                    b.append(r.name).append(": ");
                    for (String[] city : r.cities) b.append(city[0]).append("، ");
                    b.setLength(b.length() - 2);
                    b.append('\n');
                }
                setResultData(b.toString().trim());
                return;
            }
            case "sa.urtv.screensaver.APP_UPDATE": {   // the apps from the latest release, now (no agent needed later)
                final PendingResult pending = goAsync();
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    UpdateJob.askHome(app);
                    pending.setResultData("app update: " + SelfUpdate.check(app, "urtv-screensaver.apk", true, false));
                    pending.finish();
                }).start();
                return;
            }
            case "sa.urtv.screensaver.UPDATE": {
                final PendingResult pending = goAsync();   // a download: off the main thread
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    pending.setResultData("update: " + Updater.check(app, true));
                    pending.finish();
                }).start();
                return;
            }
            case "sa.urtv.screensaver.ADHAN_TEST":
                AdhanService.start(c, Adhan.resolve(c, p.adhanVoice(), false), true);
                setResultData("adhan: playing " + p.adhanVoice() + " at volume " + p.adhanVolume());
                return;
            case "sa.urtv.screensaver.REMINDER_TEST":
                Reminder.show(c, 2, System.currentTimeMillis() + Math.max(10, p.reminderMinutes()) * 60_000L);
                setResultData("reminder: shown" + (Reminder.canDraw(c) ? " at the top right" : " as a notification (no overlay permission)"));
                return;
            case "sa.urtv.screensaver.ADHAN_STOP":
                AdhanService.stop();
                setResultData("adhan: stopped");
                return;
            case "sa.urtv.screensaver.UNADMIN": {   // an active device admin can't be uninstalled
                android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName admin = new android.content.ComponentName(c, AdminReceiver.class);
                if (dpm != null && dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin);
                setResultData("admin: removed");
                return;
            }
            case "sa.urtv.screensaver.SET":
                setResultData(set(c, p, i));
                return;
            default:
                setResultData("error: unknown action " + action);
        }
    }

    private static String set(Context c, Prefs p, Intent i) {
        // check everything first, then save: a typo never leaves half the settings changed
        String[] city = null;
        String name = i.getStringExtra("city"), lat = i.getStringExtra("lat"), lon = i.getStringExtra("lon");
        if (name != null) {
            if (lat != null && lon != null) {
                if (!lat.matches("-?\\d{1,2}(\\.\\d+)?") || !lon.matches("-?\\d{1,3}(\\.\\d+)?"))
                    return "error: lat/lon must be numbers like 25.86 / 43.49";
                city = new String[] {name, lat, lon};
            } else {
                for (Cities.Region r : Cities.REGIONS) for (String[] x : r.cities) if (x[0].equals(name)) city = x;
                if (city == null) return "error: city not in the built-in list; give --es lat and --es lon too (CITIES lists them)";
            }
        }
        List<String> teams = null;
        String t = i.getStringExtra("teams");
        if (t != null) {
            teams = new ArrayList<>();
            for (String part : t.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String[] kv = part.split(":", 2);
                if (!kv[0].matches("\\d+")) return "error: teams are id:EnglishName, e.g. 136013:Al-Hilal";
                String en = kv.length > 1 ? kv[1].trim() : "";
                // the bundled list knows the club by id (its Arabic name as the screen shows it); a club outside
                // the list gets the name made from its English one
                TeamSearch.Team known = Teams.byId(c, kv[0]);
                String ar = known != null && !known.ar.isEmpty() ? known.ar
                    : en.isEmpty() ? "#" + kv[0] : Arabic.team(c, en);
                teams.add(kv[0] + "|" + ar.replace("|", " ").replace(";", " "));
            }
            if (teams.size() > Prefs.MAX_TEAMS) return "error: at most " + Prefs.MAX_TEAMS + " teams";
        }
        String panel = i.getStringExtra("panel"), home = i.getStringExtra("home_panel");
        if (panel != null && !isPanel(panel)) return "error: panel is one of " + panels();
        if (home != null && !isPanel(home)) return "error: home_panel is one of " + panels();
        String photos = i.getStringExtra("photos");
        if (photos != null && !Arrays.asList(Prefs.PHOTO_MINUTES).contains(photos))
            return "error: photos is one of " + Prefs.join(Arrays.asList(Prefs.PHOTO_MINUTES), " ");
        String idle = i.getStringExtra("idle");
        if (idle != null) {
            if (!Arrays.asList(Prefs.IDLE_MINUTES).contains(idle))
                return "error: idle is one of " + Prefs.join(Arrays.asList(Prefs.IDLE_MINUTES), " ");
            if (!Prefs.canWriteSettings(c))
                return "error: idle not allowed: this app may not change system settings on this TV"
                    + " (the TV's own screensaver settings can, or the shell: settings put system screen_off_timeout)";
        }
        String autoloc = i.getStringExtra("autoloc");
        if (autoloc != null && !autoloc.matches("true|false")) return "error: autoloc is true or false";
        String mosque = i.getStringExtra("mosque"), mosqueName = i.getStringExtra("mosque_name");
        String slug = null;
        if (mosque != null && !mosque.matches("none|")) {
            slug = Prefs.mosqueSlug(mosque);
            if (slug == null) return "error: mosque is a mawaqit.net link, its last part (slug), or none";
        }
        String adhan = i.getStringExtra("adhan"), adhanVoice = i.getStringExtra("adhan_voice"),
            adhanFajr = i.getStringExtra("adhan_fajr"), adhanVolume = i.getStringExtra("adhan_volume");
        if (adhan != null && !adhan.matches("true|false")) return "error: adhan is true or false";
        if (adhanVoice != null && !Adhan.isChoice(adhanVoice)) return "error: adhan_voice is one of " + voices();
        if (adhanFajr != null && !adhanFajr.equals("off") && !Adhan.isChoice(adhanFajr))
            return "error: adhan_fajr is off or one of " + voices();
        if (adhanVolume != null && !adhanVolume.matches("\\d{1,3}") ) return "error: adhan_volume is a number, e.g. 10";
        String fdkey = i.getStringExtra("fdkey");
        if (fdkey != null && !fdkey.matches("none|[A-Za-z0-9]{8,64}")) return "error: fdkey is the key from football-data.org, or none";
        String night = i.getStringExtra("night");
        if (night != null && !night.matches("true|false")) return "error: night is true or false";
        String adhkar = i.getStringExtra("adhkar");
        if (adhkar != null && !adhkar.matches("true|false")) return "error: adhkar is true or false";
        String reminder = i.getStringExtra("reminder");
        if (reminder != null && !reminder.matches("\\d{1,3}")) return "error: reminder is minutes before the adhan, 0 = off";

        if (idle != null) {   // first: a refusal here leaves everything else unsaved
            try {
                Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                    Integer.parseInt(idle) * 60000);
            } catch (SecurityException e) {
                return "error: idle not allowed: the TV refused the change";
            }
        }
        if (city != null) p.setCity(city[0], city[1], city[2]);   // also turns the automatic location off
        if (autoloc != null) p.setAutoLocation(Boolean.parseBoolean(autoloc));
        if (mosque != null) {
            if (slug == null) p.setMosque("", "");
            else p.setMosque(slug, mosqueName != null && !mosqueName.trim().isEmpty() ? mosqueName.trim()
                : slug.equals(p.mosque()[0]) ? p.mosque()[1] : slug);
        }
        if (teams != null) p.setList("teams", teams);
        if (panel != null) p.setPanel(panel);
        if (home != null) p.setHomePanel(home);
        if (photos != null) p.setPhotoMinutes(photos);
        if (adhan != null) p.setAdhanOn(Boolean.parseBoolean(adhan));
        if (adhanVoice != null) p.setAdhanVoice(adhanVoice);
        if (adhanFajr != null) p.setAdhanFajr(adhanFajr.equals("off") ? "" : adhanFajr);
        if (adhanVolume != null) p.setAdhanVolume(Math.max(1, Integer.parseInt(adhanVolume)));
        if (reminder != null) p.setReminderMinutes(Integer.parseInt(reminder));
        if (night != null) p.setNightDim(Boolean.parseBoolean(night));
        if (adhkar != null) p.setAdhkar(Boolean.parseBoolean(adhkar));
        if (fdkey != null) p.setFdKey(fdkey.equals("none") ? "" : fdkey);
        Adhan.update(c, false);   // a new mosque, city or Fajr choice moves the next adhan
        return "saved\n" + describe(c, p);
    }

    private static String describe(Context c, Prefs p) {
        String[] city = p.city();
        String[] mosque = p.mosque();
        return "autoloc: " + p.autoLocation() + (p.autoLocation() ? " (city below is only the fallback)" : "")
            + "\ncity: " + city[0] + " (" + city[1] + ", " + city[2] + ")"
            + "\nmosque: " + (mosque[0].isEmpty() ? "none (calculated times)" : mosque[1] + " | " + mosque[0])
            + "\nteams: " + Prefs.join(p.teams(), ", ")
            + "\npanel: " + p.panel() + "\nhome_panel: " + p.homePanel()
            + "\nphotos: " + p.photoMinutes()
            + "\nidle: " + idle(Prefs.idleMs(c))
            + "\nover-apps: " + IdleWatch.enabled(c) + (IdleWatch.front.isEmpty() ? "" : " | on screen " + IdleWatch.front
                + (IdleWatch.isInput(IdleWatch.front) ? " (an input: never covered)" : ""))
            + "\nnight dimming: " + p.nightDim()
            + "\nadhkar: " + p.adhkar()
            + "\nmatches from: " + (p.fdKey().isEmpty() ? "TheSportsDB" : "football-data.org (key set) | last: "
                + (FdProxy.status(c).isEmpty() ? "no request yet" : FdProxy.status(c))
                + (FdProxy.log(c).isEmpty() ? "" : "\n  " + FdProxy.log(c).replace("\n", "\n  ")))
            + "\nautoupdate: " + p.autoUpdate()
            + "\nadhan: " + p.adhanOn() + " | voice " + p.adhanVoice() + " | fajr " + (p.adhanFajr().isEmpty() ? "off" : p.adhanFajr())
            + " | volume " + p.adhanVolume() + " | next: " + p.nextAdhan()
            + " | turns the TV back off: " + adminActive(c)
            + "\nreminder: " + (p.reminderMinutes() <= 0 ? "off" : p.reminderMinutes() + " min before")
            + " | overlay: " + Reminder.canDraw(c);
    }

    private static String voices() {
        List<String> out = new ArrayList<>();
        out.add(Adhan.RANDOM);
        for (String[] v : Adhan.VOICES) out.add(v[0]);
        return Prefs.join(out, " ");
    }

    private static boolean adminActive(Context c) {
        android.app.admin.DevicePolicyManager dpm =
            (android.app.admin.DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new android.content.ComponentName(c, AdminReceiver.class));
    }

    /** minutes the TV waits before the screensaver, "unknown" when it does not say */
    private static String idle(long ms) {
        if (ms <= 0) return "unknown";
        return ms % 60000 == 0 ? String.valueOf(ms / 60000) : (ms / 1000) + "s";
    }

    private static boolean isPanel(String v) {
        for (String[] x : Prefs.PANELS) if (x[0].equals(v)) return true;
        return false;
    }

    private static String panels() {
        return names(Prefs.PANELS);
    }

    private static String names(String[][] table) {
        List<String> out = new ArrayList<>();
        for (String[] x : table) out.add(x[0]);
        return Prefs.join(out, " ");
    }

}
