package com.sanrotech.pos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.app.DownloadManager;
import android.print.PrintManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.net.URISyntaxException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.json.JSONException;

public class MainActivity extends Activity {
    private static final String START_URL = "https://test-pos.sanrotech.com/";
    private static final String ALLOWED_HOST = "test-pos.sanrotech.com";
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int CAMERA_PERMISSION_REQUEST = 2002;
    private static final int BLUETOOTH_PERMISSION_REQUEST = 2003;
    private static final int EPSON_USB_VENDOR_ID = 0x04B8;
    static final String USB_PERMISSION_ACTION = "com.sanrotech.pos.testing.USB_PERMISSION_V106";
    static final String USB_PERMISSION_RESULT_ACTION = "com.sanrotech.pos.testing.USB_PERMISSION_LOCAL_V106";
    private static final String PREF_USB_DEVICE = "usb_device";
    private static final String PRINTER_PREFS = "sanro_tunasbaru_printer_local_v1";
    private static final String PREF_DEVICE_ADDRESS = "device_address";
    private static final String PREF_DEVICE_NAME = "device_name";
    // Owner/hardware gate: keep false until drawer voltage and pinout are verified
    // and Rio explicitly approves a physical test. No command is sent by default.
    private static final boolean CASH_DRAWER_KICK_ENABLED = false;
    private static final long CASH_DRAWER_REQUEST_COOLDOWN_MS = 1200L;
    private static final UUID SERIAL_PORT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private final ExecutorService printWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean printBusy = new AtomicBoolean(false);
    private final AtomicBoolean cashDrawerBusy = new AtomicBoolean(false);
    private final AtomicLong lastCashDrawerRequestMs = new AtomicLong(0L);
    private UsbManager usbManager;
    private BroadcastReceiver usbPermissionReceiver;
    private boolean usbReceiverRegistered;
    private volatile BluetoothSocket activeBluetoothSocket;
    private volatile String cashDrawerPageToken = "";
    private boolean choosePrinterAfterPermission;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progressBar, pp);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        applySystemBarSafeArea(root);
        usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        configureWebView();
        if (savedInstanceState == null) webView.loadUrl(START_URL); else webView.restoreState(savedInstanceState);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void applySystemBarSafeArea(View root) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int leftInset = 0;
                int topInset = 0;
                int rightInset = 0;
                int bottomInset = 0;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Insets safeInsets = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    leftInset = safeInsets.left;
                    topInset = safeInsets.top;
                    rightInset = safeInsets.right;
                    bottomInset = safeInsets.bottom;
                } else {
                    leftInset = insets.getSystemWindowInsetLeft();
                    topInset = insets.getSystemWindowInsetTop();
                    rightInset = insets.getSystemWindowInsetRight();
                    bottomInset = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(leftInset, topInset, rightInset, bottomInset);
                return insets;
            });
            root.requestApplyInsets();
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return handleNavigation(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return handleNavigation(Uri.parse(url)); }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { super.onPageStarted(view,url,favicon); cashDrawerPageToken = ""; progressBar.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView view, String url) { super.onPageFinished(view,url); progressBar.setVisibility(View.GONE); CookieManager.getInstance().flush(); injectNativePrintBridge(); injectCashDrawerTestButton(); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) { super.onReceivedError(view,request,error); if (request.isForMainFrame()) showConnectionError(); }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); Toast.makeText(MainActivity.this,"Koneksi aman gagal diverifikasi.",Toast.LENGTH_LONG).show(); }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) { progressBar.setProgress(p); progressBar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE); }
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); return true; }
                catch (ActivityNotFoundException e) { filePathCallback = null; Toast.makeText(MainActivity.this,"Tidak ada aplikasi pemilih file.",Toast.LENGTH_SHORT).show(); return false; }
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean asksCamera = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) { asksCamera = true; break; }
                    if (!asksCamera || !isTrustedWebOrigin(request.getOrigin())) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    else { pendingWebPermissionRequest = request; requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST); }
                });
            }
        });

        webView.setDownloadListener((url,ua,cd,mime,len) -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.addRequestHeader("User-Agent", ua);
                String cookie = CookieManager.getInstance().getCookie(url); if (cookie != null) req.addRequestHeader("Cookie", cookie);
                req.setMimeType(mime);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this,"File sedang diunduh.",Toast.LENGTH_SHORT).show();
            } catch(Exception e) { openExternal(Uri.parse(url)); }
        });
    }

    private boolean isTrustedWebOrigin(Uri origin) { return origin != null && "https".equalsIgnoreCase(origin.getScheme()) && ALLOWED_HOST.equalsIgnoreCase(origin.getHost()); }
    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if ("https".equals(scheme) && ALLOWED_HOST.equals(host)) return false;
        if ("intent".equals(scheme)) {
            try { Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME); if (i.resolveActivity(getPackageManager()) != null) startActivity(i); }
            catch (URISyntaxException ignored) {}
            return true;
        }
        openExternal(uri); return true;
    }
    private void openExternal(Uri uri) { try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch(Exception e) { Toast.makeText(this,"Tidak ada aplikasi untuk membuka tautan ini.",Toast.LENGTH_SHORT).show(); } }
    private void injectNativePrintBridge() {
        String js = "(function(){if(window.__sanroNativePrintInstalled)return;window.__sanroNativePrintInstalled=true;window.__sanroOriginalPrint=window.print;window.print=function(){try{AndroidBridge.printPage();}catch(e){if(window.__sanroOriginalPrint)window.__sanroOriginalPrint();}};})();";
        webView.evaluateJavascript(js, null);
    }
    private boolean isTrustedReceiptPage() {
        if (webView == null) return false;
        Uri uri = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        return isTrustedWebOrigin(uri) && "/print-nota-android.html".equals(uri.getPath());
    }

    private boolean isTrustedPrinterSettingsPage() {
        if (webView == null) return false;
        Uri uri = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        if (!isTrustedWebOrigin(uri)) return false;
        String path = uri.getPath();
        return "/setting".equals(path) || "/setting.html".equals(path) || "/sanro-test-setting.html".equals(path);
    }

    private boolean isTrustedCashDrawerTestPage() {
        if (webView == null) return false;
        Uri uri = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        return isTrustedWebOrigin(uri) && "/sanro-test-setting.html".equals(uri.getPath());
    }

    /** Test-only control is injected on the dedicated TESTING settings route. */
    private void injectCashDrawerTestButton() {
        if (!isTrustedCashDrawerTestPage()) return;
        cashDrawerPageToken = UUID.randomUUID().toString();
        final String pageToken = cashDrawerPageToken;
        String js = "(function(){if(document.getElementById('sanroCashDrawerTest'))return;" +
                "var b=document.createElement('button');b.id='sanroCashDrawerTest';b.type='button';" +
                "b.textContent='TEST CASH DRAWER';b.setAttribute('aria-label','Test cash drawer via Bluetooth printer');" +
                "b.style.cssText='position:fixed;right:16px;bottom:16px;z-index:2147483000;padding:14px 18px;border:0;border-radius:8px;background:#6b7280;color:#fff;font-weight:700;box-shadow:0 3px 12px #0005';" +
                "var s=document.createElement('div');s.id='sanroCashDrawerStatus';s.setAttribute('role','status');" +
                "s.textContent='Drawer-kick NONAKTIF; belum ada pulsa yang dikirim.';" +
                "s.style.cssText='position:fixed;right:16px;bottom:72px;z-index:2147483000;padding:8px 10px;background:#fff;color:#111;border:1px solid #9ca3af;border-radius:6px;font-size:12px';" +
                "b.addEventListener('click',function(){if(b.disabled)return;b.disabled=true;b.style.opacity='.6';" +
                "s.textContent='Meminta status test cash drawer…';try{AndroidBridge.testBluetoothCashDrawer('" + pageToken + "');}" +
                "catch(e){s.textContent='Bridge test drawer tidak tersedia.';b.disabled=false;b.style.opacity='1';}});" +
                "window.addEventListener('sanro:bluetooth-printer',function(e){var d=e&&e.detail;if(!d||!String(d.type||'').startsWith('drawer-'))return;" +
                "s.textContent=d.message||'Status test drawer tidak tersedia.';b.disabled=false;b.style.opacity='1';});" +
                "document.body.appendChild(b);document.body.appendChild(s);})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission") // Permission is checked before gated Bluetooth state access; SecurityException is contained.
    private String printerStateJson() {
        JSONObject state = new JSONObject();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            state.put("bridgeVersion", 1);
            state.put("supported", adapter != null);
            state.put("permission", hasBluetoothPermission());
            state.put("enabled", adapter != null && hasBluetoothPermission() && adapter.isEnabled());
            state.put("configured", !getPreferences(Context.MODE_PRIVATE).getString(PREF_DEVICE_ADDRESS, "").isEmpty());
            state.put("name", getPreferences(Context.MODE_PRIVATE).getString(PREF_DEVICE_NAME, ""));
            state.put("busy", printBusy.get());
        } catch (Exception ignored) {}
        return state.toString();
    }

    private void printerEvent(String kind, String message) { printerEvent(kind, message, ""); }

    private void printerEvent(String kind, String message, String jobId) {
        runOnUiThread(() -> {
            if (!isTrustedReceiptPage() && !isTrustedPrinterSettingsPage()) return;
            JSONObject payload = new JSONObject();
            try {
                payload.put("type", kind);
                payload.put("message", message);
                payload.put("state", new JSONObject(printerStateJson()));
                if (jobId != null && !jobId.isEmpty()) payload.put("jobId", jobId);
            } catch (JSONException ignored) { return; }
            String script = "window.dispatchEvent(new CustomEvent('sanro:bluetooth-printer', {detail:" + payload + "}));";
            webView.evaluateJavascript(script, null);
        });
    }

    @SuppressLint("MissingPermission") // Runtime permission gate is retained and permission races are caught below.
    private void choosePrinter() {
        if (!isTrustedReceiptPage() && !isTrustedPrinterSettingsPage()) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { printerEvent("error", "Perangkat Android ini tidak mendukung Bluetooth."); return; }
        if (!hasBluetoothPermission()) {
            choosePrinterAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
            return;
        }
        if (!adapter.isEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Bluetooth belum aktif")
                    .setMessage("Aktifkan Bluetooth, pasangkan Panda PRJ-80BT di Pengaturan Android, lalu pilih printer kembali.")
                    .setPositiveButton("Buka pengaturan", (d, w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Batal", null).show();
            return;
        }
        final Set<BluetoothDevice> paired;
        try {
            if (!hasBluetoothPermission()) { requestBluetoothPermissionForPrinter(); return; }
            paired = adapter.getBondedDevices();
        } catch (SecurityException e) {
            printerEvent("error", "Izin Bluetooth tidak tersedia. Berikan izin lalu pilih printer kembali."); return;
        }
        if (paired == null || paired.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Belum ada printer berpasangan")
                    .setMessage("Pair Panda PRJ-80BT melalui pengaturan Bluetooth Android terlebih dahulu.")
                    .setPositiveButton("Pengaturan Bluetooth", (d, w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Tutup", null).show();
            return;
        }
        ArrayList<BluetoothDevice> list = new ArrayList<>(paired);
        list.sort(Comparator.comparing(x -> x.getName() == null ? "" : x.getName(), String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BluetoothDevice device = list.get(i);
            try {
                labels[i] = (device.getName() == null ? "Bluetooth Device" : device.getName()) + " — " + device.getAddress();
            } catch (SecurityException e) { labels[i] = "Bluetooth Device"; }
        }
        new AlertDialog.Builder(this).setTitle("Pilih printer Bluetooth yang sudah di-pair")
                .setItems(labels, (d, which) -> {
                    BluetoothDevice device = list.get(which);
                    try {
                        if (!hasBluetoothPermission()) { requestBluetoothPermissionForPrinter(); return; }
                        String name = device.getName() == null ? "Bluetooth Device" : device.getName();
                        String address = device.getAddress();
                        getPreferences(Context.MODE_PRIVATE).edit().putString(PREF_DEVICE_ADDRESS, address)
                                .putString(PREF_DEVICE_NAME, name).apply();
                        printerEvent("selected", "Printer dipilih: " + name);
                    } catch (SecurityException e) {
                        printerEvent("error", "Izin Bluetooth berubah. Berikan izin lalu pilih printer kembali.");
                    }
                }).setNegativeButton("Batal", null).show();
    }

    private void requestBluetoothPermissionForPrinter() {
        choosePrinterAfterPermission = true;
        if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
    }

    @SuppressLint("MissingPermission") // Permission is checked before API calls and again on the worker; SecurityException is handled.
    private void sendBluetoothReceipt(String payload) {
        if (!isTrustedReceiptPage()) return;
        String jobId = "";
        try { jobId = new JSONObject(payload == null ? "{}" : payload).optString("jobId", ""); }
        catch (Exception ignored) {}
        if (!jobId.matches("[A-Za-z0-9._:-]{1,96}")) {
            printerEvent("error", "Job ID cetak tidak valid.", jobId); return;
        }
        if (payload == null || payload.length() > 48000) {
            printerEvent("error", "Ukuran data nota tidak valid.", jobId); return;
        }
        if (!hasBluetoothPermission()) {
            printerEvent("error", "Izin Bluetooth belum diberikan. Tekan Pilih Printer terlebih dahulu.", jobId); return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            printerEvent("error", "Bluetooth tidak aktif.", jobId); return;
        }
        String address = getPreferences(Context.MODE_PRIVATE).getString(PREF_DEVICE_ADDRESS, "");
        if (address.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(address)) {
            printerEvent("error", "Pilih printer yang sudah di-pair terlebih dahulu.", jobId); return;
        }
        if (!printBusy.compareAndSet(false, true)) {
            printerEvent("error", "Masih ada satu job cetak yang sedang dikirim.", jobId); return;
        }
        byte[] receiptBytes;
        try {
            receiptBytes = BluetoothReceiptPrinter.serialize(new JSONObject(payload));
        } catch (Exception e) {
            printBusy.set(false);
            printerEvent("error", "Data nota ditolak: " + e.getMessage(), jobId);
            return;
        }
        printerEvent("sending", "Menghubungkan dan mengirim satu job ke printer. Jangan mencetak ulang dulu.", jobId);
        final String eventJobId = jobId;
        printWorker.execute(() -> {
            BluetoothSocket socket = null;
            try {
                if (!hasBluetoothPermission()) throw new SecurityException("BLUETOOTH_CONNECT tidak diberikan");
                BluetoothDevice device = adapter.getRemoteDevice(address);
                socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID);
                activeBluetoothSocket = socket;
                socket.connect();
                OutputStream stream = socket.getOutputStream();
                // Pace the SPP stream and keep the socket open briefly after the last
                // byte. A flush only drains Java's stream, not the printer buffer.
                BluetoothJobWriter.send(stream, receiptBytes);
                printerEvent("sent", "Semua byte nota sudah dikirim ke Bluetooth. Pastikan footer dan cutter pada kertas; hasil fisik belum terverifikasi.", eventJobId);
            } catch (SecurityException e) {
                printerEvent("error", "Izin Bluetooth tidak tersedia saat job berjalan. Hasil cetak belum pasti; periksa kertas.", eventJobId);
            } catch (Exception e) {
                // A socket error may follow a partial write; NEVER retry automatically.
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                printerEvent("error", "Hasil cetak belum pasti (Bluetooth: " + e.getClass().getSimpleName() + "). Periksa kertas sebelum mencoba lagi.", eventJobId);
            } finally {
                activeBluetoothSocket = null;
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                printBusy.set(false);
            }
        });
    }

    @SuppressLint("MissingPermission") // Permission is checked before API calls and again on the worker; SecurityException is handled.
    private void sendBluetoothTestPrint(String requestedJobId) {
        if (!isTrustedPrinterSettingsPage()) return;
        String jobId = requestedJobId == null ? "" : requestedJobId;
        if (!jobId.matches("bt-test-[A-Za-z0-9._:-]{1,80}")) return;
        if (!hasBluetoothPermission()) { printerEvent("error", "Izin Bluetooth belum diberikan.", jobId); return; }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) { printerEvent("error", "Bluetooth tidak aktif.", jobId); return; }
        String address = getPreferences(Context.MODE_PRIVATE).getString(PREF_DEVICE_ADDRESS, "");
        String printerName = getPreferences(Context.MODE_PRIVATE).getString(PREF_DEVICE_NAME, "Bluetooth Device");
        if (address.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(address)) {
            printerEvent("error", "Pilih printer Bluetooth terlebih dahulu.", jobId); return;
        }
        if (!printBusy.compareAndSet(false, true)) {
            printerEvent("error", "Printer sedang mengirim job. Tunggu sampai selesai.", jobId); return;
        }
        final byte[] receiptBytes;
        try { receiptBytes = BluetoothReceiptPrinter.serializeTest(printerName); }
        catch (Exception e) { printBusy.set(false); printerEvent("error", "Test print tidak dapat disiapkan.", jobId); return; }
        printerEvent("sending", "Mengirim test print ke printer Bluetooth.", jobId);
        printWorker.execute(() -> {
            BluetoothSocket socket = null;
            try {
                if (!hasBluetoothPermission()) throw new SecurityException("BLUETOOTH_CONNECT tidak diberikan");
                BluetoothDevice device = adapter.getRemoteDevice(address);
                socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID);
                activeBluetoothSocket = socket;
                socket.connect();
                BluetoothJobWriter.send(socket.getOutputStream(), receiptBytes);
                printerEvent("sent", "Data test print terkirim. Periksa kertas dan cutter; hasil fisik belum terverifikasi.", jobId);
            } catch (SecurityException e) {
                printerEvent("error", "Izin Bluetooth tidak tersedia saat test print berjalan. Periksa printer sebelum tindakan berikutnya.", jobId);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                printerEvent("error", "Test print belum pasti (Bluetooth: " + e.getClass().getSimpleName() + "). Periksa printer sebelum tindakan berikutnya.", jobId);
            } finally {
                activeBluetoothSocket = null;
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                printBusy.set(false);
                printerEvent("idle", "Printer siap.", jobId);
            }
        });
    }

    /**
     * A candidate ESC p sequence is deliberately guarded OFF by default.
     * "sent" is only stream delivery and never proves that the drawer opened.
     */
    @SuppressLint("MissingPermission")
    private void sendBluetoothCashDrawerTest(String pageToken) {
        if (!isTrustedCashDrawerTestPage() || pageToken == null || pageToken.isEmpty()
                || !pageToken.equals(cashDrawerPageToken)) return;
        String jobId = "drawer-test-" + UUID.randomUUID().toString().replace("-", "");
        long now = android.os.SystemClock.elapsedRealtime();
        long previous = lastCashDrawerRequestMs.getAndSet(now);
        if (previous > 0L && now - previous < CASH_DRAWER_REQUEST_COOLDOWN_MS) {
            printerEvent("drawer-error", "Permintaan test drawer ganda ditolak. Tunggu sebelum mencoba lagi.", jobId);
            return;
        }
        if (!cashDrawerBusy.compareAndSet(false, true)) {
            printerEvent("drawer-error", "Job test cash drawer lain masih aktif.", jobId);
            return;
        }
        if (!CASH_DRAWER_KICK_ENABLED) {
            printerEvent("drawer-disabled", "Drawer-kick NONAKTIF. Tidak ada pulsa yang dikirim; konfirmasi tegangan/pinout dan persetujuan Rio diperlukan.", jobId);
            cashDrawerBusy.set(false);
            return;
        }
        if (!hasBluetoothPermission()) {
            drawerTestError("Izin Bluetooth belum diberikan.", jobId);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            drawerTestError("Bluetooth tidak aktif.", jobId);
            return;
        }
        String address = getPreferences(Context.MODE_PRIVATE).getString(PREF_DEVICE_ADDRESS, "");
        if (address.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(address)) {
            drawerTestError("Pilih printer Bluetooth terlebih dahulu.", jobId);
            return;
        }
        if (!printBusy.compareAndSet(false, true)) {
            drawerTestError("Printer sedang menjalankan job lain. Test drawer tidak dikirim.", jobId);
            return;
        }

        // Candidate only: ESC p m t1 t2. Values are not hardware-verified.
        // This block is unreachable unless the default-off Owner gate is changed.
        final byte[] kick = new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFF};
        printerEvent("drawer-sending", "Mengirim satu kandidat perintah drawer-kick; gerakan laci belum terverifikasi.", jobId);
        printWorker.execute(() -> {
            BluetoothSocket socket = null;
            try {
                if (!hasBluetoothPermission()) throw new SecurityException("BLUETOOTH_CONNECT tidak diberikan");
                BluetoothDevice device = adapter.getRemoteDevice(address);
                socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID);
                activeBluetoothSocket = socket;
                socket.connect();
                BluetoothJobWriter.send(socket.getOutputStream(), kick);
                printerEvent("drawer-sent", "Byte drawer-kick dikirim ke stream Bluetooth; ini bukan bukti laci terbuka. Tidak ada retry otomatis.", jobId);
            } catch (SecurityException e) {
                printerEvent("drawer-error", "Izin Bluetooth tidak tersedia; hasil pengiriman tidak pasti. Jangan retry otomatis.", jobId);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                printerEvent("drawer-error", "Status pengiriman tidak pasti (Bluetooth: " + e.getClass().getSimpleName() + "). Tidak ada retry otomatis.", jobId);
            } finally {
                activeBluetoothSocket = null;
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                printBusy.set(false);
                cashDrawerBusy.set(false);
                printerEvent("idle", "Printer siap.", jobId);
            }
        });
    }

    private void drawerTestError(String message, String jobId) {
        cashDrawerBusy.set(false);
        printerEvent("drawer-error", message, jobId);
    }

    /**
     * USB Printer Class = 7. Some Epson printers expose vendor-specific class 255;
     * Epson VID is allowed in that case, but a bulk OUT endpoint is still mandatory.
     * This only supports raw USB bulk streams, not a proprietary Windows driver.
     */
    private UsbInterface findPrinterInterface(UsbDevice device) {
        if(device==null)return null;
        for(int i=0;i<device.getInterfaceCount();i++){
            UsbInterface iface=device.getInterface(i);
            if(iface.getInterfaceClass()!=UsbConstants.USB_CLASS_PRINTER &&
                    !(device.getVendorId()==EPSON_USB_VENDOR_ID && iface.getInterfaceClass()==UsbConstants.USB_CLASS_VENDOR_SPEC))continue;
            if(findBulkOut(iface)!=null)return iface;
        }
        return null;
    }

    private UsbEndpoint findBulkOut(UsbInterface iface) {
        for(int i=0;i<iface.getEndpointCount();i++){
            UsbEndpoint endpoint=iface.getEndpoint(i);
            if(endpoint.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.getDirection()==UsbConstants.USB_DIR_OUT)return endpoint;
        }
        return null;
    }

    private boolean isEpsonCandidate(UsbDevice device) {
        if(device==null)return false;
        return device.getVendorId()==EPSON_USB_VENDOR_ID;
    }

    private UsbDevice selectedUsbDevice() {
        if(usbManager==null)return null;
        String selected=getPreferences(Context.MODE_PRIVATE).getString(PREF_USB_DEVICE,"");
        UsbDevice found=usbManager.getDeviceList().get(selected);
        if(found!=null && isEpsonCandidate(found))return found;
        return null; // No automatic reroute to a possibly different USB peripheral.
    }

    private String usbStateJson() {
        JSONObject state=new JSONObject();
        try {
            UsbDevice selected=selectedUsbDevice();
            state.put("bridgeVersion",1);
            state.put("hostSupported",getPackageManager().hasSystemFeature("android.hardware.usb.host"));
            state.put("connected",selected!=null);
            state.put("configured",!getPreferences(Context.MODE_PRIVATE).getString(PREF_USB_DEVICE,"").isEmpty());
            state.put("permission",selected!=null&&usbManager.hasPermission(selected));
            state.put("compatibleInterface",selected!=null&&findPrinterInterface(selected)!=null);
            state.put("name",selected==null?"":usbLabel(selected));
            state.put("busy",printBusy.get());
        }catch(Exception ignored){}
        return state.toString();
    }

    private String usbLabel(UsbDevice d) {
        String name;
        try{name=d.getProductName();}catch(SecurityException e){name=null;}
        if(name==null||name.trim().isEmpty())name="Epson USB";
        return name+" (VID "+String.format("%04X",d.getVendorId())+", PID "+String.format("%04X",d.getProductId())+")";
    }

    private void usbEvent(String kind,String message){
        runOnUiThread(()->{
            if(!isTrustedReceiptPage())return;
            JSONObject event=new JSONObject();
            try {
                event.put("type",kind);
                event.put("message",message);
                event.put("state",new JSONObject(usbStateJson()));
            }catch(JSONException ignored){return;}
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('sanro:usb-printer',{detail:"+event+"}));",null);
        });
    }

    private void registerUsbReceiver(){
        usbPermissionReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(!USB_PERMISSION_RESULT_ACTION.equals(intent.getAction()))return;
                UsbDevice device;
                if(Build.VERSION.SDK_INT>=33)device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice.class);
                else device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if(device==null || selectedUsbDevice()==null || !device.getDeviceName().equals(selectedUsbDevice().getDeviceName()))return;
                boolean granted=intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)
                    && usbManager.hasPermission(device);
                usbEvent(granted?"selected":"error",granted?
                    "Izin USB diberikan. Siap Uji Epson USB.":"Izin USB ditolak. Cabut dan sambungkan ulang jika diperlukan.");
            }
        };
        IntentFilter filter=new IntentFilter(USB_PERMISSION_RESULT_ACTION);
        if(Build.VERSION.SDK_INT>=33) {
            registerReceiver(usbPermissionReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= 26) {
            registerReceiver(usbPermissionReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyUsbReceiver(filter);
        }
        usbReceiverRegistered=true;
    }

    // Only called on API 24-25, where receiver export flags do not exist.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyUsbReceiver(IntentFilter filter) {
        registerReceiver(usbPermissionReceiver, filter);
    }

    private void chooseUsbPrinter(){
        if(!isTrustedReceiptPage())return;
        if(printBusy.get()){usbEvent("error","Tunggu job cetak yang sedang dikirim selesai.");return;}
        if(usbManager==null){usbEvent("error","USB Host tidak tersedia pada Android ini.");return;}
        ArrayList<UsbDevice> printers=new ArrayList<>();
        for(UsbDevice d:usbManager.getDeviceList().values())if(isEpsonCandidate(d))printers.add(d);
        if(printers.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Epson LX-300+ USB belum terdeteksi")
                .setMessage("Sambungkan Epson ke Android memakai adapter USB OTG, hidupkan printer, lalu coba Pilih Epson USB kembali. Tidak perlu menyambung ke Windows.")
                .setPositiveButton("Tutup",null).show();
            usbEvent("error","Belum ada perangkat printer USB yang terdeteksi.");
            return;
        }
        String[] labels=new String[printers.size()];
        for(int i=0;i<printers.size();i++)labels[i]=usbLabel(printers.get(i));
        new AlertDialog.Builder(this).setTitle("Pilih Epson LX-300+ melalui USB OTG")
            .setItems(labels,(dialog,index)->{
                UsbDevice dev=printers.get(index);
                if(findPrinterInterface(dev)==null){
                    usbEvent("error","Perangkat terdeteksi tetapi tidak ada endpoint USB bulk OUT yang kompatibel. Protokol/driver tambahan mungkin diperlukan.");
                    return;
                }
                getPreferences(Context.MODE_PRIVATE).edit().putString(PREF_USB_DEVICE,dev.getDeviceName()).apply();
                if(usbManager.hasPermission(dev)){
                    usbEvent("selected","Printer USB dipilih: "+usbLabel(dev));
                }else{
                    // USB permission result must carry the UsbDevice Parcelable: mutable PendingIntent.
                    // Explicit manifest receiver is required for mutable PendingIntent on recent Android.
                    Intent permissionIntent=new Intent(USB_PERMISSION_ACTION).setClass(this,UsbPermissionReceiver.class);
                    int flags=PendingIntent.FLAG_UPDATE_CURRENT;
                    if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
                    PendingIntent pi=PendingIntent.getBroadcast(this,0,permissionIntent,flags);
                    usbManager.requestPermission(dev,pi);
                    usbEvent("permission","Setujui dialog izin akses printer USB Android.");
                }
            }).setNegativeButton("Batal",null).show();
    }

    private void sendUsbPrint(String receiptPayload,boolean testOnly){
        if(!isTrustedReceiptPage())return;
        UsbDevice dev=selectedUsbDevice();
        if(dev==null){usbEvent("error","Printer USB belum terhubung atau perlu dipilih ulang.");return;}
        UsbInterface iface=findPrinterInterface(dev);
        if(iface==null){usbEvent("error","Interface USB printer tidak kompatibel.");return;}
        if(!usbManager.hasPermission(dev)){usbEvent("error","Izin USB belum diberikan. Tekan Pilih Epson USB.");return;}
        if(receiptPayload!=null && receiptPayload.length()>48000){usbEvent("error","Ukuran nota melebihi batas.");return;}
        if(!printBusy.compareAndSet(false,true)){
            usbEvent("error","Masih ada job cetak yang sedang dikirim ke printer.");return;
        }
        byte[] bytes;
        try{
            bytes=testOnly?EpsonEscpReceiptPrinter.testPage():
                EpsonEscpReceiptPrinter.serialize(new JSONObject(receiptPayload));
        }catch(Exception e){
            printBusy.set(false);usbEvent("error","Data nota USB ditolak: "+e.getMessage());return;
        }
        usbEvent("sending",testOnly?"Mengirim halaman TEST melalui USB. Tunggu hasil kertas.":
                "Mengirim satu nota ESC/P melalui USB. Jangan cetak ulang sebelum kertas diperiksa.");
        printWorker.execute(()->{
            UsbDeviceConnection connection=null;
            boolean claimed=false;
            try{
                connection=usbManager.openDevice(dev);
                if(connection==null)throw new IOException("USB openDevice gagal");
                if(!connection.claimInterface(iface,true))throw new IOException("USB claimInterface gagal");
                claimed=true;
                UsbEndpoint output=findBulkOut(iface);
                if(output==null)throw new IOException("Tidak ada endpoint bulk OUT");
                int cursor=0;
                while(cursor<bytes.length){
                    int length=Math.min(1024,bytes.length-cursor);
                    // bulkTransfer with explicit offsets is supported since API 18.
                    int n=connection.bulkTransfer(output,bytes,cursor,length,5000);
                    if(n<=0)throw new IOException("USB bulkTransfer gagal; hasil mungkin parsial");
                    cursor+=n;
                }
                usbEvent("sent", "Data ESC/P telah ditransfer via USB ("+bytes.length+" byte). Periksa kertas printer; hasil fisik belum dipastikan.");
            }catch(Exception e){
                usbEvent("error", "Hasil cetak USB belum pasti ("+e.getClass().getSimpleName()+"). Periksa printer dan kertas sebelum mengulang.");
            }finally{
                if(connection!=null){
                    if(claimed)try{connection.releaseInterface(iface);}catch(Exception ignored){}
                    connection.close();
                }
                printBusy.set(false);
            }
        });
    }

    private void showConnectionError() {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:sans-serif;padding:32px;color:#111827;background:#fff}button{padding:12px 18px;border:0;border-radius:8px;background:#111827;color:#fff;font-size:16px}p{line-height:1.5;color:#4b5563}</style></head><body><h2>Koneksi gagal</h2><p>Periksa internet lalu coba lagi.</p><button onclick=\"location.href='" + START_URL + "'\">Coba Lagi</button></body></html>";
        webView.loadDataWithBaseURL(START_URL, html, "text/html", "UTF-8", null);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && choosePrinterAfterPermission) {
                choosePrinterAfterPermission = false;
                choosePrinter();
            } else {
                choosePrinterAfterPermission = false;
                printerEvent("error", "Izin Bluetooth ditolak. Buka Pengaturan aplikasi untuk memberikan izin.");
            }
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST && pendingWebPermissionRequest != null) {
            PermissionRequest r = pendingWebPermissionRequest; pendingWebPermissionRequest = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else { r.deny(); Toast.makeText(this,"Izin kamera diperlukan untuk scan barcode.",Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == FILE_CHOOSER_REQUEST) { if (filePathCallback == null) return; filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,data)); filePathCallback = null; }
    }
    @Override protected void onSaveInstanceState(Bundle outState) { webView.saveState(outState); super.onSaveInstanceState(outState); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) { webView.goBack(); return true; } return super.onKeyDown(keyCode,event); }
    @Override protected void onDestroy() {
        BluetoothSocket socket = activeBluetoothSocket;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        printWorker.shutdownNow();
        if(usbReceiverRegistered){try{unregisterReceiver(usbPermissionReceiver);}catch(Exception ignored){} usbReceiverRegistered=false;}
        if (webView != null) { webView.removeJavascriptInterface("AndroidBridge"); webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }

    private final class AndroidBridge {
        @JavascriptInterface public String getUsbPrinterState(){return usbStateJson();}
        @JavascriptInterface public void selectUsbPrinter(){runOnUiThread(()->chooseUsbPrinter());}
        @JavascriptInterface public void printUsbTest(){runOnUiThread(()->sendUsbPrint(null,true));}
        @JavascriptInterface public void printUsbReceipt(String data){runOnUiThread(()->sendUsbPrint(data,false));}
        @JavascriptInterface public String getBluetoothPrinterState() { return printerStateJson(); }
        @JavascriptInterface public void selectBluetoothPrinter() { runOnUiThread(() -> choosePrinter()); }
        @JavascriptInterface public void printBluetoothReceipt(String data) { runOnUiThread(() -> sendBluetoothReceipt(data)); }
        @JavascriptInterface public void printBluetoothTest(String jobId) { runOnUiThread(() -> sendBluetoothTestPrint(jobId)); }
        @JavascriptInterface public void testBluetoothCashDrawer(String pageToken) { runOnUiThread(() -> sendBluetoothCashDrawerTest(pageToken)); }
        @JavascriptInterface public void printPage() { runOnUiThread(() -> { try { PrintManager pm=(PrintManager)getSystemService(Context.PRINT_SERVICE); String name=getString(R.string.app_name)+" - Cetak"; pm.print(name,webView.createPrintDocumentAdapter(name),null); } catch(Exception e) { Toast.makeText(MainActivity.this,"Fitur cetak Android tidak tersedia.",Toast.LENGTH_LONG).show(); } }); }
    }
}
