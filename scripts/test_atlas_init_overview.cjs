const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(
  path.join(__dirname, "../app/src/main/assets/route-map/atlas-init.js"),
  "utf8",
);

async function runCase(setOverviewBeforeAtlasExists, native) {
  let resolveAtlas;
  let createOptions;
  const root = { classList: { toggle() {} }, querySelector() { return null; } };
  const window = {
    location: { search: "" },
    DeyttAtlas: {
      create(root, options) {
        createOptions = options;
        return new Promise((resolve) => { resolveAtlas = resolve; });
      },
    },
  };
  if (native) window.DeyttAtlasBridge = {};
  const document = {
    documentElement: { dataset: {} },
    querySelector() { return root; },
  };

  vm.runInNewContext(source, { window, document, URLSearchParams });
  if (setOverviewBeforeAtlasExists) window.deyttSetMapOverviewMode(true);

  const atlas = {
    overviewMode: false,
    targetZoom: 2.85,
    reducedMotion: { matches: false },
    clampZoom(value) { return Math.max(1, Math.min(32, value)); },
    start() {},
    setLanguage() {},
    setTrafficActive() {},
    setConnected() {},
    setRoute() {},
    setAutoExitCountry() {},
    setActiveAutoRoute() {},
    focusRoute() {},
  };

  resolveAtlas(atlas);
  await new Promise(setImmediate);
  if (!setOverviewBeforeAtlasExists) window.deyttSetMapOverviewMode(true);

  assert.equal(window.deyttMapOverviewMode, true);
  assert.equal(atlas.overviewMode, true);
  assert.equal(atlas.targetZoom, native ? 1.28 : 1.1);
  assert.equal(createOptions.initialZoom, native ? 1.28 : 1.1);
  atlas.targetZoom = 4;
  window.deyttResetMapView();
  assert.equal(atlas.targetZoom, native ? 1.28 : 1.1);
}

(async () => {
  for (const native of [false, true]) {
    await runCase(true, native);
    await runCase(false, native);
  }
  process.stdout.write("atlas native/web initialization and reset: 4 cases passed\n");
})().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
