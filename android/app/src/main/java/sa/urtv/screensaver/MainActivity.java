package sa.urtv.screensaver;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

/** Full-screen preview of the screensaver; any remote key closes it. */
public class MainActivity extends Activity {
    /** started by IdleWatch over an app: then the TV may still turn itself off on its own timer */
    static final String IDLE = "idle";
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!getIntent().getBooleanExtra(IDLE, false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = ScreenView.create(this);
        setContentView(web);
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override protected void onResume() { super.onResume(); ScreenView.onScreen++; }
    @Override protected void onPause() { ScreenView.onScreen = Math.max(0, ScreenView.onScreen - 1); super.onPause(); }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
