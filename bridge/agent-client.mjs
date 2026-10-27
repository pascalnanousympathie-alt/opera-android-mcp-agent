const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "::1"]);

export function createAgentClient({
  baseUrl = "http://127.0.0.1:18765",
  token,
  fetchImpl = fetch,
  timeoutMs = 8000,
} = {}) {
  if (typeof token !== "string" || token.length < 32) {
    throw new Error("OPERA_AGENT_TOKEN must be configured locally; never log the token.");
  }
  const base = new URL(baseUrl);
  const host = base.hostname.replace(/^\[|\]$/g, "");
  const loopback = LOOPBACK_HOSTS.has(host);
  if (base.protocol !== "https:" && !(base.protocol === "http:" && loopback)) {
    throw new Error("The agent URL must use HTTPS; plain HTTP is allowed only on loopback.");
  }
  if (base.username || base.password || base.search || base.hash) {
    throw new Error("Agent base URL must not contain credentials, query strings, or fragments.");
  }

  async function request(path, method = "GET", payload = undefined) {
    const url = new URL(path, base.href.endsWith("/") ? base : base.href + "/");
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(url, {
        method,
        headers: {
          Authorization: "Bearer " + token,
          Accept: "application/json",
          ...(payload === undefined ? {} : { "Content-Type": "application/json" }),
        },
        body: payload === undefined ? undefined : JSON.stringify(payload),
        signal: controller.signal,
        redirect: "error",
      });
      const text = await response.text();
      let data;
      try {
        data = JSON.parse(text);
      } catch {
        throw new Error("Agent returned a non-JSON response (HTTP " + response.status + ").");
      }
      if (!response.ok) {
        throw new Error("Agent request failed (HTTP " + response.status + "): " +
          String(data.error || data.message || "request rejected"));
      }
      return data;
    } finally {
      clearTimeout(timer);
    }
  }

  return Object.freeze({
    health: () => request("/health"),
    status: () => request("/status"),
    command: (payload) => request("/command", "POST", payload),
  });
}
