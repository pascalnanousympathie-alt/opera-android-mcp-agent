import test from "node:test";
import assert from "node:assert/strict";
import { createAgentClient } from "./agent-client.mjs";

const token = "x".repeat(43);

test("rejects missing or short pairing token", () => {
  assert.throws(() => createAgentClient({ token: "" }), /OPERA_AGENT_TOKEN/);
  assert.throws(() => createAgentClient({ token: "short" }), /OPERA_AGENT_TOKEN/);
});

test("rejects cleartext HTTP to a remote host", () => {
  assert.throws(() => createAgentClient({ token, baseUrl: "http://example.com:18765" }), /HTTPS/);
});

test("permits HTTP only on loopback for same-device use", () => {
  assert.doesNotThrow(() => createAgentClient({ token, baseUrl: "http://127.0.0.1:18765" }));
  assert.doesNotThrow(() => createAgentClient({ token, baseUrl: "http://localhost:18765" }));
});

test("sends bearer auth and parses JSON health response", async () => {
  let captured;
  const client = createAgentClient({
    token,
    fetchImpl: async (url, options) => {
      captured = { url: String(url), options };
      return new Response(JSON.stringify({ ok: true, service: "test-agent" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    },
  });
  const result = await client.health();
  assert.equal(result.ok, true);
  assert.equal(captured.url, "http://127.0.0.1:18765/health");
  assert.equal(captured.options.headers.Authorization, "Bearer " + token);
  assert.equal(captured.options.redirect, "error");
});

test("does not follow HTTP redirects that could leak the token", async () => {
  const client = createAgentClient({
    token,
    fetchImpl: async (_url, options) => {
      assert.equal(options.redirect, "error");
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    },
  });
  assert.equal((await client.health()).ok, true);
});
