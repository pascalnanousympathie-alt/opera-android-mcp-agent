package com.nanou.operaagent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class MainActivity extends Activity {
    public static final String PREFS = "opera_android_agent";
    public static final String PREF_TOKEN = "agent_token";
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureToken();

        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(this);
        title.setText("Android Browser MCP Agent");
        title.setTextSize(24);
        title.setTextColor(0xFF202124);
        content.addView(title, matchWrap());

        TextView intro = new TextView(this);
        intro.setText("Companion for Chrome or Opera on this Android device. It does not modify the browser. Enable Accessibility below to let an explicitly configured local MCP client read visible labels and perform bounded actions.");
        intro.setTextSize(15);
        intro.setTextColor(0xFF424242);
        intro.setPadding(0, dp(12), 0, dp(18));
        content.addView(intro, matchWrap());

        content.addView(button("Enable Accessibility", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), matchWrap());

        content.addView(button("Check local bridge", v -> checkBridge()), matchWrap());

        content.addView(button("Copy pairing token", v -> copyToken()), matchWrap());

        content.addView(button("Copy local endpoint", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Opera Agent URL", "http://127.0.0.1:" + OperaAccessibilityService.PORT));
            Toast.makeText(this, "Local endpoint copied.", Toast.LENGTH_SHORT).show();
        }), matchWrap());

        TextView security = new TextView(this);
        security.setText(
                "Local API: 127.0.0.1:" + OperaAccessibilityService.PORT + "\n\n" +
                "Security: API binds to loopback only and requires a random pairing token. Password fields are blocked. Controls that appear to involve payment, sending, deletion, login, installation, or approval are blocked for manual action. The app does not submit forms automatically.\n\n" +
                "Limit: Android 8.1 does not expose Chrome DevTools Protocol for arbitrary Chrome or Opera tabs through this app. This agent uses Android Accessibility text/actions, not the hidden page DOM. Screenshots are not supported on this Android version.");
        security.setTextSize(13);
        security.setTextColor(0xFF616161);
        security.setPadding(0, dp(20), 0, dp(10));
        content.addView(security, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFFB71C1C);
        statusView.setPadding(0, dp(12), 0, 0);
        content.addView(statusView, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) refreshStatus();
    }

    private void refreshStatus() {
        boolean active = OperaAccessibilityService.isAvailable();
        statusView.setText(active
                ? "Accessibility service: running. Local API should be available at 127.0.0.1:" + OperaAccessibilityService.PORT + "."
                : "Accessibility service: not connected yet. Enable this service in Android Accessibility settings.");
    }

    private void checkBridge() {
        statusView.setText("Checking local bridge…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            String result;
            try {
                URL url = new URL("http://127.0.0.1:" + OperaAccessibilityService.PORT + "/health");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(2000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Authorization", "Bearer " + ensureToken());
                int code = connection.getResponseCode();
                InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();
                JSONObject json = new JSONObject(body.toString());
                result = code == 200 && json.optBoolean("ok")
                        ? "Local API: PASS. Accessibility: " + json.optBoolean("accessibility_enabled") + "."
                        : "Local API returned HTTP " + code + ".";
            } catch (Exception e) {
                result = "Local API not reachable. Enable the Accessibility service first, then try again.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String finalResult = result;
            runOnUiThread(() -> statusView.setText(finalResult));
        }, "opera-agent-health-check").start();
    }

    private String ensureToken() {
        String token = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TOKEN, null);
        if (token != null && token.length() >= 32) return token;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TOKEN, token).apply();
        return token;
    }

    private void copyToken() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Opera Agent pairing token", ensureToken()));
        Toast.makeText(this, "Token copied on this device. Do not share it in chat or screenshots.", Toast.LENGTH_LONG).show();
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

[executed on device: localhost (3da613ef-360b-4f88-b84a-ca0a3fb4bdb6)]