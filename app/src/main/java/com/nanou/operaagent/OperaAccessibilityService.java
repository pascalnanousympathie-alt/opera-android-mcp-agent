package com.nanou.operaagent;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class OperaAccessibilityService extends AccessibilityService {
    public static final int PORT = 18765;
    private static volatile OperaAccessibilityService instance;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile String lastPackageName = "";

    public static boolean isAvailable() {
        return instance != null && instance.running;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        running = true;
        serverThread = new Thread(this::serveLoop, "opera-agent-loopback-http");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            lastPackageName = event.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {
        // No queued gestures are kept; interruption simply stops future requests.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        closeServer();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        closeServer();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private String pairingToken() {
        String token = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.PREF_TOKEN, null);
        if (token != null && token.length() >= 32) return token;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit().putString(MainActivity.PREF_TOKEN, token).apply();
        return token;
    }

    private void serveLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(false);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread worker = new Thread(() -> handleClient(socket), "opera-agent-http-client");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Exception e) {
                    if (running) {
                        try { Thread.sleep(250L); } catch (InterruptedException ignored) { }
                    }
                }
            }
        } catch (Exception ignored) {
            // The app UI reports when the local server cannot be reached.
        } finally {
            closeServer();
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(3000);
            if (!client.getInetAddress().isLoopbackAddress()) {
                writeResponse(client.getOutputStream(), 403, error("Loopback requests only."));
                return;
            }
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            String requestLine = readLine(in, 4096);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                writeResponse(out, 400, error("Malformed HTTP request."));
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];
            Map<String, String> headers = new HashMap<>();
            int totalHeaderBytes = 0;
            String line;
            while ((line = readLine(in, 8192)) != null && !line.isEmpty()) {
                totalHeaderBytes += line.length();
                if (totalHeaderBytes > 8192) {
                    writeResponse(out, 431, error("Headers too large."));
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            String auth = headers.getOrDefault("authorization", "");
            String expected = "Bearer " + pairingToken();
            if (!constantTimeEquals(expected, auth)) {
                writeResponse(out, 401, error("Invalid pairing token."));
                return;
            }

            JSONObject result;
            int status = 200;
            if ("GET".equals(method) && "/health".equals(path)) {
                result = new JSONObject().put("ok", true)
                        .put("service", "opera-android-mcp-agent")
                        .put("accessibility_enabled", isAvailable())
                        .put("bind_address", "127.0.0.1")
                        .put("port", PORT);
            } else if ("GET".equals(method) && "/status".equals(path)) {
                result = currentStatus();
            } else if ("POST".equals(method) && "/command".equals(path)) {
                int length;
                try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
                catch (NumberFormatException e) { length = -1; }
                if (length < 2 || length > 16384) {
                    writeResponse(out, 413, error("Command body must be 2..16384 bytes."));
                    return;
                }
                byte[] body = readExactly(in, length);
                if (body == null) {
                    writeResponse(out, 400, error("Incomplete request body."));
                    return;
                }
                try {
                    JSONObject command = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    result = executeCommand(command);
                } catch (Exception e) {
                    result = error("Invalid JSON command.");
                    status = 400;
                }
            } else {
                result = error("Unknown endpoint. Use GET /health, GET /status, or POST /command.");
                status = 404;
            }
            writeResponse(out, status, result);
        } catch (Exception ignored) {
            // Do not log command payloads, page text, or pairing token.
        }
    }

    private JSONObject currentStatus() {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String packageName = root != null && root.getPackageName() != null
                ? root.getPackageName().toString() : lastPackageName;
        try {
            out.put("ok", true);
            out.put("accessibility_enabled", isAvailable());
            out.put("active_package", packageName);
            out.put("browser_active", isSupportedBrowserPackage(packageName));
            out.put("browser_name", browserName(packageName));
            out.put("text", isSupportedBrowserPackage(packageName) && root != null ? visibleText(root, 7000) : "");
            out.put("note", isSupportedBrowserPackage(packageName)
                    ? "Visible accessibility text only; hidden DOM data is not available on Android."
                    : "Open Chrome or Opera and keep it in the foreground before requesting page actions.");
        } catch (Exception e) {
            return error("Could not read the active window.");
        }
        return out;
    }

    private JSONObject executeCommand(JSONObject cmd) {
        String action = cmd.optString("action", "").trim().toLowerCase(Locale.ROOT);
        if ("status".equals(action) || "get_page_text".equals(action)) return currentStatus();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String packageName = root != null && root.getPackageName() != null
                ? root.getPackageName().toString() : lastPackageName;
        if (!isSupportedBrowserPackage(packageName) || root == null) {
            return error("No supported browser is active. Open Chrome or Opera and retry.");
        }

        try {
            if ("click_text".equals(action)) {
                String target = cmd.optString("text", "").trim();
                if (target.isEmpty() || target.length() > 200) return error("Text must be 1..200 characters.");
                if (isSensitiveLabel(target)) {
                    return new JSONObject().put("ok", false).put("requires_human_action", true)
                            .put("message", "Potentially consequential control blocked. Inspect the page and tap it yourself.");
                }
                AccessibilityNodeInfo node = findMatchingClickable(root, target, 0);
                if (node == null) return error("No visible clickable element matched that exact label.");
                boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return new JSONObject().put("ok", clicked).put("action", action)
                        .put("message", clicked ? "Click dispatched." : "The element refused the click.");
            }
            if ("type_text".equals(action)) {
                String text = cmd.optString("text", "");
                if (text.length() > 2000) return error("Text too long; maximum is 2000 characters.");
                AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (node == null || !node.isEditable()) return error("No focused editable field was found.");
                if (node.isPassword()) return error("Password fields are intentionally blocked; enter credentials yourself.");
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                return new JSONObject().put("ok", changed).put("action", action)
                        .put("message", changed ? "Text entered; form was not submitted." : "The field rejected the text.");
            }
            if ("scroll".equals(action)) {
                String direction = cmd.optString("direction", "forward").toLowerCase(Locale.ROOT);
                int scrollAction;
                if ("forward".equals(direction) || "down".equals(direction)) {
                    scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                } else if ("backward".equals(direction) || "up".equals(direction)) {
                    scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                } else {
                    return error("Direction must be forward/down or backward/up.");
                }
                AccessibilityNodeInfo node = findScrollable(root, 0);
                if (node == null) return error("No scrollable element found.");
                boolean scrolled = node.performAction(scrollAction);
                return new JSONObject().put("ok", scrolled).put("action", action);
            }
            if ("go_back".equals(action)) {
                boolean backed = performGlobalAction(GLOBAL_ACTION_BACK);
                return new JSONObject().put("ok", backed).put("action", action);
            }
            if ("open_url".equals(action)) {
                String rawUrl = cmd.optString("url", "").trim();
                Uri uri = Uri.parse(rawUrl);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
                    return error("Only valid http:// or https:// URLs are allowed.");
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return new JSONObject().put("ok", true).put("action", action)
                        .put("message", "URL handed to Android's selected browser.");
            }
            return error("Unsupported action. Allowed: status, get_page_text, click_text, type_text, scroll, go_back, open_url.");
        } catch (Exception e) {
            return error("Action failed; sensitive page details were not logged.");
        }
    }

    private static boolean isSupportedBrowserPackage(String pkg) {
        if (pkg == null) return false;
        String value = pkg.toLowerCase(Locale.ROOT);
        return value.equals("com.android.chrome") || value.equals("com.chrome.beta")
                || value.equals("com.chrome.dev") || value.contains("opera");
    }

    private static String browserName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "unknown";
        if (pkg.equals("com.android.chrome") || pkg.equals("com.chrome.beta") || pkg.equals("com.chrome.dev")) return "Chrome";
        if (pkg.toLowerCase(Locale.ROOT).contains("opera")) return "Opera";
        return "unknown";
    }

    private static boolean isSensitiveLabel(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        String[] terms = {
            "pay", "payment", "purchase", "buy now", "send money", "transfer", "withdraw",
            "delete", "remove account", "submit", "confirm", "authorize", "approve",
            "log in", "login", "sign in", "sign-in", "install", "uninstall", "place order",
            "payer", "paiement", "acheter", "envoyer", "transférer", "retirer",
            "supprimer", "confirmer", "autoriser", "valider", "connexion", "mandefa vola"
        };
        for (String term : terms) if (s.contains(term)) return true;
        return false;
    }

    private static AccessibilityNodeInfo findMatchingClickable(AccessibilityNodeInfo node, String target, int depth) {
        if (node == null || depth > 18 || !node.isVisibleToUser()) return null;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        if (target.equalsIgnoreCase(text) || target.equalsIgnoreCase(desc)) {
            AccessibilityNodeInfo clickTarget = node;
            for (int i = 0; i < 5 && clickTarget != null && !clickTarget.isClickable(); i++) {
                clickTarget = clickTarget.getParent();
            }
            if (clickTarget != null && clickTarget.isClickable()) return clickTarget;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findMatchingClickable(node.getChild(i), target, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18 || !node.isVisibleToUser()) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String visibleText(AccessibilityNodeInfo node, int maxChars) {
        StringBuilder out = new StringBuilder();
        appendText(node, out, 0, maxChars);
        return out.toString();
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder out, int depth, int maxChars) {
        if (node == null || depth > 18 || out.length() >= maxChars) return;
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            appendLine(out, text, maxChars);
            if (desc != null && (text == null || !desc.toString().equals(text.toString()))) {
                appendLine(out, desc, maxChars);
            }
        }
        for (int i = 0; i < node.getChildCount() && out.length() < maxChars; i++) {
            appendText(node.getChild(i), out, depth + 1, maxChars);
        }
    }

    private static void appendLine(StringBuilder out, CharSequence value, int maxChars) {
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty()) return;
        if (out.length() + text.length() + 1 > maxChars) {
            int remaining = maxChars - out.length();
            if (remaining > 1) out.append(text, 0, Math.min(text.length(), remaining - 1));
            return;
        }
        out.append(text).append('\n');
    }

    private static String readLine(InputStream in, int maxLength) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        while (buffer.size() <= maxLength) {
            int next = in.read();
            if (next == -1) return buffer.size() == 0 ? null : buffer.toString("UTF-8");
            if (previous == '\r' && next == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            buffer.write(next);
            previous = next;
        }
        throw new IllegalArgumentException("HTTP line too long.");
    }

    private static byte[] readExactly(InputStream in, int length) throws Exception {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(out, offset, length - offset);
            if (count < 0) return null;
            offset += count;
        }
        return out;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Exception ignored) { }
        return out;
    }

    private static void writeResponse(OutputStream out, int code, JSONObject body) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" :
                code == 403 ? "Forbidden" : code == 404 ? "Not Found" :
                code == 413 ? "Payload Too Large" : code == 431 ? "Request Header Fields Too Large" :
                code == 400 ? "Bad Request" : "Error";
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private void closeServer() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = null;
    }
}

[executed on device: localhost (3da613ef-360b-4f88-b84a-ca0a3fb4bdb6)]