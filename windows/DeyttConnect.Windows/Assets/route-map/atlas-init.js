(function () {
  "use strict";

  const root = document.querySelector(".network-atlas");
  const mainPanel = root.querySelector(".home-panel-shell");
  const compactPanel = root.querySelector(".home-compact");
  const panelTiltStartZoom = 1;
  const panelTiltRampZoom = 0.25;
  const panelTiltMaxDegrees = 20;
  const strings = {
    "en": {
      "latency": "response", "speed": "speed", "measurement": "last route measurement",
      "globe": "interactive deytt route globe", "route": "route"
    },
    "ru": {
      "latency": "отклик", "speed": "скорость", "measurement": "последний замер маршрута",
      "globe": "интерактивный глобус маршрута deytt.", "route": "маршрут"
    }
  };
  let language = "ru";
  let compact = false;
  let immersive = false;
  let immersiveFromZoom = false;
  let automaticImmersiveDismissed = false;
  let reducedMotion = false;
  let zoomPresentationKey = "";
  let presentationLanguage = "";
  let entranceAnimations = [];
  let compactAnimation = null;
  let dockGeometryKey = "";
  const systemReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  function motionIsReduced() {
    return reducedMotion || systemReducedMotion.matches;
  }
  root.classList.toggle("reduce-motion", motionIsReduced());
  window.deyttMapRoute = "auto";
  window.deyttMapLanguage = language;
  window.deyttMapUserLocation = null;
  window.deyttMapEgressCountry = null;
  window.deyttMapActiveAutoRoute = null;
  window.deyttMapLocations = [];
  window.deyttMapTrafficActive = false;
  window.deyttMapHostActive = true;
  window.deyttMapReducedMotion = false;
  window.deyttHomePresentation = null;

  function notifyHost(message) {
    if (typeof window.invokeCSharpAction === "function") {
      try { window.invokeCSharpAction(message); return; } catch (_) { /* Host may be closing. */ }
    }
    if (window.chrome && window.chrome.webview && typeof window.chrome.webview.postMessage === "function") {
      try { window.chrome.webview.postMessage(message); } catch (_) { /* Host may be closing. */ }
    }
  }

  function setCompact(next) {
    if (compact === next) return;
    compact = next;
    root.classList.toggle("is-zoomed", compact);
    if (window.deyttMapAtlas) window.deyttMapAtlas.resize();
    syncPanelMode();
  }

  function syncPanelMode() {
    root.classList.toggle("is-immersive", immersive);
    document.documentElement.classList.toggle("is-immersive", immersive);
    const compactVisible = compact || immersive;
    const wasCompactVisible = compactPanel.getAttribute("aria-hidden") === "false";
    const mainHidden = compactVisible || root.classList.contains("is-inspecting");
    mainPanel.setAttribute("aria-hidden", mainHidden ? "true" : "false");
    compactPanel.setAttribute("aria-hidden", compactVisible ? "false" : "true");
    compactPanel.toggleAttribute("inert", !compactVisible);
    mainPanel.toggleAttribute("inert", mainHidden);
    if (wasCompactVisible !== compactVisible) {
      entranceAnimations = entranceAnimations.filter(animation => {
        if (animation.effect?.target !== compactPanel) return true;
        animation.cancel();
        return false;
      });
      if (compactAnimation) compactAnimation.cancel();
      compactAnimation = null;
      if (compactVisible && !motionIsReduced() && window.deyttMapHostActive)
        compactAnimation = compactPanel.animate([
          { opacity: 0, translate: "0 12px" }, { opacity: 1, translate: "0 0" }
        ], { duration: 300, easing: "cubic-bezier(.16,1,.3,1)" });
    }
    const exitButton = root.querySelector("#immersive-exit");
    if (exitButton) exitButton.hidden = !immersive;
  }

  function setImmersive(next) {
    const value = Boolean(next);
    if (immersive === value) return;
    immersive = value;
    syncPanelMode();
    if (immersive) root.querySelector("canvas[data-atlas-interactive]").focus({ preventScroll: true });
    requestAnimationFrame(function () {
      if (window.deyttMapAtlas) {
        window.deyttMapAtlas.resize();
        window.deyttMapAtlas.start();
      }
    });
  }

  function requestImmersive(next, automatic = false) {
    const zoom = window.deyttMapAtlas?.targetZoom || window.deyttMapAtlas?.zoom || 1;
    immersiveFromZoom = Boolean(next && automatic);
    if (!next && !automatic && zoom >= 2.12) automaticImmersiveDismissed = true;
    if (zoom < 1.66) automaticImmersiveDismissed = false;
    setImmersive(next);
    notifyHost(next ? "deytt:immersive:enter" : "deytt:immersive:exit");
  }

  function updateZoomPresentation() {
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    const zoom = atlas.zoom || 1;
    // Traffic frames never change panel layout. Avoid repeated DOM geometry
    // reads and dataset writes at the monitor refresh rate.
    const key = [atlas.width, atlas.height, zoom.toFixed(4), compact, immersive, motionIsReduced(), language].join("|");
    if (zoomPresentationKey === key) return;
    zoomPresentationKey = key;
    const wide = atlas.width >= 1180;
    const panelTilt = wide && !compact && !immersive && !motionIsReduced()
      ? Math.min(panelTiltMaxDegrees, Math.max(0,
          ((zoom - panelTiltStartZoom) / panelTiltRampZoom) * panelTiltMaxDegrees))
      : 0;
    const panelAngle = panelTilt > 0.005 ? `-${panelTilt.toFixed(2)}deg` : "0deg";
    if (root.style.getPropertyValue("--panel-angle") !== panelAngle)
      root.style.setProperty("--panel-angle", panelAngle);
    // Dock just before the growing globe reaches the readable panel content.
    // Wheel zoom changes only this scene; fullscreen is an explicit command.
    const geometryKey = [atlas.width, atlas.height, atlas.radius, language, presentationLanguage].join("|");
    if (!compact && wide && dockGeometryKey !== geometryKey) {
      dockGeometryKey = geometryKey;
      const scene = atlas.canvas.getBoundingClientRect();
      const copy = root.querySelector(".route-heading").getBoundingClientRect();
      const readableLeft = copy.left - scene.left;
      const contactZoom = Math.max(1.18, (readableLeft - atlas.width * .34 - 20) / Math.max(1, atlas.radius));
      root.dataset.dockZoom = Math.max(1.22, contactZoom * 1.06).toFixed(3);
    }
    const dockZoom = wide ? Number(root.dataset.dockZoom || 1.62) : 1.62;
    if (!compact && zoom >= dockZoom) setCompact(true);
    else if (compact && zoom < dockZoom / 1.12) setCompact(false);
  }

  function setAction(button, label, action, enabled, tone) {
    const labelTarget = button.querySelector("[data-action-label]") || button;
    labelTarget.textContent = label || "";
    button.dataset.action = action || "";
    button.disabled = !enabled;
    button.dataset.tone = tone || "normal";
    const accessibleLabel = action === "home-disconnect"
      ? (label === "Connected" ? "Disconnect VPN" : "Отключить VPN")
      : action === "home-cancel"
        ? (label === "Connecting" ? "Cancel connection" : "Отменить подключение")
        : label || "";
    button.setAttribute("aria-label", accessibleLabel);
    button.title = accessibleLabel;
  }

  function routeFlag(code) {
    const normalized = String(code || "").toUpperCase();
    return /^(NL|DE|RU|FI|IT)$/.test(normalized) ? normalized : "";
  }

  function setRouteLabel(prefix, state) {
    const flag = root.querySelector("#" + prefix + "flag");
    const country = root.querySelector("#" + prefix + "country");
    const city = root.querySelector("#" + prefix + "city");
    const protocol = root.querySelector("#" + prefix + "protocol");
    if (flag) {
      const code = routeFlag(state.countryCode);
      flag.dataset.country = code;
      flag.classList.toggle("is-generic", !code);
      flag.textContent = code ? "" : "🌐";
      flag.setAttribute("aria-label", code || "");
    }
    if (country) country.textContent = state.country || "";
    if (city) city.textContent = state.city || "";
    if (protocol) {
      protocol.textContent = state.protocol || "";
      protocol.hidden = !state.protocol;
    }
  }

  function applyHomePresentation(state) {
    if (!state || typeof state !== "object") return;
    const previous = window.deyttHomePresentation;
    if (previous && presentationLanguage === language && JSON.stringify(previous) === JSON.stringify(state)) return;
    presentationLanguage = language;
    if (!previous || previous.country !== state.country || previous.city !== state.city || previous.protocol !== state.protocol)
      zoomPresentationKey = "";
    window.deyttHomePresentation = state;
    root.dataset.connectionTone = state.statusTone || "normal";
    root.classList.toggle("is-selecting", state.statusTone === "warn" && state.countryCode === "");
    if (window.deyttMapAtlas) {
      const selecting = root.classList.contains("is-selecting");
      if (window.deyttMapAtlas.autoSelecting !== selecting) window.deyttMapAtlas.overlayDirty = true;
      window.deyttMapAtlas.autoSelecting = selecting;
      window.deyttMapAtlas.start();
    }
    const status = root.querySelector("#home-status");
    const error = root.querySelector("#home-error");
    const note = root.querySelector("#home-measurement-note");
    const latency = root.querySelector("#home-latency");
    const speed = root.querySelector("#home-speed");
    const compactStatus = root.querySelector("#compact-status");
    const compactStatusLine = root.querySelector(".compact-status-line");
    const latencyMetric = latency.parentElement;
    const speedMetric = speed.parentElement;
    if (status) {
      status.textContent = state.status || "";
      status.dataset.tone = state.statusTone || "normal";
      status.dataset.fit = Array.from(state.status || "").length <= 15 ? "true" : "false";
      if (previous && previous.status !== state.status && !motionIsReduced()) {
        status.getAnimations().forEach(animation => animation.cancel());
        status.animate([{ opacity: .35, transform: "translateY(4px)" }, { opacity: 1, transform: "translateY(0)" }],
          { duration: 340, easing: "cubic-bezier(.23,1,.32,1)" });
      }
    }
    if (compactStatus) {
      compactStatus.textContent = state.status || "";
      compactStatus.dataset.tone = state.statusTone || "normal";
    }
    if (compactStatusLine) compactStatusLine.dataset.tone = state.statusTone || "normal";
    if (error) {
      error.textContent = state.message || "";
      error.hidden = !state.message;
    }
    const compactError = root.querySelector("#compact-error");
    if (compactError) {
      compactError.textContent = state.message || "";
      compactError.hidden = state.statusTone !== "error" || !state.message;
    }
    setRouteLabel("home-", state);
    setRouteLabel("compact-", state);
    setAction(root.querySelector("#home-action"), state.actionLabel, state.action, state.actionEnabled, state.actionTone);
    setAction(root.querySelector("#compact-action"), state.actionLabel, state.action, state.actionEnabled, state.actionTone);
    root.querySelectorAll("[data-action-status]").forEach(el => {
      el.hidden = state.statusTone !== "good";
      el.textContent = language === "en" ? "connected" : "подключено";
    });
    if (latency) latency.textContent = state.latency == null ? "—" : state.latency;
    if (speed) speed.textContent = state.speed == null ? "—" : state.speed;
    root.querySelector("#compact-latency").textContent = (state.latency == null ? "—" : state.latency) + " " + (language === "en" ? "ms" : "мс");
    root.querySelector("#compact-speed").textContent = (state.speed == null ? "—" : state.speed) + " " + (language === "en" ? "Mbit/s" : "Мбит/с");
    latencyMetric.dataset.tone = state.latencyTone || "normal";
    speedMetric.dataset.tone = state.speedTone || "normal";
    note.textContent = state.measurementLabel || "";
    note.hidden = !state.measurementLabel;
    const measure = root.querySelector("#home-measure");
    measure.textContent = state.measurementActionLabel || (language === "en" ? "refresh measurement" : "обновить замер");
    measure.disabled = state.measurementEnabled === false;
    const summary = root.querySelector("#home-route-description");
    if (summary) summary.textContent = [state.country, state.city, state.protocol].filter(Boolean).join(" · ");
    const canvas = root.querySelector("canvas[data-atlas-interactive]");
    if (canvas) canvas.setAttribute("aria-label", strings[language].globe + " " + [state.country, state.city].filter(Boolean).join(" · "));
    if (previous && !motionIsReduced()) {
      const reveal = function (element, distance = 0) {
        if (!element) return;
        element.getAnimations().forEach(animation => animation.cancel());
        element.animate([{ opacity: .45, transform: "translateY(" + distance + "px)" },
          { opacity: 1, transform: "translateY(0)" }],
          { duration: 420, easing: "cubic-bezier(.2,.8,.2,1)" });
      };
      if (previous.country !== state.country || previous.city !== state.city || previous.protocol !== state.protocol)
        reveal(root.querySelector(".route-heading"), 14);
      if (previous.latency !== state.latency) reveal(latency);
      if (previous.speed !== state.speed) reveal(speed);
      if (state.message && previous.message !== state.message) reveal(error, 3);
    }
  }

  function setLanguage(value) {
    language = value === "en" ? "en" : "ru";
    document.documentElement.lang = language;
    document.documentElement.dataset.theme = document.documentElement.dataset.theme || "dark";
    root.querySelector("#latency-label").textContent = strings[language].latency;
    root.querySelector("#speed-label").textContent = strings[language].speed;
    root.querySelector("#home-latency-unit").textContent = language === "en" ? "ms" : "мс";
    root.querySelector("#home-speed-unit").textContent = language === "en" ? "Mbit/s" : "Мбит/с";
    ["upload", "download", "control"].forEach((id, index) => {
      root.querySelector("[data-legend-" + id + "]").textContent = language === "en"
        ? ["outbound data", "return traffic", "service traffic"][index]
        : ["исходящие данные", "ответный трафик", "служебный трафик"][index];
    });
    const canvas = root.querySelector("canvas[data-atlas-interactive]");
    if (canvas) canvas.setAttribute("aria-label", strings[language].globe);
    if (window.deyttHomePresentation) applyHomePresentation(window.deyttHomePresentation);
    const atlas = window.deyttMapAtlas;
    if (atlas) atlas.setLanguage(language);
  }

  window.deyttMapNodeTapped = function (key) { notifyHost(String(key || "")); };
  window.deyttSetMapRoute = function (route) {
    window.deyttMapRoute = route;
    const atlas = window.deyttMapAtlas;
    if (atlas) {
      atlas.setRoute(route, false);
      atlas.showcaseFocused = route !== "auto";
      atlas.start();
    }
  };
  window.deyttSetMapLanguage = setLanguage;
  window.deyttSetMapImmersive = setImmersive;
  window.deyttSetMapTheme = function (theme) {
    const next = theme === "light" ? "light" : "dark";
    if (document.documentElement.dataset.theme === next) return;
    document.documentElement.dataset.theme = next;
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.markMapDirty();
      window.deyttMapAtlas.start();
    }
  };
  window.deyttSetHomePresentation = applyHomePresentation;
  window.deyttReplayHomeEntrance = function () {
    entranceAnimations.forEach(animation => animation.cancel());
    entranceAnimations = [];
    if (compactAnimation) compactAnimation.cancel();
    compactAnimation = null;
    if (motionIsReduced() || !window.deyttMapHostActive) return;
    const reveal = (selector, offset, delay, duration = 500) => {
      const element = root.querySelector(selector);
      if (!element) return;
      entranceAnimations.push(element.animate([
        { opacity: 0, translate: "0 " + offset + "px" },
        { opacity: 1, translate: "0 0" }
      ], { duration, delay, easing: "cubic-bezier(.16,1,.3,1)", fill: "backwards" }));
    };
    reveal(".network-atlas__canvas-wrap", 0, 0, 620);
    reveal(".route-heading", 20, 60);
    reveal(".home-action", 16, 140);
    reveal(".home-metrics", 14, 220);
    if (compactPanel.getAttribute("aria-hidden") === "false") reveal(".home-compact", 14, 100);
  };
  window.deyttSetMapHostActive = function (active) {
    const next = Boolean(active), changed = window.deyttMapHostActive !== next;
    window.deyttMapHostActive = next;
    root.dataset.hostActive = String(next);
    if (window.deyttMapAtlas) window.deyttMapAtlas.setHostActive(next);
    if (!next) {
      entranceAnimations.forEach(animation => animation.cancel());
      entranceAnimations = [];
      if (compactAnimation) compactAnimation.cancel();
      compactAnimation = null;
    } else if (changed) {
      zoomPresentationKey = "";
      requestAnimationFrame(() => { window.deyttMapAtlas?.resize(); window.deyttReplayHomeEntrance(); });
    }
  };
  window.deyttResumeMap = function () {
    window.deyttSetMapHostActive(true);
    if (window.deyttMapAtlas) window.deyttMapAtlas.resumeHost();
  };
  systemReducedMotion.addEventListener("change", () => {
    root.classList.toggle("reduce-motion", motionIsReduced());
    if (compactAnimation) compactAnimation.cancel();
    compactAnimation = null;
    zoomPresentationKey = "";
    window.deyttReplayHomeEntrance();
    window.deyttMapAtlas?.markMapDirty();
    window.deyttMapAtlas?.start();
  });
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
    root.classList.toggle("has-traffic", window.deyttMapTrafficActive);
    if (window.deyttMapAtlas) window.deyttMapAtlas.setTrafficActive(window.deyttMapTrafficActive);
  };
  window.deyttSetMapReducedMotion = function (enabled) {
    if (reducedMotion === Boolean(enabled)) return;
    reducedMotion = Boolean(enabled);
    window.deyttMapReducedMotion = reducedMotion;
    root.classList.toggle("reduce-motion", motionIsReduced());
    if (motionIsReduced()) {
      entranceAnimations.forEach(animation => animation.cancel());
      entranceAnimations = [];
      if (compactAnimation) compactAnimation.cancel();
      compactAnimation = null;
    }
    if (window.deyttMapAtlas) {
      window.deyttMapAtlas.markMapDirty();
      window.deyttMapAtlas.reducedMotion = { get matches() { return motionIsReduced(); } };
      if (motionIsReduced()) window.deyttMapAtlas.velocityLon = 0;
      window.deyttMapAtlas.start();
    }
    updateZoomPresentation();
  };
  window.deyttSetMapLocations = function (locations) {
    window.deyttMapLocations = Array.isArray(locations) ? locations : [];
    if (window.deyttMapAtlas) window.deyttMapAtlas.setAvailableLocations(window.deyttMapLocations);
  };
  window.deyttSetMapUserLocation = function (latitude, longitude, details) {
    window.deyttMapUserLocation = { latitude: latitude, longitude: longitude, details: details || {} };
    if (window.deyttMapAtlas) window.deyttMapAtlas.setUserLocation(latitude, longitude, details || {});
  };
  window.deyttClearMapUserLocation = function () {
    window.deyttMapUserLocation = null;
    if (window.deyttMapAtlas) window.deyttMapAtlas.setUserLocation(null, null, {});
  };
  window.deyttZoomMap = function (factor) {
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    atlas.targetZoom = Math.max(.85, Math.min(4.8, atlas.targetZoom * Number(factor || 1)));
    atlas.markMapDirty();
    atlas.start();
    updateZoomPresentation();
  };
  window.deyttResetMapView = function () {
    const atlas = window.deyttMapAtlas;
    if (!atlas) return;
    atlas.targetLon = 15;
    atlas.targetLat = 50;
    atlas.targetZoom = 1;
    atlas.velocityLon = 0;
    atlas.markMapDirty();
    atlas.start();
    updateZoomPresentation();
  };

  root.querySelector("#home-action")?.addEventListener("click", function () {
    if (!this.disabled) notifyHost(this.dataset.action);
  });
  root.querySelector("#home-measure")?.addEventListener("click", function () {
    if (!this.disabled) notifyHost("home-measure");
  });
  root.querySelector("#compact-action")?.addEventListener("click", function () {
    if (!this.disabled) notifyHost(this.dataset.action);
  });
  root.querySelector("#immersive-exit")?.addEventListener("click", function () {
    requestImmersive(false);
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && window.deyttMapAtlas?.inspectingStream != null) {
      event.preventDefault();
      window.deyttMapAtlas.resumeTraffic();
      return;
    }
    if (event.isComposing || event.repeat) return;
    const target = event.target;
    if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return;
    if (event.key === "Escape" && immersive) {
      event.preventDefault();
      requestImmersive(false);
      return;
    }
    if (event.code !== "Space" && event.key !== " ") return;
    if (target && target.closest && target.closest("button, [data-action]")) return;
    event.preventDefault();
    requestImmersive(!immersive);
  });
  root.querySelectorAll("[data-zoom]").forEach(button => button.addEventListener("click", function () {
    if (this.dataset.zoom === "in") window.deyttZoomMap(1.18);
    else if (this.dataset.zoom === "out") window.deyttZoomMap(1 / 1.18);
    else window.deyttResetMapView();
  }));
  root.querySelectorAll("[data-flow]").forEach(button => button.addEventListener("click", function () {
    const atlas = window.deyttMapAtlas;
    const line = atlas?.routeHitSegments.find(segment => segment.stream.id === this.dataset.flow);
    if (line) atlas.showTrafficInspector(line);
  }));

  const windowsProfile = new URLSearchParams(window.location.search).get("profile") === "windows";
  if (windowsProfile) document.documentElement.dataset.atlasProfile = "windows";
  const handleMotionPreferenceChange = function () {
    root.classList.toggle("reduce-motion", motionIsReduced());
    if (motionIsReduced() && window.deyttMapAtlas) window.deyttMapAtlas.velocityLon = 0;
    updateZoomPresentation();
    if (window.deyttMapAtlas) window.deyttMapAtlas.start();
  };
  if (typeof systemReducedMotion.addEventListener === "function")
    systemReducedMotion.addEventListener("change", handleMotionPreferenceChange);
  else if (typeof systemReducedMotion.addListener === "function")
    systemReducedMotion.addListener(handleMotionPreferenceChange);
  window.DeyttAtlas.create(root, {
    variant: "showcase",
    route: "auto",
    topologyUrl: "world-land.json",
    selectOnTap: false,
    wheelZoomOnly: true,
    initialZoom: 1,
    initialLon: 15,
    initialLat: 50,
    pixelRatioFloor: 1.25,
    pixelRatioCap: 1.5,
    interactionPixelRatio: 1.25,
    stableGeography: true,
    labelScale: 1.2,
    landDepth: false,
    // Keep Windows coastlines flat and crisp without the artificial offset
    // shadow beneath each country; sphere lighting supplies the depth.
    softLandShadow: false,
    labelFontFamily: '"Inter Tight", "Segoe UI", sans-serif',
    routeFocusMinZoom: 1,
    // Initial route framing must not cross the user-controlled compact threshold.
    routeFocusMaxZoom: 1,
    routeFocusSingleZoom: 1,
    routeFocusFill: .34,
    routeFocusScale: 1,
    routeFocusLatitudeOffset: 12
  }).then(function (atlas) {
    window.deyttMapAtlas = atlas;
    atlas.variant = "windows-home";
    const originalStart = atlas.start.bind(atlas);
    let lastZoomPres = -1;
    atlas.start = function () {
      this.targetZoom = Math.max(.85, Math.min(4.8, this.targetZoom));
      originalStart();
      if (Math.abs((this.zoom || 1) - lastZoomPres) > 0.005) {
        lastZoomPres = this.zoom || 1;
        updateZoomPresentation();
      }
    };
    atlas.colors = function () {
      const dark = document.documentElement.dataset.theme !== "light";
      return dark ? {
        sphereLight: "#34465B", sphereMid: "#243345", sphereShade: "#111B29",
        sphereEdge: "rgba(80,100,130,.22)", atmosphere: "rgba(0,0,0,0)", atmosphereSoft: "rgba(0,0,0,0)", rimLight: "rgba(0,0,0,0)",
        grid: null, land: "rgba(118,140,164,.93)", landDim: "rgba(96,114,135,.92)", landHot: "#71869E",
        landHighlight: "rgba(194,216,240,.29)", landRelief: "rgba(18,29,43,.74)", landLow: "rgba(80,98,119,.96)",
        border: "#AAB8C9", borderAlpha: .38, borderWidth: .8, borderHot: "#39E8BD", borderGlow: "rgba(57,232,189,.24)", route: "#FFBD32", routeHot: "#FFC857",
        download: "#FFBD32", downloadHot: "#FFE08A", upload: "#39E8BD", uploadHot: "#90F4DA",
        control: "#B897FF", controlHot: "#D9C9FF",
        node: "#F2F5FC", nodeCore: "#39E8BD", label: "#E6ECF5", labelMuted: "#A3B0C2", labelBg: "rgba(15,23,35,.88)", labelLeader: "#A3B0C2", labelBorder: "rgba(163,176,194,.16)"
      } : {
        sphereLight: "#D2E5F8", sphereMid: "#BFDBF4", sphereShade: "#A9CFEF",
        sphereEdge: "rgba(89,131,173,.58)", atmosphere: "rgba(0,0,0,0)", atmosphereSoft: "rgba(0,0,0,0)", rimLight: "rgba(0,0,0,0)",
        grid: null, land: "#1299DC", landDim: "#1299DC", landHot: "#1299DC", landAlpha: 1,
        landHighlight: "#1299DC", landRelief: "rgba(22,64,98,.32)", landLow: "#1299DC",
        border: "#AFE0F5", borderAlpha: .62, borderWidth: .8, borderHot: "#10A889", borderGlow: "rgba(16,168,137,.22)", route: "#D99000", routeHot: "#F0A900",
        download: "#B77700", downloadHot: "#FFC857", upload: "#098F78", uploadHot: "#79D6C1",
        control: "#8057C8", controlHot: "#B49BEA",
        node: "#FFFFFF", nodeCore: "#123A67", label: "#1B2938", labelMuted: "#4D6175", labelBg: "rgba(242,246,249,.96)", labelLeader: "#354B60", labelBorder: "rgba(53,75,96,.22)"
      };
    };
    atlas.reducedMotion = { get matches() { return reducedMotion || systemReducedMotion.matches; } };
    atlas.targetZoom = atlas.zoom = 1;
    atlas.setLanguage(language);
    atlas.setTrafficActive(window.deyttMapTrafficActive);
    if (window.deyttMapLocations.length) atlas.setAvailableLocations(window.deyttMapLocations);
    if (window.deyttMapUserLocation) {
      const location = window.deyttMapUserLocation;
      atlas.setUserLocation(location.latitude, location.longitude, location.details);
    }
    atlas.start();
    updateZoomPresentation();
    window.deyttReplayHomeEntrance();
    notifyHost("atlas-ready");
  }).catch(function () {
    const status = root.querySelector("[data-atlas-status]");
    if (status) status.textContent = language === "en" ? "globe is temporarily unavailable" : "глобус временно недоступен";
    notifyHost("atlas-error");
  });
}());
