import { createInterface } from "node:readline";
import { createAgentClient } from "./agent-client.mjs";

const token = process.env.OPERA_AGENT_TOKEN;
const baseUrl = process.env.OPERA_AGENT_URL || "http://127.0.0.1:18765";
let client;
try {
  client = createAgentClient({ baseUrl, token });
} catch (error) {
  console.error(error.message);
  process.exit(2);
}

const TOOL_LIST = [
  {
    name: "opera_agent_health",
    description: "Check whether the local Android accessibility agent is reachable. Does not change the browser.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "opera_get_active_page",
    description: "Read visible accessibility text from the foreground Opera app; not hidden DOM or screenshot.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "opera_click_text",
    description: "Click an exact visible label in Opera. Consequential labels are blocked for manual action.",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", minLength: 1, maxLength: 200 } },
      required: ["text"],
      additionalProperties: false,
    },
  },
  {
    name: "opera_type_text",
    description: "Type into a focused non-password field. Does not submit the form.",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", maxLength: 2000 } },
      required: ["text"],
      additionalProperties: false,
    },
  },
  {
    name: "opera_scroll",
    description: "Scroll the foreground Opera page forward/down or backward/up.",
    inputSchema: {
      type: "object",
      properties: { direction: { type: "string", enum: ["forward", "backward", "down", "up"] } },
      additionalProperties: false,
    },
  },
  {
    name: "opera_go_back",
    description: "Use Android Back while Opera is in the foreground.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "opera_open_url",
    description: "Open a valid HTTP(S) URL using Android's selected browser. May switch apps; does not submit forms.",
    inputSchema: {
      type: "object",
      properties: { url: { type: "string", minLength: 8, maxLength: 2048 } },
      required: ["url"],
      additionalProperties: false,
    },
  },
];

function rpcResult(id, result) {
  send({ jsonrpc: "2.0", id, result });
}

function rpcError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function toolResult(data) {
  return {
    content: [{ type: "text", text: JSON.stringify(data, null, 2).slice(0, 12000) }],
    isError: data?.ok === false,
  };
}

function requireObject(args) {
  if (args === undefined || args === null) return {};
  if (typeof args !== "object" || Array.isArray(args)) throw new Error("Tool arguments must be an object.");
  return args;
}

async function callTool(name, rawArgs) {
  const args = requireObject(rawArgs);
  if (name === "opera_agent_health") return toolResult(await client.health());
  if (name === "opera_get_active_page") return toolResult(await client.status());
  if (name === "opera_click_text") {
    if (typeof args.text !== "string" || args.text.trim().length < 1 || args.text.length > 200) {
      throw new Error("text must be a string of 1..200 characters.");
    }
    return toolResult(await client.command({ action: "click_text", text: args.text }));
  }
  if (name === "opera_type_text") {
    if (typeof args.text !== "string" || args.text.length > 2000) {
      throw new Error("text must be a string of at most 2000 characters.");
    }
    return toolResult(await client.command({ action: "type_text", text: args.text }));
  }
  if (name === "opera_scroll") {
    const direction = args.direction === undefined ? "forward" : args.direction;
    if (!["forward", "backward", "down", "up"].includes(direction)) {
      throw new Error("direction must be forward, backward, down, or up.");
    }
    return toolResult(await client.command({ action: "scroll", direction }));
  }
  if (name === "opera_go_back") return toolResult(await client.command({ action: "go_back" }));
  if (name === "opera_open_url") {
    if (typeof args.url !== "string" || args.url.length > 2048) throw new Error("url is invalid.");
    let url;
    try { url = new URL(args.url); } catch { throw new Error("url is invalid."); }
    if (!["http:", "https:"].includes(url.protocol) || !url.hostname || url.username || url.password) {
      throw new Error("Only HTTP(S) URLs without embedded credentials are allowed.");
    }
    return toolResult(await client.command({ action: "open_url", url: url.href }));
  }
  throw new Error("Unknown tool: " + String(name));
}

async function handle(message) {
  if (!message || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
    if (message && Object.hasOwn(message, "id")) rpcError(message.id, -32600, "Invalid JSON-RPC request.");
    return;
  }
  const hasId = Object.hasOwn(message, "id");
  const id = message.id;

  if (message.method === "notifications/initialized" || message.method === "notifications/cancelled") return;
  if (!hasId && message.method.startsWith("notifications/")) return;

  try {
    if (message.method === "initialize") {
      const requested = message.params?.protocolVersion;
      const supported = new Set(["2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"]);
      const protocolVersion = supported.has(requested) ? requested : "2025-03-26";
      rpcResult(id, {
        protocolVersion,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "opera-android-accessibility-agent", version: "0.1.0" },
        instructions: "Use only with the user-authorized Android device. Accessibility exposes visible UI text, not the hidden DOM. Consequential controls require manual action.",
      });
      return;
    }
    if (message.method === "ping") {
      rpcResult(id, {});
      return;
    }
    if (message.method === "tools/list") {
      rpcResult(id, { tools: TOOL_LIST });
      return;
    }
    if (message.method === "tools/call") {
      const name = message.params?.name;
      try {
        const result = await callTool(name, message.params?.arguments);
        rpcResult(id, result);
      } catch (error) {
        rpcResult(id, {
          content: [{ type: "text", text: "Tool failed: " + error.message }],
          isError: true,
        });
      }
      return;
    }
    rpcError(id, -32601, "Method not found.");
  } catch {
    if (hasId) rpcError(id, -32603, "Internal MCP bridge error.");
  }
}

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity, terminal: false });
rl.on("line", (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    return;
  }
  void handle(message);
});
