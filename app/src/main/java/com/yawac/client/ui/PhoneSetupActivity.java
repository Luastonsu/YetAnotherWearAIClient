package com.yawac.client.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.yawac.client.R;
import com.yawac.client.data.ApiConfigStore;
import com.yawac.client.net.ApiConfig;
import com.yawac.client.net.ProvisioningServer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.EnumMap;
import java.util.Map;

/**
 * Serves a one-shot local web page so the phone can enter the API key. Works over
 * the watch's current Wi-Fi, including a phone hotspot; no Wi-Fi Direct required.
 */
public final class PhoneSetupActivity extends SettingsScreenActivity {
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    /** Test seam: the emulator has no Wi-Fi, so instrumentation can bind loopback. */
    static Inet4Address addressForTest;

    private ImageView qr;
    private View qrCard;
    private TextView status, urlView, help;
    private Handler handler;
    private ProvisioningServer server;
    private boolean saved;

    private final Runnable timeout = () -> {
        if (saved) return;
        stopServer();
        qrCard.setVisibility(View.GONE);
        urlView.setVisibility(View.GONE);
        status.setText(R.string.phone_setup_expired);
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        openSettingsLayout(R.layout.activity_phone_setup);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        qr = findViewById(R.id.setupQr);
        qrCard = findViewById(R.id.qrCard);
        status = findViewById(R.id.setupStatus);
        urlView = findViewById(R.id.setupUrl);
        help = findViewById(R.id.phoneSetupHelp);
        handler = new Handler(Looper.getMainLooper());
        nativeButtons(R.id.openWifiSettings, R.id.retrySetup, R.id.manualSetup);
        findViewById(R.id.openWifiSettings).setOnClickListener(v -> openWifiSettings());
        findViewById(R.id.retrySetup).setOnClickListener(v -> refresh());
        findViewById(R.id.manualSetup).setOnClickListener(v -> {
            stopServer();
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (!saved) refresh();
    }

    @Override protected void onPause() {
        stopServer();
        handler.removeCallbacks(timeout);
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopServer();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refresh() {
        stopServer();
        handler.removeCallbacks(timeout);
        qrCard.setVisibility(View.GONE);
        qr.setImageDrawable(null);
        urlView.setVisibility(View.GONE);
        help.setText(R.string.phone_setup_help);
        Inet4Address address = wifiAddress();
        if (address == null) {
            status.setText(R.string.phone_setup_no_wifi);
            return;
        }
        try {
            server = new ProvisioningServer(address,
                    (url, key, model) -> {
                        ApiConfig config = new ApiConfig(url, key, model);
                        String error = config.validationError();
                        if (error != null) return error;
                        try {
                            new ApiConfigStore(PhoneSetupActivity.this).save(config);
                            return null;
                        } catch (Exception failure) {
                            return "保存失败，请重试";
                        }
                    },
                    ApiConfig.DEEPSEEK_URL, ApiConfig.DEFAULT_MODEL,
                    () -> runOnUiThread(this::onSaved));
            server.start();
        } catch (IOException failure) {
            server = null;
            status.setText(R.string.phone_setup_error);
            return;
        }
        String url = server.setupUrl(address.getHostAddress());
        Bitmap bitmap = qrBitmap(url);
        if (bitmap == null) {
            stopServer();
            status.setText(R.string.phone_setup_error);
            return;
        }
        qr.setImageBitmap(bitmap);
        qrCard.setVisibility(View.VISIBLE);
        urlView.setText(url);
        urlView.setVisibility(View.VISIBLE);
        status.setText(R.string.phone_setup_ready);
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    private void onSaved() {
        if (saved) return;
        saved = true;
        handler.removeCallbacks(timeout);
        stopServer();
        qrCard.setVisibility(View.GONE);
        urlView.setVisibility(View.GONE);
        status.setText(R.string.phone_setup_saved);
        setResult(RESULT_OK);
        handler.postDelayed(this::finish, 1200);
    }

    private void stopServer() {
        ProvisioningServer current = server;
        server = null;
        if (current != null) current.stop();
    }

    private Inet4Address wifiAddress() {
        if (addressForTest != null) return addressForTest;
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                continue;
            }
            LinkProperties link = manager.getLinkProperties(network);
            if (link == null) continue;
            for (LinkAddress address : link.getLinkAddresses()) {
                InetAddress inet = address.getAddress();
                if (inet instanceof Inet4Address && !inet.isLoopbackAddress()
                        && !inet.isLinkLocalAddress()) {
                    return (Inet4Address) inet;
                }
            }
        }
        return null;
    }

    private Bitmap qrBitmap(String text) {
        int size = Math.round(154 * getResources().getDisplayMetrics().density);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            return bitmap;
        } catch (WriterException | IllegalArgumentException failure) {
            return null;
        }
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException failure) {
            Toast.makeText(this, R.string.phone_setup_wifi_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
