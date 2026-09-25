(function () {
  const root = document.querySelector(".network-atlas");
  document.documentElement.dataset.theme = "dark";
  window.deyttMapRoute = "auto";
  window.deyttMapUserLocation = null;
  window.deyttMapNodeTapped = function (key) {
    if (window.DeyttAtlasBridge && typeof window.DeyttAtlasBridge.onNodeTap === "function") {
      window.DeyttAtlasBridge.onNodeTap(String(key || ""));
    }
  };
  window.deyttSetMapRoute = function (route) {
    window.deyttMapRoute = route;
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.setRoute(route, false);
      window.deyttMapAtlas.showcaseFocused = route !== "auto";
      window.deyttMapAtlas.start();
    }
  };
  window.deyttSetMapTraffic = function (enabled) {
    if (!window.deyttMapAtlas) return;
    window.deyttMapAtlas.animateTraffic = Boolean(enabled);
    window.deyttMapAtlas.start();
  };
  window.deyttSetMapUserLocation = function (latitude, longitude, details) {
    window.deyttMapUserLocation = { latitude: latitude, longitude: longitude, details: details || {} };
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.setUserLocation(latitude, longitude, details || {});
    }
  };
  window.deyttClearMapUserLocation = function () {
    window.deyttMapUserLocation = null;
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.userLocation = null;
      window.deyttMapAtlas.staticDirty = true;
      window.deyttMapAtlas.start();
    }
  };

  window.DeyttAtlas.create(root, {
    variant: "showcase",
    route: "auto",
    topologyUrl: "world-land.json",
    animateTraffic: false,
    selectOnTap: false,
  }).then(function (atlas) {
    window.deyttMapAtlas = atlas;
    const status = root.querySelector("[data-atlas-status]");
    if (status) {
      status.textContent = "";
      status.setAttribute("aria-hidden", "true");
    }
    window.deyttSetMapRoute(window.deyttMapRoute);
    if (window.deyttMapUserLocation) {
      const location = window.deyttMapUserLocation;
      window.deyttMapAtlas.setUserLocation(location.latitude, location.longitude, location.details);
    }
  }).catch(function () {
    const status = root.querySelector("[data-atlas-status]");
    if (status) {
      status.removeAttribute("aria-hidden");
      status.textContent = "карта временно недоступна";
    }
  });
}());
