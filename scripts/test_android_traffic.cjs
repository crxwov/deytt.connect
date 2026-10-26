const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require("C:/Users/hackov/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright");

const root = path.resolve(__dirname, "../app/src/main/assets/route-map");
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".woff2": "font/woff2" };
const desktopMap = process.env.DEYTT_DESKTOP_ATLAS_REFERENCE || path.join(process.env.ProgramFiles || "C:/Program Files", "DEYTT/Connect/Assets/route-map/network-atlas.js");
if (process.env.DEYTT_DESKTOP_ATLAS_REFERENCE) assert.ok(fs.existsSync(desktopMap), `desktop reference not found: ${desktopMap}`);
if (fs.existsSync(desktopMap)) {
  const desktopSource = fs.readFileSync(desktopMap, "utf8");
  const desktopStart = desktopSource.indexOf("trafficStreams(colors)");
  const desktopStreams = desktopSource.slice(desktopStart, desktopSource.indexOf("projectLanePoint(", desktopStart));
  for (const [id, direction, lane, duration, phase] of [["upload", "forward", ".07", 3400, 0], ["download", "reverse", ".17", 3000, ".34"], ["control", "forward", ".27", 3800, ".68"]]) {
    const decimal = value => String(value).replace(".", "\\.");
    assert.match(desktopStreams, new RegExp(`id: "${id}".*direction: "${direction}".*lane: ${decimal(lane)}, duration: ${duration}, phase: ${decimal(phase)}`), `installed desktop ${id} direction and lane`);
  }
  for (const color of ["#FF7798", "#FFE2EA", "#FFCE55", "#FFF4CB", "#AF8CFF", "#EFE5FF", "#FF5278", "#FFCDDD", "#F6AD11", "#FFE9A6", "#824BFF", "#D9C5FF"])
    assert.ok(desktopSource.includes(color), `desktop palette contains ${color}`);
}
const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split("?")[0]);
  const file = url === "/world-land.json" ? path.resolve(root, "../world-land.json") : path.resolve(root, "." + (url === "/" ? "/index.html" : url));
  if (!file.startsWith(root + path.sep) && file !== path.resolve(root, "../world-land.json")) { res.writeHead(403).end(); return; }
  fs.readFile(file, (error, data) => { if (error) { res.writeHead(404).end(); return; } res.setHeader("Content-Type", types[path.extname(file)] || "application/octet-stream"); res.end(data); });
});

