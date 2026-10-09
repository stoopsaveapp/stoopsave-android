package com.stoopsave.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.view.View;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.webkit.WebViewClient;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import com.onesignal.OneSignal;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

/**
 * StoopSave v1.00 — WebView wrapper around https://stoopsave.com.
 *
 * Native bridges exposed to the page as window.StoopSaveApp:
 *   getFcmToken()  -> the FCM registration token ("" until Firebase returns
 *     one; the page skips /api/devices/register while empty).
 *   getVersion()   -> "1.00"
 *   ensureCameraPermission() -> asks for CAMERA at runtime before a scan.
 *   cameraState()  -> "granted" | "denied" | "blocked"
 *   shareOffer(title, text, url) -> system ACTION_SEND chooser (the WebView
 *     has no navigator.share, so the page calls this first).
 *
 * FCM_STATUS (2026-10-06): Gradle build. FirebaseInitProvider (merged from
 * the firebase-messaging AAR) auto-initializes FirebaseApp from the
 * google-services.json values; the token is fetched on launch and on refresh
 * via StoopSaveMessagingService, exposed through getFcmToken(), and the page
 * registers it with POST /api/devices/register.
 */
public class MainActivity extends Activity {

    /**
     * Radar splash view (splash-4): concentric rings, expanding pulse,
     * three blinking deal pins, and the center logo tile.
     */
    static class RadarSplashView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pulsePhase = 0f; // 0..1
        private long startTime = 0L;
        private android.graphics.Bitmap logoBitmap;
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                if (startTime == 0L) startTime = now;
                float t = ((now - startTime) % 2000L) / 2000f;
                pulsePhase = t;
                invalidate();
                handler.postDelayed(this, 33); // ~30fps
            }
        };
        // Pin positions (fraction of view size) + colors + blink phase offsets.
        private final float[][] pins = {
            {0.60f, 0.30f, 0.4f}, // red, delay .4s
            {0.32f, 0.62f, 1.1f}, // green, delay 1.1s
            {0.72f, 0.48f, 1.6f}, // yellow, delay 1.6s
        };
        private final int[] pinColors = {0xFFD93A1E, 0xFF3DDC84, 0xFFFFB800};

        RadarSplashView(android.content.Context ctx) {
            super(ctx);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            ringPaint.setColor(0xFF1E4A73);
            pulsePaint.setStyle(Paint.Style.STROKE);
            pulsePaint.setStrokeWidth(dp(2));
            pulsePaint.setColor(0xFF3DDC84);
            pinPaint.setStyle(Paint.Style.FILL);
            centerPaint.setStyle(Paint.Style.FILL);
            centerPaint.setColor(0xFFD93A1E);
            try {
                logoBitmap = android.graphics.BitmapFactory.decodeResource(
                    ctx.getResources(), R.drawable.splash_logo);
            } catch (Exception ignored) { }
        }
        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
        void startAnimations() {
            handler.post(ticker);
        }
        void stopAnimations() {
            handler.removeCallbacks(ticker);
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimations();
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float maxR = Math.min(w, h) / 2f;
            // Three static rings.
            for (int i = 0; i < 3; i++) {
                float r = maxR - dp(2) - i * (maxR / 3.4f);
                if (r > 0) c.drawCircle(cx, cy, r, ringPaint);
            }
            // Expanding pulse: scale .3 -> 1.1, alpha 1 -> 0.
            float pr = maxR * (0.3f + 0.8f * pulsePhase);
            pulsePaint.setAlpha((int) (255 * (1f - pulsePhase)));
            c.drawCircle(cx, cy, pr, pulsePaint);
            pulsePaint.setAlpha(255);
            // Blinking pins.
            long now = android.os.SystemClock.uptimeMillis();
            float pinR = dp(7);
            for (int i = 0; i < pins.length; i++) {
                float phase = ((now / 1000f) + pins[i][2]) % 2f / 2f; // 0..1 over 2s
                float s = (float) (1.0 + 0.5 * Math.sin(phase * Math.PI * 2));
                float a = (float) (0.75 + 0.25 * Math.cos(phase * Math.PI * 2));
                pinPaint.setColor(pinColors[i]);
                pinPaint.setAlpha((int) (255 * a));
                c.drawCircle(cx + (pins[i][0] - 0.5f) * w,
                             cy + (pins[i][1] - 0.5f) * h,
                             pinR * s, pinPaint);
            }
            pinPaint.setAlpha(255);
            // Center logo tile (red rounded square with logo bitmap).
            float tile = dp(56);
            float left = cx - tile / 2f, top = cy - tile / 2f;
            float rr = dp(16);
            c.drawRoundRect(left, top, left + tile, top + tile, rr, rr, centerPaint);
            if (logoBitmap != null) {
                float pad = dp(8);
                c.drawBitmap(logoBitmap, null,
                    new android.graphics.RectF(left + pad, top + pad,
                        left + tile - pad, top + tile - pad), null);
            }
        }
    }

    private static final String HOME_URL = "https://stoopsave.com/app";
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int CAMERA_REQUEST_CODE = 2002;
    private static final int LOCATION_REQUEST_CODE = 2003;
    private static final int SCAN_REQUEST_CODE = 2005;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;
    private boolean cameraRequestedBefore;
    private boolean locationRequestedBefore;
    // Set when the stoopsave://auth deep link arrives with a fresh OAuth
    // session: the WebView loads HOME_URL, then onPageFinished injects the
    // session into localStorage (origin-scoped, so it must happen on a
    // stoopsave.com page) and reloads so the app boots signed in.
    private String pendingOAuthSession;

    // FCM registration token. Empty until FirebaseMessaging returns one;
    // the page registers it via /api/devices/register once non-empty.
    // Volatile + static hook so StoopSaveMessagingService.onNewToken can
    // deliver a refresh to the live activity (or stash it for next launch).
    private String fcmToken = "";
    private View splashView;
    private RadarSplashView radarSplashView;
    // Splash logo pulse animator, cancelled when the first page finishes.
    private AnimatorSet splashPulse;
    private boolean splashHiding;
    private Vibrator vibrator;
    private String fcmDebug = "init:not-run";
    private static String pendingFcmToken = null;
    private static MainActivity liveInstance = null;

    /** Called by StoopSaveMessagingService when FCM rotates the token. */
    public static void onFcmTokenRefresh(String token) {
        pendingFcmToken = token;
        MainActivity inst = liveInstance;
        if (inst != null) {
            final String t = token;
            inst.runOnUiThread(new Runnable() {
                @Override public void run() { inst.setFcmToken(t); }
            });
        }
    }

    private void setFcmToken(String token) {
        fcmToken = token != null ? token : "";
        // Push the token into the page the moment it arrives — the page's
        // boot-time poll can miss it if Firebase is still fetching.
        if (webView != null && !fcmToken.isEmpty()) {
            final String t = fcmToken;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        webView.evaluateJavascript(
                            "(function(){try{window.__stoopsaveFcmToken="
                            + org.json.JSONObject.quote(t)
                            + ";window.dispatchEvent(new Event('stoopsave:fcm-token'));}catch(e){}})();",
                            null);
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // OneSignal push (primary): initialize before anything else so the
        // player ID is available as early as possible.
        try {
            OneSignal.initWithContext(this, "3f1470be-8762-407d-a39a-5bd313e6cd34");
            // OneSignal 5.x: explicitly request permission to trigger player
            // registration. initWithContext alone doesn't register without this.
            OneSignal.Notifications.requestPermission(true);
        } catch (Throwable t) {
            android.util.Log.w("StoopSave", "OneSignal init failed", t);
        }

        // Native feel: splash screen wrapper
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        FrameLayout root = new FrameLayout(this);

        // Splash: radar pulse (splash-4) — navy screen with animated radar rings,
        // expanding pulse, blinking deal pins, center logo, wordmark + tagline.
        // Shown while the WebView loads its first page.
        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.parseColor("#0A2540"));
        FrameLayout.LayoutParams splashParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
        splash.setLayoutParams(splashParams);

        LinearLayout splashInner = new LinearLayout(this);
        splashInner.setOrientation(LinearLayout.VERTICAL);
        splashInner.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        splashInner.setLayoutParams(innerParams);

        final float density = getResources().getDisplayMetrics().density;
        // Radar custom view (220dp square): rings + pulse + pins + center logo.
        RadarSplashView radarView = new RadarSplashView(this);
        int radarPx = (int) (220 * density);
        LinearLayout.LayoutParams radarParams =
            new LinearLayout.LayoutParams(radarPx, radarPx);
        radarView.setLayoutParams(radarParams);

        TextView wordmark = new TextView(this);
        wordmark.setText("StoopSave");
        wordmark.setTextSize(30);
        wordmark.setTypeface(wordmark.getTypeface(), android.graphics.Typeface.BOLD);
        wordmark.setTextColor(Color.parseColor("#FFF8EE"));
        wordmark.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        wordParams.topMargin = (int) (26 * density);
        wordmark.setLayoutParams(wordParams);

        TextView tagline = new TextView(this);
        tagline.setText("Deals near you, right now");
        tagline.setTextSize(13);
        tagline.setTextColor(Color.parseColor("#9FB3C8"));
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        tagParams.topMargin = (int) (8 * density);
        tagline.setLayoutParams(tagParams);

        splashInner.addView(radarView);
        splashInner.addView(wordmark);
        splashInner.addView(tagline);
        splash.addView(splashInner);
        splashView = splash;
        radarSplashView = radarView;
        radarView.startAnimations();

        // Entrance: radar + wordmark fade up.
        radarView.setAlpha(0f);
        wordmark.setAlpha(0f);
        tagline.setAlpha(0f);
        AnimatorSet entrance = new AnimatorSet();
        ObjectAnimator radarAlpha = ObjectAnimator.ofFloat(radarView, View.ALPHA, 0f, 1f);
        ObjectAnimator wordAlpha = ObjectAnimator.ofFloat(wordmark, View.ALPHA, 0f, 1f);
        ObjectAnimator tagAlpha = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f);
        radarAlpha.setDuration(450);
        wordAlpha.setDuration(400);
        wordAlpha.setStartDelay(180);
        tagAlpha.setDuration(400);
        tagAlpha.setStartDelay(270);
        entrance.playTogether(radarAlpha, wordAlpha, tagAlpha);
        entrance.setInterpolator(new AccelerateDecelerateInterpolator());
        entrance.start();
        // splashPulse unused for radar (its internal animators loop instead).

        webView = new WebView(this);

        root.addView(webView);
        root.addView(splashView);
        setContentView(root);

        // Cookies must be accepted for sign-in sessions (including OAuth).
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Native feel: aggressive caching so the app doesn't white-screen
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Google OAuth blocks WebViews: strip the "wv" marker from the user agent
        // so Google treats it as a regular browser.
        String ua = settings.getUserAgentString();
        if (ua != null) {
            settings.setUserAgentString(ua.replace("; wv", ""));
        }
        // Geolocation for "Use my location": the page settles the Android
        // permission via the bridge first, then this grants the origin.
        settings.setGeolocationEnabled(true);
        // File:// access is unnecessary (camera capture goes through
        // MediaStore content URIs; everything loads from https) and is a
        // standing XSS-escalation vector. Keep it off.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep the app on stoopsave.com over HTTPS; hand anything else
                // to the system. Plain http is not allowed (HSTS aside, the
                // app must never downgrade itself).
                if (url != null && url.startsWith("https://stoopsave.com")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Native feel: fade the splash out, stop pull-to-refresh spinner
                if (splashView != null && splashView.getVisibility() == View.VISIBLE
                        && !splashHiding) {
                    splashHiding = true;
                    if (splashPulse != null) {
                        splashPulse.cancel();
                        splashPulse = null;
                    }
                    if (radarSplashView != null) {
                        radarSplashView.stopAnimations();
                        radarSplashView = null;
                    }
                    splashView.animate().alpha(0f).setDuration(250)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                if (splashView != null) {
                                    splashView.setVisibility(View.GONE);
                                }
                            }
                        }).start();
                }
                // OAuth hand-off: drop the fresh session into localStorage,
                // then reload so the client boots signed in. The flag is
                // cleared before the async inject so the reload can't loop.
                if (pendingOAuthSession != null && url != null
                        && url.startsWith("https://stoopsave.com")) {
                    String raw = pendingOAuthSession;
                    pendingOAuthSession = null;
                    String esc = raw.replace("\\", "\\\\").replace("'", "\\'");
                    view.evaluateJavascript(
                        "(function(){try{localStorage.setItem("
                        + "'stoopsave.auth.session','" + esc + "');}catch(e){} "
                        + "location.reload();})();",
                        null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // Photo upload bridge for the scan flow: offer camera capture
                // plus the gallery/file picker. CAMERA is requested first so a
                // capture intent never silently fails.
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && checkSelfPermission(Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {
                    cameraRequestedBefore = true;
                    requestPermissions(new String[]{Manifest.permission.CAMERA},
                            CAMERA_REQUEST_CODE);
                    // Continue anyway: the picker still works without camera.
                }

                Intent takePicture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                try {
                    // MediaStore content URI — no FileProvider needed, works on
                    // every API level we target (WRITE_EXTERNAL_STORAGE covers
                    // API 26-28; 29+ is scoped-storage clean).
                    ContentValues values = new ContentValues();
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                            Locale.US).format(new Date());
                    values.put(MediaStore.Images.Media.DISPLAY_NAME,
                            "stoopsave_" + stamp + ".jpg");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cameraPhotoUri = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    takePicture.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                } catch (Exception e) {
                    takePicture = null;
                    cameraPhotoUri = null;
                }

                Intent pickFile = new Intent(Intent.ACTION_GET_CONTENT);
                pickFile.addCategory(Intent.CATEGORY_OPENABLE);
                pickFile.setType("image/*");

                Intent chooser = new Intent(Intent.ACTION_CHOOSER);
                chooser.putExtra(Intent.EXTRA_INTENT, pickFile);
                if (takePicture != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                            new Intent[]{takePicture});
                }
                chooser.putExtra(Intent.EXTRA_TITLE, "Add offer photo");
                try {
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                // The page settles the Android permission via
                // ensureLocationPermission() before calling getCurrentPosition,
                // so this only ever grants when it's already allowed — and
                // denies fast otherwise instead of hanging the page.
                boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                // WebView getUserMedia (camera for the web scanner fallback):
                // grant only when the Android CAMERA permission is already
                // allowed; deny fast otherwise so the page shows its
                // "allow camera" notice instead of hanging.
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            boolean cameraGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                                    || checkSelfPermission(Manifest.permission.CAMERA)
                                            == PackageManager.PERMISSION_GRANTED;
                            boolean wantsVideo = false;
                            for (String r : request.getResources()) {
                                if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                    wantsVideo = true;
                                    break;
                                }
                            }
                            if (wantsVideo && cameraGranted) {
                                request.grant(request.getResources());
                            } else {
                                request.deny();
                            }
                        } catch (Exception e) {
                            try { request.deny(); } catch (Exception ignored) {}
                        }
                    }
                });
            }
        });

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public String getFcmToken() {
                // "" until Firebase returns a token — the page skips device
                // registration while empty and retries on later visits.
                return MainActivity.this.fcmToken;
            }

            @android.webkit.JavascriptInterface
            public String getFcmDebug() {
                // Short diagnostic: init:not-run | init:ok | init:returned-null
                // | init:threw:<Class>:<msg> | token:failed:<Class>:<msg> | token:ok
                return MainActivity.this.fcmDebug;
            }

            @android.webkit.JavascriptInterface
            public void performHaptic() {
                // Native feel: subtle tap feedback, callable from web via
                // window.StoopSaveApp.performHaptic()
                try {
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(15);
                        }
                    }
                } catch (Exception ignored) {}
            }

            @android.webkit.JavascriptInterface
            public String getOneSignalPlayerId() {
                // OneSignal subscription ID (the "player ID") — "" until the
                // SDK registers the device. Primary push identifier going forward.
                try {
                    String id = OneSignal.getUser().getPushSubscription().getId();
                    return id != null ? id : "";
                } catch (Exception e) {
                    return "";
                }
            }

            @android.webkit.JavascriptInterface
            public String getPendingOAuthSession() {
                // Returns the OAuth session from the deep link (if any) and
                // clears it. The frontend calls this on boot as a backup for
                // the onPageFinished injection, which can miss due to timing.
                try {
                    android.content.SharedPreferences prefs =
                        MainActivity.this.getSharedPreferences("stoopsave", MODE_PRIVATE);
                    String session = prefs.getString("pending_oauth_session", "");
                    if (session != null && !session.isEmpty()) {
                        prefs.edit().remove("pending_oauth_session").apply();
                        return session;
                    }
                } catch (Exception ignored) { }
                return "";
            }

            @android.webkit.JavascriptInterface
            public String getVersion() {
                return "1.00";
            }

            @android.webkit.JavascriptInterface
            public void ensureCameraPermission() {
                MainActivity.this.cameraRequestedBefore = true;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                && MainActivity.this.checkSelfPermission(
                                        Manifest.permission.CAMERA)
                                        != PackageManager.PERMISSION_GRANTED) {
                            MainActivity.this.requestPermissions(
                                    new String[]{Manifest.permission.CAMERA},
                                    CAMERA_REQUEST_CODE);
                        }
                    }
                });
            }

            // "granted" | "denied" (can still ask) | "blocked" (permanently denied)
            @android.webkit.JavascriptInterface
            public String cameraState() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "granted";
                if (MainActivity.this.checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) return "granted";
                if (MainActivity.this.shouldShowRequestPermissionRationale(
                        Manifest.permission.CAMERA)) return "denied";
                return MainActivity.this.cameraRequestedBefore ? "blocked" : "denied";
            }

            // Location permission for "Use my location". Same settled-first
            // pattern as the camera: the page calls this before
            // getCurrentPosition, then polls locationState(); the WebView
            // geolocation prompt grants against the settled permission.
            @android.webkit.JavascriptInterface
            public void ensureLocationPermission() {
                MainActivity.this.locationRequestedBefore = true;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                && MainActivity.this.checkSelfPermission(
                                        Manifest.permission.ACCESS_FINE_LOCATION)
                                        != PackageManager.PERMISSION_GRANTED
                                && MainActivity.this.checkSelfPermission(
                                        Manifest.permission.ACCESS_COARSE_LOCATION)
                                        != PackageManager.PERMISSION_GRANTED) {
                            MainActivity.this.requestPermissions(
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION},
                                    LOCATION_REQUEST_CODE);
                        }
                    }
                });
            }

            // "granted" | "denied" (can still ask) | "blocked" (permanently denied)
            @android.webkit.JavascriptInterface
            public String locationState() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "granted";
                if (MainActivity.this.checkSelfPermission(
                                Manifest.permission.ACCESS_FINE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED
                        || MainActivity.this.checkSelfPermission(
                                Manifest.permission.ACCESS_COARSE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED) return "granted";
                if (MainActivity.this.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_FINE_LOCATION)) return "denied";
                return MainActivity.this.locationRequestedBefore ? "blocked" : "denied";
            }

            // Native share sheet (the WebView has no navigator.share).
            // Fires a system ACTION_SEND chooser: WhatsApp, contacts, etc.
            @android.webkit.JavascriptInterface
            public void shareOffer(final String title, final String text, final String url) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_SUBJECT, title);
                        send.putExtra(Intent.EXTRA_TEXT, text + "\n" + url);
                        Intent chooser = Intent.createChooser(send, "Share this offer");
                        try {
                            MainActivity.this.startActivity(chooser);
                        } catch (Exception ignored) {}
                    }
                });
            }

            // ML Kit Document Scanner: on-device auto edge-detect + perspective
            // correction (the same class of scanner as Samsung Camera's).
            // Launch from the page via window.StoopSaveApp.scanDocument().
            // Result delivered as a data URL to window.onNativeScanResult(url),
            // or the stoopsave:scan-result CustomEvent as fallback.
            // Cancellation -> window.onNativeScanCancelled() (or
            // stoopsave:scan-cancelled event); failure ->
            // window.onNativeScanError(code) (or stoopsave:scan-error event).
            @android.webkit.JavascriptInterface
            public void scanDocument() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            GmsDocumentScannerOptions options =
                                new GmsDocumentScannerOptions.Builder()
                                    .setGalleryImportAllowed(false)
                                    .setPageLimit(1)
                                    .setResultFormats(
                                        GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                                    .setScannerMode(
                                        GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                                    .build();
                            com.google.mlkit.vision.documentscanner.GmsDocumentScanner
                                scanner = GmsDocumentScanning.getClient(options);
                            scanner.getStartScanIntent(MainActivity.this)
                                .addOnSuccessListener(
                                    new com.google.android.gms.tasks.OnSuccessListener<IntentSender>() {
                                        @Override public void onSuccess(IntentSender intentSender) {
                                            try {
                                                MainActivity.this.startIntentSenderForResult(
                                                    intentSender, SCAN_REQUEST_CODE,
                                                    null, 0, 0, 0);
                                            } catch (IntentSender.SendIntentException e) {
                                                MainActivity.this.notifyScanError("launch-failed");
                                            }
                                        }
                                    })
                                .addOnFailureListener(
                                    new com.google.android.gms.tasks.OnFailureListener() {
                                        @Override public void onFailure(Exception e) {
                                            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                                            MainActivity.this.notifyScanError("unavailable:" + detail);
                                        }
                                    });
                        } catch (Throwable t) {
                            MainActivity.this.notifyScanError("error");
                        }
                    }
                });
            }
        }, "StoopSaveApp");

        // FCM push: initialize Firebase and fetch the registration token.
        initFcm();

        // Fresh web content on every app upgrade: the WebView otherwise keeps
        // serving a cached copy of the page (and its old JS) after an update.
        // The server also sends no-store for the app shell; this is the
        // backstop for caches already on the device.
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("stoopsave", MODE_PRIVATE);
            int lastVc = prefs.getInt("last_version_code", -1);
            int thisVc = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
            if (lastVc != thisVc) {
                webView.clearCache(true);
                prefs.edit().putInt("last_version_code", thisVc).apply();
            }
        } catch (Exception ignored) { }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (!handleAuthDeepLink(getIntent()) && !handleAppLink(getIntent())) {
            webView.loadUrl(HOME_URL);
        }
    }

    /**
     * Starts Firebase (auto-initialized by FirebaseInitProvider from the
     * google-services values merged by Gradle) and fetches the FCM
     * registration token. Also ensures the notification channel exists and
     * asks for the POST_NOTIFICATIONS runtime permission on API 33+.
     */
    private void initFcm() {
        liveInstance = this;
        if (pendingFcmToken != null) {
            setFcmToken(pendingFcmToken);
            pendingFcmToken = null;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, 2004);
        }
        try {
            // FirebaseInitProvider (merged from the firebase-messaging AAR by
            // Gradle) auto-initializes FirebaseApp from the google-services
            // values before onCreate runs — no manual initializeApp() needed.
            // Guarded so a broken Firebase setup can never crash the app —
            // push just stays dormant and getFcmToken() keeps returning "".
            com.google.firebase.FirebaseApp app =
                    com.google.firebase.FirebaseApp.getInstance();
            if (app == null) {
                fcmDebug = "init:returned-null";
                return;
            }
            fcmDebug = "init:ok";
            // Standard getToken() on a background executor. Returns the token
            // string directly in the Task — no dependence on onNewToken() for
            // the initial fetch. 60s watchdog with 3 retries: never leave the bridge hanging.
            java.util.concurrent.Executor bg =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            final long t0 = System.currentTimeMillis();
            final java.util.concurrent.atomic.AtomicBoolean settled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            bg.execute(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
                    if (settled.compareAndSet(false, true)) {
                        fcmDebug = "token:timeout";
                    }
                }
            });
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .getToken()
                    .addOnCompleteListener(bg,
                    new com.google.android.gms.tasks.OnCompleteListener<String>() {
                        @Override
                        public void onComplete(com.google.android.gms.tasks.Task<String> task) {
                            if (!settled.compareAndSet(false, true)) return; // watchdog won
                            long dt = System.currentTimeMillis() - t0;
                            if (task.isSuccessful() && task.getResult() != null) {
                                setFcmToken(task.getResult());
                                fcmDebug = "token:ok:" + dt + "ms";
                            } else {
                                Exception e = task.getException();
                                fcmDebug = "token:failed:"
                                        + (e == null ? "null"
                                           : e.getClass().getSimpleName() + ":"
                                           + String.valueOf(e.getMessage()));
                            }
                        }
                    });
        } catch (Throwable t) {
            fcmDebug = "init:threw:" + t.getClass().getSimpleName() + ":"
                    + String.valueOf(t.getMessage());
            // A broken Firebase setup can never crash the app — push just
            // stays dormant and getFcmToken() keeps returning "".
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleAuthDeepLink(intent)) {
            handleAppLink(intent);
        }
    }

    /**
     * Handles the stoopsave://auth deep link fired by the OAuth callback page
     * in the external browser. Returns true when the intent was an auth link
     * (the WebView navigation is then owned by this method).
     */
    private boolean handleAuthDeepLink(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (data == null || !"stoopsave".equals(data.getScheme())
                || !"auth".equals(data.getHost())) {
            return false;
        }
        String session = data.getQueryParameter("session");
        if (session != null && !session.isEmpty()) {
            pendingOAuthSession = session;
            // Persist to SharedPreferences as backup: the frontend can pull
            // it via getPendingOAuthSession() if the onPageFinished injection
            // misses (timing/race conditions).
            try {
                getSharedPreferences("stoopsave", MODE_PRIVATE)
                    .edit()
                    .putString("pending_oauth_session", session)
                    .apply();
            } catch (Exception ignored) { }
        }
        webView.loadUrl(HOME_URL);
        return true;
    }

    /**
     * Handles Android App Links (https://stoopsave.com/app...), e.g. from
     * push-notification taps. Loads the link URL directly in the WebView
     * so the user lands on the right screen (e.g. ?digest=1 opens the
     * weekly digest view) instead of the browser. Returns true when handled.
     */
    private boolean handleAppLink(Intent intent) {
        if (intent == null || webView == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)) return false;
        Uri data = intent.getData();
        if (data == null || !"https".equals(data.getScheme())
                || !"stoopsave.com".equals(data.getHost())) {
            return false;
        }
        String path = data.getPath();
        if (path == null || !path.startsWith("/app")) return false;
        webView.loadUrl(data.toString());
        return true;
    }

    /** Deliver an ML Kit scan failure to the page. */
    private void notifyScanError(final String code) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(
                        "(function(){try{var c="
                        + org.json.JSONObject.quote(code) + ";"
                        + "if(window.onNativeScanError){window.onNativeScanError(c);}else{"
                        + "window.dispatchEvent(new CustomEvent('stoopsave:scan-error',"
                        + "{detail:{code:c}}));}"
                        + "}catch(e){}})();", null);
                } catch (Exception ignored) {}
            }
        });
    }

    /** Deliver an ML Kit scan cancellation to the page. */
    private void notifyScanCancelled() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(
                        "(function(){try{"
                        + "if(window.onNativeScanCancelled){window.onNativeScanCancelled();}else{"
                        + "window.dispatchEvent(new Event('stoopsave:scan-cancelled'));}"
                        + "}catch(e){}})();", null);
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Deliver scanned JPEG bytes to the page as a data URL. Chunked into
     * ~400KB pieces because evaluateJavascript crosses Binder (~1MB
     * transaction limit). The page receives
     * window.onNativeScanResult(dataUrl), or the stoopsave:scan-result
     * CustomEvent as a fallback.
     */
    private void deliverScanResult(final byte[] jpegBytes) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    String base64 = android.util.Base64.encodeToString(
                        jpegBytes, android.util.Base64.NO_WRAP);
                    final int CHUNK = 400 * 1024;
                    int total = (base64.length() + CHUNK - 1) / CHUNK;
                    webView.evaluateJavascript(
                        "window.__scanChunks=[];window.__scanTotal=" + total + ";",
                        null);
                    for (int i = 0; i < total; i++) {
                        int end = Math.min(base64.length(), (i + 1) * CHUNK);
                        String part = base64.substring(i * CHUNK, end);
                        webView.evaluateJavascript(
                            "window.__scanChunks.push("
                            + org.json.JSONObject.quote(part) + ");",
                            null);
                    }
                    webView.evaluateJavascript(
                        "(function(){try{"
                        + "var b64=window.__scanChunks.join('');"
                        + "window.__scanChunks=null;"
                        + "var url='data:image/jpeg;base64,'+b64;"
                        + "if(window.onNativeScanResult){window.onNativeScanResult(url);}else{"
                        + "window.dispatchEvent(new CustomEvent('stoopsave:scan-result',"
                        + "{detail:{dataUrl:url}}));}"
                        + "}catch(e){}})();", null);
                } catch (Exception e) {
                    notifyScanError("deliver-failed");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // ML Kit Document Scanner result.
        if (requestCode == SCAN_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    GmsDocumentScanningResult result =
                        GmsDocumentScanningResult.fromActivityResultIntent(data);
                    if (result != null && result.getPages() != null
                            && !result.getPages().isEmpty()
                            && result.getPages().get(0).getImageUri() != null) {
                        final Uri imageUri = result.getPages().get(0).getImageUri();
                        // Read the bytes off the UI thread.
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try {
                                    InputStream in = getContentResolver()
                                        .openInputStream(imageUri);
                                    ByteArrayOutputStream out =
                                        new ByteArrayOutputStream();
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = in.read(buf)) != -1) {
                                        out.write(buf, 0, n);
                                    }
                                    in.close();
                                    deliverScanResult(out.toByteArray());
                                } catch (Exception e) {
                                    notifyScanError("read-failed");
                                }
                            }
                        }).start();
                    } else {
                        notifyScanError("no-pages");
                    }
                } catch (Exception e) {
                    notifyScanError("result-error");
                }
            } else if (resultCode == RESULT_CANCELED) {
                notifyScanCancelled();
            } else {
                notifyScanError("failed");
            }
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (cameraPhotoUri != null) {
                // Camera capture with EXTRA_OUTPUT: data is null, use our URI.
                results = new Uri[]{cameraPhotoUri};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraPhotoUri = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Nothing to wake: the page re-checks cameraState() / StoopSaveApp
        // before each scan, so a retry after allowing just works.
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Background-audio law: pause page media/timers and fire
        // visibilitychange -> hidden in the WebView, so nothing keeps
        // playing after the user leaves the app.
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (liveInstance == this) liveInstance = null;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
