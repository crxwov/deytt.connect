const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/route-map/network-atlas.js"), "utf8");
const window = { DeyttAtlasBridge: {} };
vm.runInNewContext(source.replace("window.DeyttAtlas = {", "window.DeyttAtlas = { NetworkAtlas: NetworkAtlas,"), { window });

function labels(server, width = 600, serverX = 240, userX = 440) {
  const atlas = Object.create(window.DeyttAtlas.NetworkAtlas.prototype);
  const context = new Proxy({
    font: "",
    measureText(text) { return { width: String(text).length * Number(this.font.match(/(\d+)px/)[1]) * .55 }; },
  }, { get(target, key) { return key in target ? target[key] : () => {}; } });
  Object.assign(atlas, {
    width, height: 500, context, options: {}, connected: true,
    route: server, userLocation: { city: "ufa", country: "ru" },
    projectedNodes: { [server]: { x: serverX, y: 250, z: 1 }, user: { x: userX, y: 250, z: 1 } },
    routeSegments: [{ left: serverX, right: userX, top: 249, bottom: 251 }],
    labelPlacements: new Map(), copy: (value) => value,
    activeRouteNodeKeys: () => [server], isAndroidViewportAnchorVisible: () => true,
  });
  const occupied = [];
  atlas.drawLabel("user", {}, occupied);
  const user = occupied[0];
  atlas.drawLabel(server, {}, occupied);
  assert.equal(occupied.length, 2, `${server}: both labels remain visible`);
  const serverLabel = occupied[1];
  for (const box of occupied) {
    assert.ok(box.left >= 8 && box.left + box.width <= width - 8, "label stays within viewport");
    for (const pin of Object.values(atlas.projectedNodes)) {
      assert.ok(pin.x <= box.left - 5 || pin.x >= box.left + box.width + 5 ||
        pin.y <= box.top - 5 || pin.y >= box.top + box.height + 5, "label does not cover a pin");
    }
  }
  assert.ok(user.left >= serverLabel.left + serverLabel.width || serverLabel.left >= user.left + user.width ||
    user.top >= serverLabel.top + serverLabel.height || serverLabel.top >= user.top + user.height, "labels do not overlap");
  return { user, serverLabel };
}

for (const server of ["nl", "de", "fi", "ru", "it"]) {
  const { user, serverLabel } = labels(server);
  assert.ok(user.left > 440, `${server}: user label points right`);
  assert.ok(serverLabel.left + serverLabel.width < 240, `${server}: server label points left`);
}
const compact = labels("nl", 360, 100, 265);
assert.ok(compact.user.left > 265 && compact.serverLabel.left + compact.serverLabel.width < 100);
labels("nl", 600, 40, 590);
console.log("Android connected label directions, compact layout and edge fallbacks: 7 cases passed");