(async () => {
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 432, height: 650 }, deviceScaleFactor: 2 });
    page.on("pageerror", error => { throw error; });
    await page.addInitScript(() => {
      window.qaTrafficTaps = [];
      window.DeyttAtlasBridge = { onTrafficTap: flow => window.qaTrafficTaps.push(flow) };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => window.deyttMapAtlas);
    await page.waitForFunction(() => document.querySelector(".network-atlas")?.dataset.atlasStatus === "ready");
    const screenshot = process.env.DEYTT_TRAFFIC_SCREENSHOT;
    if (screenshot) {
      await page.evaluate(() => {
        document.documentElement.dataset.theme = "dark";
        window.deyttSetMapViewportAnchor(8, 20, 416, 500);
        window.deyttSetMapUserLocation(54.7351, 55.9587, { city: "ufa", country: "ru" });
        window.deyttSetMapConnected(false);
        window.deyttSetMapReducedMotion(false);
        window.deyttSetMapRoute("ru-de");
        window.deyttSetMapTrafficActive(true);
      });
      await page.waitForTimeout(650);
      fs.mkdirSync(path.dirname(screenshot), { recursive: true });
      await page.screenshot({ path: screenshot });
    }
    const result = await page.evaluate(() => {
      const atlas = window.deyttMapAtlas;
      window.deyttSetMapViewportAnchor(8, 20, 416, 500);
      atlas.render(performance.now());
      const expected = {
        dark: [
          ["upload", "#FF7798", "#FFE2EA", .07, 3400, 0, false],
          ["download", "#FFCE55", "#FFF4CB", .17, 3000, .34, true],
          ["control", "#AF8CFF", "#EFE5FF", .27, 3800, .68, false]
        ],
        light: [
          ["upload", "#FF5278", "#FFCDDD", .07, 3400, 0, false],
          ["download", "#F6AD11", "#FFE9A6", .17, 3000, .34, true],
          ["control", "#824BFF", "#D9C5FF", .27, 3800, .68, false]
        ]
      };
      const streamTuples = () => atlas.trafficStreams().map(s => [s.id, s.color, s.bright, s.lane, s.duration, s.phase, Boolean(s.reverse)]);
      document.documentElement.dataset.theme = "dark";
      const dark = streamTuples();
      document.documentElement.dataset.theme = "light";
      const light = streamTuples();
      document.documentElement.dataset.theme = "dark";

      atlas.availableLocationKeys = new Set(["nl", "de", "fi", "ru", "it"]);
      atlas.activeRouteDefinition = () => ({ links: [["ru", "de"]] });
      atlas.activeRouteNodeKeys = () => ["ru", "de"];
      atlas.userLocation = { lat: 54.7351, lon: 55.9587 };
      atlas.projectedNodes = {};
      atlas.routeHitSegments = [];
      const projectedLinks = [];
      const projectLanePoint = atlas.projectLanePoint;
      atlas.projectLanePoint = function (from, to, amount, lane) {
        if (amount === 0) projectedLinks.push([lane, from, to]);
        return projectLanePoint.call(this, from, to, amount, lane);
      };
      atlas.trafficActive = false;
      let inactiveStrokes = 0;
      const nativeStroke = atlas.context.stroke;
      atlas.context.stroke = function (...args) { inactiveStrokes++; return nativeStroke.apply(this, args); };
      atlas.drawRoutes(atlas.colors(), performance.now() + 1000);
      const inactiveSegments = atlas.routeHitSegments.length;
      atlas.context.stroke = nativeStroke;
      atlas.projectLanePoint = projectLanePoint;
      const directedLinks = projectedLinks.filter(item => item[0] === .07 || item[0] === .17 || item[0] === .27);
      atlas.routeHitSegments = [];
      atlas.drawTrafficLines(atlas.routeLinks());
      const withOrigin = Object.fromEntries(["upload", "download", "control"].map(id => [id, atlas.routeHitSegments.filter(s => s.stream === id).length]));
      atlas.userLocation = null;
      atlas.routeHitSegments = [];
      atlas.drawTrafficLines(atlas.routeLinks());
      const withoutOrigin = Object.fromEntries(["upload", "download", "control"].map(id => [id, atlas.routeHitSegments.filter(s => s.stream === id).length]));

      atlas.activeRouteDefinition = () => ({ links: [] });
      atlas.activeRouteNodeKeys = () => ["de"];
      atlas.routeHitSegments = [];
      let dots = 0;
      const drawDot = atlas.drawTrafficDot;
      atlas.drawTrafficDot = function (...args) { dots++; return drawDot.apply(this, args); };
      atlas.drawTrafficLines(atlas.routeLinks());
      atlas.drawTraffic(atlas.routeLinks(), performance.now() + 1000);
      const noOriginFallback = { hitSegments: atlas.routeHitSegments.length, dots };

      atlas.activeRouteDefinition = () => ({ links: [["ru", "de"]] });
      atlas.userLocation = { lat: 54.7351, lon: 55.9587 };
      atlas.selectTrafficFlow("download");
      atlas.routeHitSegments = [];
      atlas.drawTrafficLines(atlas.routeLinks());
      const selected = [...new Set(atlas.routeHitSegments.map(s => s.stream))];
      atlas.selectTrafficFlow(null);
      atlas.routeHitSegments = [];
      atlas.drawTrafficLines(atlas.routeLinks());
      const cleared = [...new Set(atlas.routeHitSegments.map(s => s.stream))];

      Object.defineProperty(atlas.reducedMotion, "matches", { configurable: true, value: true });
      let trails = 0, reducedDots = 0;
      const drawTrail = atlas.drawTrafficTrail;
      atlas.drawTrafficTrail = function (...args) { trails++; return drawTrail.apply(this, args); };
      atlas.drawTrafficDot = function (...args) { reducedDots++; return drawDot.apply(this, args); };
      atlas.routeHitSegments = [];
      atlas.drawTraffic(atlas.routeLinks(), performance.now() + 1000);

      Object.defineProperty(atlas.reducedMotion, "matches", { configurable: true, value: false });
      atlas.selectTrafficFlow(null);
      atlas.routeHitSegments = [];
      atlas.drawTrafficLines(atlas.routeLinks());
      const canvas = atlas.canvas;
      const candidates = atlas.routeHitSegments.filter(s => s.stream === "control").map(s => ({ x: (s.a.x + s.b.x) / 2, y: (s.a.y + s.b.y) / 2 }));
      const tap = candidates.find(p => p.x > 20 && p.x < innerWidth - 20 && p.y > 20 && p.y < innerHeight - 20);
      const box = canvas.getBoundingClientRect(), clientTap = { x: box.left + tap.x, y: box.top + tap.y };
      canvas.dispatchEvent(new MouseEvent("click", { bubbles: true, clientX: clientTap.x, clientY: clientTap.y }));
      const tapped = [...window.qaTrafficTaps];
      window.qaTrafficTaps.length = 0;
      canvas.setPointerCapture = () => {};
      canvas.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, pointerId: 42, pointerType: "mouse", clientX: clientTap.x, clientY: clientTap.y }));
      canvas.dispatchEvent(new PointerEvent("pointermove", { bubbles: true, pointerId: 42, pointerType: "mouse", clientX: clientTap.x + 24, clientY: clientTap.y }));
      document.dispatchEvent(new PointerEvent("pointerup", { bubbles: true, pointerId: 42, pointerType: "mouse", clientX: clientTap.x + 24, clientY: clientTap.y }));
      canvas.dispatchEvent(new MouseEvent("click", { bubbles: true, clientX: clientTap.x, clientY: clientTap.y }));
      const panSuppressed = window.qaTrafficTaps.length === 0;
      return { expected, dark, light, inactiveSegments, inactiveStrokes, directedLinks, withOrigin, withoutOrigin, noOriginFallback, selected, cleared, reduced: { trails, dots: reducedDots }, tapped, panSuppressed };
    });

    assert.deepEqual(result.dark, result.expected.dark, "dark desktop traffic palette/direction/lane/timing parity");
    assert.deepEqual(result.light, result.expected.light, "light desktop traffic palette/direction/lane/timing parity");
    assert.equal(result.inactiveSegments, 480, "all three 80-segment lanes remain visible when packet animation is inactive");
    assert.equal(result.inactiveStrokes, 12, "only two strokes per lane are drawn, with no legacy base route arc");
    assert.deepEqual(result.directedLinks, [[.07, "user", "ru"], [.07, "ru", "de"], [.17, "de", "ru"], [.17, "ru", "user"], [.27, "user", "ru"], [.27, "ru", "de"]], "all three lanes keep their desktop direction over each route link");
    assert.deepEqual(result.withOrigin, { upload: 160, download: 160, control: 160 }, "each of two consented route links has a separate 80-segment lane");
    assert.deepEqual(result.withoutOrigin, { upload: 80, download: 80, control: 80 }, "without consent, only confirmed server-to-server route is drawn");
    assert.deepEqual(result.noOriginFallback, { hitSegments: 0, dots: 12 }, "no route/origin renders endpoint orbit particles without a fabricated line");
    assert.deepEqual(result.selected, ["download"], "native dialog selection highlights only selected flow");
    assert.deepEqual(result.cleared, ["upload", "download", "control"], "dismissal restores all flows");
    assert.deepEqual(result.reduced, { trails: 0, dots: 3 }, "reduced motion uses one static dot per flow and no trails");
    assert.deepEqual(result.tapped, ["control"], "tap selects the stream hit and calls native bridge with its id");
    assert.equal(result.panSuppressed, true, "drag-suppressed click does not call native flow bridge");
    console.log("Android traffic renderer parity and regression checks passed (installed desktop reference).");
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
