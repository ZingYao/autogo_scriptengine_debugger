const assert = require("assert");
const http = require("http");
const { requestJson } = require("../autogo-core");

async function main() {
  const server = http.createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    if (request.url === "/api/v1/logs?cursor=7") {
      response.end(JSON.stringify({ cursor: 9, entries: ["ok"] }));
      return;
    }
    response.statusCode = 409;
    response.end(JSON.stringify({ error: { code: "ENGINE_STOPPED", message: "引擎未启动", detail: "state=stopped" } }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    const base = `http://127.0.0.1:${address.port}/api`;
    assert.deepStrictEqual(await requestJson(base, "GET", "/v1/logs?cursor=7"), { cursor: 9, entries: ["ok"] });
    await assert.rejects(requestJson(base, "POST", "/v1/engine/start", {}), /ENGINE_STOPPED.*state=stopped/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
  console.log("VSCode remote JSON transport tests passed");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
