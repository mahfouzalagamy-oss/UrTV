package sa.urtv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Collections;

/**
 * The optional card on the home screen: the screensaver's page in card-only mode (#widget=1), showing
 * the panel picked in the screensaver's settings («بطاقة على الشاشة الرئيسية»): the four cards in turn unless
 * picked otherwise, in a glass frame; set to «لا شيء», no WebView is created. Settings come from the screensaver app's ConfigProvider (same signing key).
 */
final class HomeCard {
    private static final Uri CONFIG = Uri.parse("content://sa.urtv.screensaver.config");
    private static final String FONTS = "https://raw.githubusercontent.com/abdosamo1/UrTV/master/assets/fonts/";

    private final Activity activity;
    private final ViewGroup parent;
    private final FrameLayout.LayoutParams params;
    private WebView web;
    private FrameLayout frame;   // the glass the card sits in (Glass: the frosted wallpaper, a tint, gloss and rim)
    private String url;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int lastHeight = -1;
    private android.animation.ValueAnimator resize;

    /** The frame takes the card's own height (the four cards differ, and they take turns): asked every 1.5 s. */
    private final Runnable fit = new Runnable() {
        @Override public void run() {
            if (web == null || frame == null) return;
            float d = activity.getResources().getDisplayMetrics().density;
            // the room left, less the frame's own padding (22 dp), in the page's CSS px (= dp)
            int roomPx = room == null ? 0 : room.px();   // 0 before the screen is laid out: no limit yet
            int maxCss = roomPx <= 0 ? 0 : Math.max(80, Math.round(roomPx / d) - 22);
            web.evaluateJavascript("typeof fitCard==='function'?fitCard(" + maxCss + "):0", v -> {
                    try {
                        int css = Integer.parseInt(v.replace("\"", "").trim());
                        if (css <= 0 || frame == null) return;
                        int px = Math.round((css + 22) * d);   // + the frame's padding
                        if (px != lastHeight) {   // the glass grows or shrinks smoothly to the new card
                            int from = lastHeight < 0 ? px : frame.getLayoutParams().height;
                            lastHeight = px;
                            if (resize != null) resize.cancel();
                            resize = android.animation.ValueAnimator.ofInt(from, px);
                            resize.setDuration(from == px ? 0 : 420);
                            resize.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
                            resize.addUpdateListener(a -> {
                                if (frame == null) return;
                                ViewGroup.LayoutParams lp = frame.getLayoutParams();
                                lp.height = (int) a.getAnimatedValue();
                                frame.setLayoutParams(lp);
                            });
                            resize.start();
                        }
                    } catch (Exception ignored) { }
                });
            handler.postDelayed(this, 1500);
        }
    };

    /** the most height the card may take, in px (the space down to the app name over the favourites) */
    interface Room { int px(); }
    private Room room;

    HomeCard(Activity activity, ViewGroup parent, FrameLayout.LayoutParams params) {
        this.activity = activity;
        this.parent = parent;
        this.params = params;
    }

    void setRoom(Room r) { room = r; }

    /** onResume: follow the current settings; a changed choice gets a fresh page. */
    void refresh() {
        String want = readUrl();
        if (want == null) { destroy(); return; }
        if (!want.equals(url)) { destroy(); create(want); }
        web.onResume();
        web.resumeTimers();
        handler.removeCallbacks(fit);
        handler.post(fit);
    }

    /** onPause: no timers or network while an app is in front. pauseTimers is for every WebView in this app, so a
     *  website app (WebAppActivity) resumes them as it opens. */
    void pause() {
        handler.removeCallbacks(fit);
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    void destroy() {
        handler.removeCallbacks(fit);
        lastHeight = -1;
        if (resize != null) { resize.cancel(); resize = null; }
        if (web == null) return;
        parent.removeView(frame);
        frame = null;
        web.destroy();
        web = null;
        url = null;
    }

    private String readUrl() {
        try (Cursor c = activity.getContentResolver().query(CONFIG, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            String frag = c.getString(0), panel = c.getString(1);
            if (frag == null || panel == null) return null;
            // no card picked: still the morning and evening adhkar in their hours, if on (the page shows nothing else)
            if ("none".equals(panel) && !frag.matches("(.*&)?adhkar=1(&.*)?")) return null;
            frag = frag.replaceAll("(^|&)(panel|week)=[^&]*", "");   // the screensaver's own card choice
            return "file:///android_asset/web/tv.html#" + frag + "&panel=" + panel + "&widget=1";
        } catch (RuntimeException e) {
            return null;   // screensaver app missing or too old
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private String fdKey = "";

    private void create(String u) {
        java.util.regex.Matcher km = java.util.regex.Pattern.compile("[#&]fdkey=([A-Za-z0-9]+)").matcher(u);
        fdKey = km.find() ? km.group(1) : "";
        web = new WebView(activity);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            // the page asks GitHub for its fonts; answer from the APK
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String s = r.getUrl().toString();
                WebResourceResponse fd = FdProxy.serve(activity, s, fdKey);   // football-data.org, with the key
                if (fd != null) return fd;
                if (s.startsWith(FONTS)) {
                    String name = s.substring(FONTS.length());
                    if (name.matches("[A-Za-z0-9_-]+\\.woff2")) {
                        try {
                            return new WebResourceResponse("font/woff2", null, 200, "OK",
                                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                                activity.getAssets().open("fonts/" + name));
                        } catch (java.io.IOException ignored) { }
                    }
                }
                return super.shouldInterceptRequest(v, r);
            }
        });
        // the card's football-data games, for the screensaver app (FdProvider "shared"): only this screen asks the site
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void share(String json) { FdProvider.save(activity, json); }
        }, "UrTVShare");
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // same as the screensaver: the page calls its data APIs directly from file://
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        float d = activity.getResources().getDisplayMetrics().density;
        frame = new FrameLayout(activity);
        frame.setBackground(new Glass(frame, 24 * d, 1.2f * d, 0x2EFFFFFF, true));
        frame.setClipToOutline(true);
        frame.setElevation(8 * d);
        int pad = Math.round(14 * d);
        frame.setPadding(pad, Math.round(10 * d), pad, Math.round(10 * d));
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(frame, new FrameLayout.LayoutParams(params));   // its own copy: the height changes
        url = u;
        web.loadUrl(u);
    }
}
