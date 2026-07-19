const net = require("net");
const path = require("path");

function toRemotePath(localRoot, remoteRoot, sourcePath) {
  if (!sourcePath) return sourcePath;
  const root = path.resolve(localRoot);
  const source = path.resolve(sourcePath);
  const relative = path.relative(root, source);
  if (!relative || relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) return sourcePath;
  return `${String(remoteRoot).replace(/\/$/, "")}/${relative.split(path.sep).join("/")}`;
}

function toLocalPath(localRoot, remoteRoot, sourcePath) {
  if (!sourcePath) return sourcePath;
  const normalized = String(sourcePath).replace(/\\/g, "/");
  const prefix = String(remoteRoot).replace(/\\/g, "/").replace(/\/$/, "") + "/";
  const index = normalized.indexOf(prefix);
  if (index < 0) return sourcePath;
  const relative = normalized.slice(index + prefix.length);
  const target = path.resolve(localRoot, ...relative.split("/"));
  const root = path.resolve(localRoot);
  return target === root || target.startsWith(`${root}${path.sep}`) ? target : sourcePath;
}

function normalizeHoverExpression(expression) {
  const source = String(expression || "").trim();
  if (!source) return source;
  const identifierPath = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/;
  if (identifierPath.test(source)) return source;
  // VSCode 在连接运算符后悬停时可能把 `..name` 或整个拼接片段作为 evaluate 表达式。
  const trailingPath = source.match(/([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*$/)?.[1];
  return trailingPath || source;
}

function transformClientMessage(message, localRoot, remoteRoot) {
  if (message?.type === "request" && message.command === "setBreakpoints" && message.arguments?.source?.path) {
    message.arguments.source.path = toRemotePath(localRoot, remoteRoot, message.arguments.source.path);
  }
  if (message?.type === "request" && message.command === "evaluate" && message.arguments?.context === "hover") {
    // 仅收窄修正 Hover；Watch/调试控制台仍保留用户输入的完整表达式。
    message.arguments.expression = normalizeHoverExpression(message.arguments.expression);
  }
  return message;
}

function transformServerMessage(message, localRoot, remoteRoot) {
  const rewriteSource = (source) => {
    if (source?.path) source.path = toLocalPath(localRoot, remoteRoot, source.path);
  };
  if (message?.type === "response" && message.command === "stackTrace") {
    for (const frame of message.body?.stackFrames || []) rewriteSource(frame.source);
  }
  if (message?.type === "event" && message.event === "output") rewriteSource(message.body?.source);
  if (message?.type === "response" && message.command === "loadedSources") {
    for (const source of message.body?.sources || []) rewriteSource(source);
  }
  return message;
}

function dapTransformStream(destination, transform) {
  let buffer = Buffer.alloc(0);
  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const headerEnd = buffer.indexOf("\r\n\r\n");
      if (headerEnd < 0) return;
      const header = buffer.subarray(0, headerEnd).toString("ascii");
      const match = header.match(/(?:^|\r\n)Content-Length:\s*(\d+)/i);
      if (!match) throw new Error("DAP 消息缺少 Content-Length");
      const length = Number(match[1]);
      const bodyStart = headerEnd + 4;
      if (buffer.length < bodyStart + length) return;
      const raw = buffer.subarray(bodyStart, bodyStart + length);
      buffer = buffer.subarray(bodyStart + length);
      const message = transform(JSON.parse(raw.toString("utf8")));
      if (!message) continue;
      const encoded = Buffer.from(JSON.stringify(message));
      destination.write(`Content-Length: ${encoded.length}\r\n\r\n`);
      destination.write(encoded);
    }
  };
}

function startDapPathProxy(remoteHost, remotePort, localRoot, remoteRoot, options = {}) {
  return new Promise((resolve, reject) => {
    const server = net.createServer((client) => {
      const remote = net.createConnection({ host: remoteHost, port: remotePort });
      client.on("data", dapTransformStream(remote, (message) => {
        const transformed = transformClientMessage(message, localRoot, remoteRoot);
        options.onClientMessage?.(transformed);
        return transformed;
      }));
      remote.on("data", dapTransformStream(client, (message) => {
        const transformed = transformServerMessage(message, localRoot, remoteRoot);
        options.onServerMessage?.(transformed);
        if (options.suppressOutput && transformed?.type === "event" && transformed.event === "output") return null;
        return transformed;
      }));
      const close = () => { client.destroy(); remote.destroy(); };
      client.on("error", close); remote.on("error", close); client.on("close", close); remote.on("close", close);
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve({ host: "127.0.0.1", port: address.port, close: () => server.close() });
    });
  });
}

module.exports = { normalizeHoverExpression, startDapPathProxy, toLocalPath, toRemotePath, transformClientMessage, transformServerMessage };
