const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(
  path.join(__dirname, "../app/src/main/assets/route-map/atlas-init.js"),
  "utf8",
);

async function runCase(setOverviewBeforeAtlasExists) {
  let resolveAtlas;
  const root = { classList: { toggle() {} }, querySelector() { return null; } };
  const window = {
    location: { search: "" },
    DeyttAtlas: {
      create() {
        return new Promise((resolve) => { resolveAtlas = resolve; });
      },
    },
  };
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
  assert.equal(atlas.targetZoom, 1.1);
}

(async () => {
  await runCase(true);
  await runCase(false);
  process.stdout.write("atlas overview initialization race: 2 cases passed\n");
})().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
