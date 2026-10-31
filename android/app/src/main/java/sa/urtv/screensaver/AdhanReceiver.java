package sa.urtv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The adhan alarm (Adhan.schedule): play it if it is still its time, then arm the next one. Also the notice
 *  before it (Adhan.REMIND): shown only while the screen is on, and only near its time. */
public class AdhanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        int prayer = i.getIntExtra("prayer", -1);
        long at = i.getLongExtra("at", 0);
        if (Adhan.REMIND.equals(i.getAction())) {
            long due = at - p.reminderMinutes() * 60_000L;
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (prayer >= 0 && AdhanTimes.onTime(due, System.currentTimeMillis()) && at > System.currentTimeMillis()
                    && pm != null && pm.isInteractive()) Reminder.show(c, prayer, at);
        } else if (p.adhanOn() && prayer >= 0 && AdhanTimes.onTime(at, System.currentTimeMillis())) {
            String voice = prayer == 0 ? p.adhanFajr() : p.adhanVoice();
            if (!voice.isEmpty()) AdhanService.start(c, Adhan.resolve(c, voice, prayer == 0), false);
        }
        final PendingResult r = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Adhan.refresh(app, false);
            Adhan.schedule(app);
            r.finish();
        }).start();
    }
}
