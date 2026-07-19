const fs = require("fs");
const path = require("path");

const BUNDLED_TARGETS = new Map([
  ["darwin/amd64", ["darwin-amd64", "gluals"]],
  ["darwin/arm64", ["darwin-arm64", "gluals"]],
  ["linux/amd64", ["linux-amd64", "gluals"]],
  ["linux/arm64", ["linux-arm64", "gluals"]],
  ["win32/amd64", ["windows-amd64", "gluals.exe"]],
  ["win32/arm64", ["windows-arm64", "gluals.exe"]],
]);

const BUNDLED_GLUAC_TARGETS = new Map([
  ["darwin/amd64", ["darwin-amd64", "gluac"]],
  ["darwin/arm64", ["darwin-arm64", "gluac"]],
  ["win32/amd64", ["windows-amd64", "gluac.exe"]],
  ["win32/arm64", ["windows-arm64", "gluac.exe"]],
]);

function resolveBundledExecutable(extensionPath, configuredPath, targets, toolName, platform, arch) {
  const configured = String(configuredPath || "").trim();
  if (configured) {
    const resolved = path.resolve(configured);
    if (!fs.existsSync(resolved)) throw new Error(`configured ${toolName} executable does not exist: ${resolved}`);
    return { path: resolved, bundled: false };
  }
  const normalizedArch = arch === "x64" ? "amd64" : arch;
  const target = targets.get(`${platform}/${normalizedArch}`);
  if (!target) throw new Error(`${toolName} is not bundled for ${platform}/${arch}`);
  const resolved = path.join(extensionPath, "bin", target[0], target[1]);
  if (!fs.existsSync(resolved)) throw new Error(`bundled ${toolName} executable is missing: ${resolved}`);
  return { path: resolved, bundled: true };
}

function resolveGlualsExecutable(extensionPath, configuredPath, platform = process.platform, arch = process.arch) {
  return resolveBundledExecutable(extensionPath, configuredPath, BUNDLED_TARGETS, "gluals", platform, arch);
}

function resolveGluacExecutable(extensionPath, configuredPath, platform = process.platform, arch = process.arch) {
  return resolveBundledExecutable(extensionPath, configuredPath, BUNDLED_GLUAC_TARGETS, "gluac", platform, arch);
}

module.exports = { BUNDLED_TARGETS, BUNDLED_GLUAC_TARGETS, resolveGlualsExecutable, resolveGluacExecutable };
