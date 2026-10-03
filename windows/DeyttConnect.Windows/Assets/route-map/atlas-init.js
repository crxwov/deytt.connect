(function () {
  const root = document.querySelector(".network-atlas");
  document.documentElement.dataset.theme = "dark";
  window.deyttMapRoute = "auto";
  window.deyttMapLanguage = "ru";
  window.deyttMapUserLocation = null;
  window.deyttMapEgressCountry = null;
  window.deyttMapActiveAutoRoute = null;
  window.deyttMapTrafficActive = false;
  window.deyttMapReducedMotion = false;
  window.deyttMapLocations = ["nl", "de", "fi", "ru"];
  function notifyHost(message, retries) {
    if (typeof window.invokeCSharpAction === "function") {
      try {
        window.invokeCSharpAction(message);
        return;
      } catch (_) { /* The native adapter may be detaching. */ }
    }
    if (window.chrome && window.chrome.webview && typeof window.chrome.webview.postMessage === "function") {
      try {
        window.chrome.webview.postMessage(message);
        return;
      } catch (_) { /* Retry after the adapter is ready. */ }
    }
    if (window.DeyttAtlasBridge && typeof window.DeyttAtlasBridge.onNodeTap === "function") {
      try {
        window.DeyttAtlasBridge.onNodeTap(message);
        return;
      } catch (_) { /* Retry after the adapter is ready. */ }
    }
    if (retries > 0) setTimeout(function () { notifyHost(message, retries - 1); }, 100);
  }
  function applyReducedMotion() {
    const canvas = root.querySelector("canvas");
    if (canvas) {
      canvas.style.transition = window.deyttMapReducedMotion ? "none" : "";
      canvas.style.transform = window.deyttMapReducedMotion ? "none" : "";
    }
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    atlas.staticDirty = true;
    if (window.deyttMapReducedMotion) atlas.velocityLon = 0;
    if (atlas.frame) {
      cancelAnimationFrame(atlas.frame);
      atlas.frame = 0;
    }
    atlas.lastFrame = 0;
    atlas.start();
  }
  window.deyttSetMapReducedMotion = function (enabled) {
    const next = Boolean(enabled);
    if (window.deyttMapReducedMotion === next) return;
    window.deyttMapReducedMotion = next;
    applyReducedMotion();
  };
  window.deyttMapNodeTapped = function (key) {
    const node = String(key || "").toLowerCase();
    notifyHost(node, 0);
  };
  window.deyttSetMapRoute = function (route) {
    window.deyttMapRoute = route;
    if (window.deyttMapAtlas) {
      // Keep the desktop overview on the whole globe. Route changes update the
      // highlighted path without zooming the camera into a single exit node.
      const routeChanged = window.deyttMapAtlas.route !== route;
      window.deyttMapAtlas.setRoute(route, false, false);
      window.deyttMapAtlas.showcaseFocused = route !== "auto";
      if (routeChanged) {
        window.deyttMapAtlas.targetLon = 15;
        window.deyttMapAtlas.targetLat = 50;
        window.deyttMapAtlas.targetZoom = 1.1;
      }
      window.deyttMapAtlas.start();
    }
  };
  window.deyttSetMapLanguage = function (language) {
    window.deyttMapLanguage = language === "en" ? "en" : "ru";
    if (window.deyttMapAtlas) window.deyttMapAtlas.setLanguage(window.deyttMapLanguage);
  };
  window.deyttSetMapEgressCountry = function (countryCode) {
    window.deyttMapEgressCountry = countryCode || null;
    if (window.deyttMapAtlas) window.deyttMapAtlas.setAutoExitCountry(window.deyttMapEgressCountry);
  };
  window.deyttSetMapActiveAutoRoute = function (routeKey) {
    window.deyttMapActiveAutoRoute = routeKey || null;
    if (window.deyttMapAtlas) window.deyttMapAtlas.setActiveAutoRoute(window.deyttMapActiveAutoRoute);
  };
  window.deyttSetMapTrafficActive = function (active) {
    window.deyttMapTrafficActive = Boolean(active);
    if (window.deyttMapAtlas) window.deyttMapAtlas.setTrafficActive(window.deyttMapTrafficActive);
  };
  window.deyttSetMapLocations = function (locations) {
    const allowed = ["nl", "de", "fi", "ru"];
    window.deyttMapLocations = Array.from(new Set((Array.isArray(locations) ? locations : [])
      .map((key) => String(key).toLowerCase())
      .filter((key) => allowed.includes(key))));
    if (window.deyttMapAtlas) window.deyttMapAtlas.setAvailableLocations(window.deyttMapLocations, false);
  };
  window.deyttSetMapUserLocation = function (latitude, longitude, details) {
    window.deyttMapUserLocation = { latitude: latitude, longitude: longitude, details: details || {} };
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.setUserLocation(latitude, longitude, details || {}, false);
    }
  };
  window.deyttClearMapUserLocation = function () {
    window.deyttMapUserLocation = null;
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.setUserLocation(null, null, {}, false);
    }
  };

  Promise.resolve().then(function () {
    return window.DeyttAtlas.create(root, {
      variant: "showcase",
      route: "auto",
      topologyUrl: "world-land.json",
      selectOnTap: false,
    });
  }).then(function (atlas) {
    window.deyttMapAtlas = atlas;
    const systemReducedMotion = atlas.reducedMotion;
    atlas.reducedMotion = {
      get matches() { return window.deyttMapReducedMotion || systemReducedMotion.matches; },
    };
    applyReducedMotion();
    window.deyttMapAtlas.setLanguage(window.deyttMapLanguage);
    window.deyttMapAtlas.setTrafficActive(window.deyttMapTrafficActive);
    const status = root.querySelector("[data-atlas-status]");
    if (status) {
      status.textContent = "";
      status.setAttribute("aria-hidden", "true");
    }
    window.deyttSetMapRoute(window.deyttMapRoute);
    window.deyttSetMapEgressCountry(window.deyttMapEgressCountry);
    window.deyttSetMapActiveAutoRoute(window.deyttMapActiveAutoRoute);
    if (window.deyttMapLocations) window.deyttSetMapLocations(window.deyttMapLocations);
    if (window.deyttMapUserLocation) {
      const location = window.deyttMapUserLocation;
      window.deyttMapAtlas.setUserLocation(location.latitude, location.longitude, location.details, false);
    }
    // The desktop home card is a global overview. Start with the full globe
    // visible even if init() fitted the default route while loading topology.
    window.deyttMapAtlas.centerLon = window.deyttMapAtlas.targetLon = 15;
    window.deyttMapAtlas.centerLat = window.deyttMapAtlas.targetLat = 50;
    window.deyttMapAtlas.zoom = window.deyttMapAtlas.targetZoom = 1.14;
    window.deyttMapAtlas.velocityLon = 0;
    window.deyttMapAtlas.staticDirty = true;
    window.deyttMapAtlas.start();
    // Navigation can finish before the asynchronous topology fetch and atlas creation.
    // Ask the host to resend its latest state after the atlas is fully initialized.
    notifyHost("atlas-ready", 15);
  }).catch(function () {
    const status = root.querySelector("[data-atlas-status]");
    if (status) {
      status.removeAttribute("aria-hidden");
      status.textContent = window.deyttMapLanguage === "en" ? "Map temporarily unavailable" : "карта временно недоступна";
    }
    notifyHost("atlas-error", 15);
  });
}());
