# Opera Android MCP Agent — prototype 0.1.0

This is a separate Android Accessibility companion for the existing Opera One app. It does **not** modify Opera's APK or claim access to the browser's hidden DOM.

## Why this exists

Opera's official opera-devtools-mcp expects a Chromium DevTools Protocol endpoint (such as http://127.0.0.1:9222). A standard Opera Android installation does not automatically expose that endpoint to Termux. The 0.1 prototype therefore uses Android Accessibility to read visible labels and perform a limited set of UI actions.

## Features

- Android 8.0+ (API 26+) companion app.
- Loopback-only HTTP API at 127.0.0.1:18765.
- Random local bearer token, generated on-device and never logged.
- MCP tools for checking health, reading visible text, clicking an exact label, typing into a focused non-password field, scrolling, Android Back, and opening an HTTP(S) URL.
- Blocks password-field typing and labels that appear to involve payment, sending money, deletion, login, authorization, installation, or approval.
- No form is auto-submitted. Android Accessibility permission requires the user to enable it manually.

## Build on Termux

Requirements: JDK 17+, Android SDK Platform 35 and Build Tools 35.0.0. The project uses its own Gradle wrapper.

    cd ~/opera-android-mcp-agent
    ./gradlew testDebugUnitTest assembleDebug

The APK is written to:

    app/build/outputs/apk/debug/app-debug.apk

To put a copy in the phone's Downloads directory (after Termux storage access has been granted):

    cp app/build/outputs/apk/debug/app-debug.apk /storage/emulated/0/Download/OperaAndroidMCPAgent-debug.apk

Install it from Downloads, open it, press **Enable Accessibility**, and enable **Opera Android MCP Agent** in Android Accessibility settings. Then return to the app and check the local bridge. No accessibility permission is enabled automatically.

## Local MCP bridge

Run the dependency-free JavaScript bridge on the same Android/Termux environment:

    cd ~/opera-android-mcp-agent/bridge
    OPERA_AGENT_TOKEN='paste-the-token-locally' node server.mjs

Use the app's **Copy pairing token** button and paste it only into Termux or a local MCP client configuration. Do not send the token in ChatGPT, paste it into an issue, or include it in screenshots. OPERA_AGENT_URL defaults to http://127.0.0.1:18765; non-loopback URLs must use HTTPS and this prototype does not provide any public tunnel.

A local MCP client can start bridge/server.mjs with OPERA_AGENT_TOKEN in its environment. Because the MCP transport here uses local stdio, it is **not yet callable directly from ChatGPT's cloud**. Direct ChatGPT integration needs a separately authenticated, publicly reachable MCP endpoint and a secure outbound connection from the phone; that is a later deployment step and is intentionally not enabled by this prototype.

## API

All requests require Authorization: Bearer <token>.

- GET /health — agent reachability.
- GET /status — active package and visible accessibility text when Opera is foregrounded.
- POST /command — JSON action.

Example body (do not send real credentials):

    {"action":"get_page_text"}

Other actions: click_text, type_text, scroll, go_back, open_url.

## Safety and limitations

- Server binds only to loopback, never 0.0.0.0.
- Pairing token is required; app logs neither token nor page contents.
- Password inputs and some obviously consequential buttons are blocked. This is a conservative heuristic, not a complete understanding of every website; keep oversight and do not let the agent approve payments or security prompts.
- Accessibility text is not a complete page DOM and may omit content or labels. Some buttons in Opera may not expose useful accessibility nodes.
- Android 8.1 cannot use the newer Accessibility screenshot API. Screenshot, network-panel, console, performance traces, and hidden DOM inspection are not available through this path.
- Remote access is not configured. Do not expose this HTTP service on the internet.
