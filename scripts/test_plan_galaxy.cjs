const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/galaxy/galaxy.html"), "utf8");
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
const frames = new Map();
let frameId = 0;
let draws = 0;
let visibilityChanged;
const gl = new Proxy({
  ACTIVE_UNIFORMS: "count",
  createShader: () => ({}),
  getShaderParameter: () => true,
  getProgramParameter: (_, name) => name === "count" ? 0 : true,
  drawArrays: () => { draws += 1; },
}, { get: (target, key) => target[key] ?? (() => {}) });
const canvas = { getContext: () => gl };
const document = {
  hidden: false,
  getElementById: () => canvas,
  addEventListener: (_, callback) => { visibilityChanged = callback; },
};
const window = { innerWidth: 380, innerHeight: 220, addEventListener() {} };
vm.runInNewContext(script, {
  document, window,
  requestAnimationFrame: (callback) => { frames.set(++frameId, callback); return frameId; },
  cancelAnimationFrame: (id) => frames.delete(id),
});

assert.equal(draws, 1, "a still galaxy appears before animation");
assert.equal(frames.size, 0, "preloaded profiles do not animate");
window.deyttSetGalaxyMotion(true);
assert.equal(frames.size, 1, "opening the profile starts one frame loop");
window.deyttSetGalaxyMotion(false);
assert.equal(frames.size, 0, "the native reduced-motion signal cancels the frame loop");
window.deyttSetGalaxyMotion(true);
document.hidden = true;
visibilityChanged();
assert.equal(frames.size, 0, "backgrounding cancels the loop");
document.hidden = false;
visibilityChanged();
window.deyttSetGalaxyMotion(false);
assert.equal(frames.size, 0, "native profile exit cancels the loop");
console.log("Plan galaxy still frame and motion lifecycle passed");
