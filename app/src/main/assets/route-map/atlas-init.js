(function () {
  const root = document.querySelector(".network-atlas");
  document.documentElement.dataset.theme = "dark";
  window.deyttMapRoute = "auto";
  window.deyttMapLanguage = "ru";
  window.deyttMapUserLocation = null;
  window.deyttMapEgressCountry = null;
  window.deyttMapActiveAutoRoute = null;
  window.deyttMapConnected = false;
  window.deyttMapTrafficActive = false;
  window.deyttMapReducedMotion = false;
  window.deyttMapOverviewMode = false;
  window.deyttMapViewportAnchor = null;
  window.deyttSetMapViewportAnchor = function (x, y, width, height) {
    window.deyttMapViewportAnchor = { x, y, width, height };
    if (window.deyttMapAtlas) window.deyttMapAtlas.setViewportAnchor(x, y, width, height);
  };
  const windowsProfile = new URLSearchParams(window.location.search).get("profile") === "windows";
  const initialZoom = window.DeyttAtlasBridge ? 1.28 : 1.1;
  const windowsPalette = document.documentElement.dataset.atlasPalette === "windows";
  if (windowsProfile) document.documentElement.dataset.atlasProfile = "windows";
  function updateInteractionHint() {
    if (!windowsProfile) return;
    const hint = root.querySelector(".network-atlas__hint");
    if (hint) hint.style.display = "none";
    const canvas = root.querySelector("canvas");
    if (canvas) canvas.setAttribute("aria-label", window.deyttMapLanguage === "en"
      ? "interactive deytt network globe. use the mouse wheel to zoom, drag to pan, or use arrow keys."
      : "интерактивный глобус deytt. масштабируйте колесиком, перетаскивайте карту или используйте клавиши со стрелками.");
  }
  function notifyHost(message) {
    if (typeof window.invokeCSharpAction === "function") {
      try { window.invokeCSharpAction(message); return; } catch (_) { /* Native view may be closing. */ }
    }
    if (window.chrome && window.chrome.webview && typeof window.chrome.webview.postMessage === "function") {
      try { window.chrome.webview.postMessage(message); return; } catch (_) { /* Native view may be closing. */ }
    }
    if (window.DeyttAtlasBridge && typeof window.DeyttAtlasBridge.onNodeTap === "function") {
      window.DeyttAtlasBridge.onNodeTap(message);
    }
  }
  window.deyttMapNodeTapped = function (key) {
    notifyHost(String(key || ""));
  };
  window.deyttZoomMap = function (factor) {
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    const scale = Number(factor);
    atlas.targetZoom = atlas.clampZoom(atlas.targetZoom * (Number.isFinite(scale) ? scale : 1));
    atlas.staticDirty = true;
    atlas.start();
  };
  window.deyttResetMapView = function () {
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    atlas.targetLon = 15;
    atlas.targetLat = 50;
    atlas.targetZoom = initialZoom;
    atlas.velocityLon = 0;
    atlas.staticDirty = true;
    atlas.start();
  };
  window.deyttSetMapRoute = function (route) {
    window.deyttMapRoute = route;
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.setRoute(route, false);
      window.deyttMapAtlas.showcaseFocused = !window.deyttMapOverviewMode && route !== "auto";
      window.deyttMapAtlas.start();
    }
    updateInteractionHint();
  };
  window.deyttSetMapOverviewMode = function (active) {
    window.deyttMapOverviewMode = Boolean(active);
    root.classList.toggle("is-overview-mode", window.deyttMapOverviewMode);
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    atlas.overviewMode = window.deyttMapOverviewMode;
    if (atlas.overviewMode) {
      window.deyttResetMapView();
      atlas.lastFocusSignature = null;
      atlas.focusRoute();
    }
  };
  window.deyttSetMapLanguage = function (language) {
    window.deyttMapLanguage = language === "en" ? "en" : "ru";
    if (window.deyttMapAtlas) window.deyttMapAtlas.setLanguage(window.deyttMapLanguage);
    updateInteractionHint();
  };
  window.deyttSetMapEgressCountry = function (countryCode) {
    window.deyttMapEgressCountry = countryCode || null;
    if (window.deyttMapAtlas) window.deyttMapAtlas.setAutoExitCountry(window.deyttMapEgressCountry);
  };
  window.deyttSetMapActiveAutoRoute = function (routeKey) {
    window.deyttMapActiveAutoRoute = routeKey || null;
    if (window.deyttMapAtlas) window.deyttMapAtlas.setActiveAutoRoute(window.deyttMapActiveAutoRoute);
  };
  window.deyttSetMapConnected = function (active) {
    window.deyttMapConnected = Boolean(active);
    if (window.deyttMapAtlas) window.deyttMapAtlas.setConnected(window.deyttMapConnected);
  };
  window.deyttSetMapTrafficActive = function (active) {
    window.deyttMapTrafficActive = Boolean(active);
    if (window.deyttMapAtlas) window.deyttMapAtlas.setTrafficActive(window.deyttMapTrafficActive);
  };
  window.deyttSetMapBackdropStage = function (active) {
    root.classList.toggle("is-backdrop-stage", Boolean(active));
  };
  window.deyttSetMapBackdropProgress = function (progress) {
    const value = Math.max(0, Math.min(1, Number(progress) || 0));
    root.style.setProperty("--backdrop-blur", (value * 28).toFixed(2) + "px");
    root.style.setProperty("--backdrop-opacity", (1 - value * .6).toFixed(3));
  };
  window.deyttSetMapReducedMotion = function (enabled) {
    const next = Boolean(enabled);
    root.classList.toggle("is-reduced-motion", next);
    if (window.deyttMapReducedMotion === next) return;
    window.deyttMapReducedMotion = next;
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    atlas.staticDirty = true;
    if (next) atlas.velocityLon = 0;
    if (atlas.frame) {
      cancelAnimationFrame(atlas.frame);
      atlas.frame = 0;
    }
    atlas.lastFrame = 0;
    atlas.start();
  };
  window.deyttSetMapLocations = function (locations) {
    if (window.deyttMapAtlas) window.deyttMapAtlas.setAvailableLocations(locations);
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
      window.deyttMapAtlas.setUserLocation(null, null, {});
    }
  };

  window.DeyttAtlas.create(root, {
    initialZoom: initialZoom,
    variant: "showcase",
    route: "auto",
    topologyUrl: "world-land.json",
    selectOnTap: false,
    wheelZoomOnly: windowsProfile,
    pixelRatioFloor: windowsProfile ? 1.6 : 1,
    pixelRatioCap: windowsProfile ? 2.25 : undefined,
    labelScale: windowsPalette ? 0.9 : windowsProfile ? 1.34 : 1,
    labelFontFamily: windowsPalette ? '"Inter Tight", "Segoe UI", sans-serif' : windowsProfile ? 'system-ui, "Segoe UI", sans-serif' : undefined,
    routeFocusMinZoom: windowsProfile ? 4.2 : undefined,
    routeFocusMaxZoom: windowsProfile ? 8.5 : undefined,
    routeFocusSingleZoom: windowsProfile ? 8.5 : undefined,
    routeFocusFill: windowsProfile ? .34 : undefined,
    routeFocusScale: windowsProfile ? 1.06 : undefined,
  }).then(function (atlas) {
    window.deyttMapAtlas = atlas;
    if (window.deyttMapViewportAnchor) {
      const anchor = window.deyttMapViewportAnchor;
      atlas.setViewportAnchor(anchor.x, anchor.y, anchor.width, anchor.height);
    }
    // Reapply native mode after async creation: an earlier setter cannot reset an atlas that does not exist yet.
    window.deyttSetMapOverviewMode(window.deyttMapOverviewMode);
    const systemReducedMotion = atlas.reducedMotion;
    atlas.reducedMotion = {
      get matches() { return window.deyttMapReducedMotion || systemReducedMotion.matches; }
    };
    window.deyttMapAtlas.setLanguage(window.deyttMapLanguage);
    window.deyttMapAtlas.setTrafficActive(window.deyttMapTrafficActive);
    window.deyttMapAtlas.setConnected(window.deyttMapConnected);
    window.deyttSetMapReducedMotion(window.deyttMapReducedMotion);
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
      window.deyttMapAtlas.setUserLocation(location.latitude, location.longitude, location.details);
    }
    if (window.deyttMapOverviewMode) {
      window.deyttMapAtlas.lastFocusSignature = null;
      window.deyttMapAtlas.focusRoute();
    }
    updateInteractionHint();
    notifyHost("atlas-ready");
  }).catch(function () {
    const status = root.querySelector("[data-atlas-status]");
    if (status) {
      status.removeAttribute("aria-hidden");
      status.textContent = window.deyttMapLanguage === "en" ? "Map temporarily unavailable" : "карта временно недоступна";
    }
    notifyHost("atlas-error");
  });
}());
