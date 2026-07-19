const assert = require("assert");
const path = require("path");
const { normalizeHoverExpression, toLocalPath, toRemotePath, transformClientMessage, transformServerMessage } = require("../dap-path-proxy");

const root = path.resolve("/workspace/project");
const remote = "/data/local/tmp/.autogo/remote/releases/manifest-1";
assert.strictEqual(toRemotePath(root, remote, path.join(root, "scripts", "main.lua")), `${remote}/scripts/main.lua`);
assert.strictEqual(toRemotePath(root, remote, "/outside/main.lua"), "/outside/main.lua");
assert.strictEqual(toLocalPath(root, remote, `${remote}/scripts/main.lua`), path.join(root, "scripts", "main.lua"));

const request = transformClientMessage({ type: "request", command: "setBreakpoints", arguments: { source: { path: path.join(root, "main.lua") } } }, root, remote);
assert.strictEqual(request.arguments.source.path, `${remote}/main.lua`);
assert.strictEqual(normalizeHoverExpression("name"), "name");
assert.strictEqual(normalizeHoverExpression("..name"), "name");
assert.strictEqual(normalizeHoverExpression("'hello,'..name"), "name");
assert.strictEqual(normalizeHoverExpression("tools.current.name"), "tools.current.name");
const hoverRequest = transformClientMessage({ type: "request", command: "evaluate", arguments: { context: "hover", expression: "'hello,'..name" } }, root, remote);
assert.strictEqual(hoverRequest.arguments.expression, "name");
const watchRequest = transformClientMessage({ type: "request", command: "evaluate", arguments: { context: "watch", expression: "'hello,'..name" } }, root, remote);
assert.strictEqual(watchRequest.arguments.expression, "'hello,'..name");
const response = transformServerMessage({ type: "response", command: "stackTrace", body: { stackFrames: [{ source: { path: `${remote}/main.lua` } }] } }, root, remote);
assert.strictEqual(response.body.stackFrames[0].source.path, path.join(root, "main.lua"));
console.log("VSCode DAP path proxy tests passed");
