(function () {
  "use strict";

  const DEG = Math.PI / 180;
  const TAU = Math.PI * 2;
  const MAX_ZOOM = 32;
  const SAVE_DATA_PIXEL_RATIO_CAP = 1.5;
  const MOBILE_PIXEL_RATIO_CAP = 3;
  const DESKTOP_PIXEL_RATIO_CAP = 2.25;
  const ANDROID_GEOGRAPHY_PIXEL_RATIO_CAP = 2;
  const ZOOM_EASE_MS = 120;
  const PAN_EASE_MS = 120;
  const NODE_TAP_CORE_RADIUS = 12;
  const LOCATION_ORDER = ["nl", "de", "fi", "ru", "it"];
  const CONNECTED_LABEL_SIDE_PENALTY = 10000;
  const LABEL_OFFSETS = {
    nl: { x: -24, y: -42, align: "right" },
    de: { x: -24, y: 54, align: "right" },
    fi: { x: -22, y: -42, align: "right" },
    it: { x: -24, y: 52, align: "right" },
    ru: { x: 24, y: -42, align: "left" },
    user: { x: 22, y: -38, align: "left" }
  };
  const COMPACT_LABEL_OFFSETS = {
    nl: { x: -20, y: -42, align: "right" },
    de: { x: -20, y: 56, align: "right" },
    fi: { x: -18, y: -82, align: "right" },
    it: { x: -22, y: 54, align: "right" },
    ru: { x: 18, y: -42, align: "left" },
    user: { x: 18, y: -62, align: "left" }
  };
  const LOCATIONS = {
    nl: { code: "nl", city: "Амстердам", country: "Нидерланды", countryId: "528", lat: 52.3676, lon: 4.9041 },
    de: { code: "de", city: "Франкфурт-на-Майне", country: "Германия", countryId: "276", lat: 50.1109, lon: 8.6821 },
    fi: { code: "fi", city: "Хельсинки", country: "Финляндия", countryId: "246", lat: 60.1699, lon: 24.9384 },
    ru: { code: "ru", city: "Санкт-Петербург", country: "Россия", countryId: "643", lat: 59.9311, lon: 30.3609 },
    it: { code: "it", city: "выход", country: "Италия", countryId: "380", lat: 42.5, lon: 12.5 }
  };
  const COUNTRY_EXIT_KEYS = { NL: "nl", DE: "de", FI: "fi", RU: "ru", IT: "it" };
  const ROUTES = {
    auto: {
      title: "автоподбор",
      eyebrow: "два доступных выхода",
      nodes: ["nl", "de", "fi", "ru", "it"],
      links: [],
      steps: [["вы", "устройство"], ["auto", "проверка точек"], ["nl / de / fi / ru / it", "доступные выходы"], ["web", "интернет"]],
      description: "общий адрес выбирает доступный выход из амстердама, франкфурта, хельсинки, санкт-петербурга или италии по состоянию сети."
    },
    nl: {
      title: "нидерланды",
      eyebrow: "прямой маршрут",
      nodes: ["nl"], links: [],
      steps: [["вы", "устройство"], ["nl", "амстердам"], ["web", "интернет"]],
      description: "соединение входит в сеть и выходит в интернет через амстердам."
    },
    ru: {
      title: "россия",
      eyebrow: "прямой маршрут",
      nodes: ["ru"], links: [],
      steps: [["вы", "устройство"], ["ru", "петербург"], ["web", "интернет"]],
      description: "прямой вход и выход через точку в санкт-петербурге."
    },
    de: {
      title: "германия",
      eyebrow: "прямой маршрут",
      nodes: ["de"], links: [],
      steps: [["вы", "устройство"], ["de", "франкфурт"], ["web", "интернет"]],
      description: "соединение входит в сеть и выходит в интернет через франкфурт."
    },
    fi: {
      title: "финляндия",
      eyebrow: "прямой маршрут",
      nodes: ["fi"], links: [],
      steps: [["вы", "устройство"], ["fi", "хельсинки"], ["web", "интернет"]],
      description: "соединение входит в сеть и выходит в интернет через хельсинки."
    },
    it: {
      title: "италия",
      eyebrow: "прямой маршрут",
      nodes: ["it"], links: [],
      steps: [["вы", "устройство"], ["it", "точка в италии"], ["web", "интернет"]],
      description: "соединение входит в сеть и выходит в интернет через точку в италии."
    },
    "ru-de": {
      title: "россия + германия",
      eyebrow: "двойной маршрут",
      nodes: ["ru", "de"], links: [["ru", "de"]],
      steps: [["вы", "устройство"], ["ru", "вход · петербург"], ["de", "выход · франкфурт"], ["web", "интернет"]],
      description: "трафик сначала входит в точку в санкт-петербурге, затем идёт внутри сети во франкфурт и выходит в интернет из германии."
    }
  };
  const EN_COPY = {
    "амстердам": "Amsterdam",
    "нидерланды": "Netherlands",
    "франкфурт": "Frankfurt",
    "франкфурт-на-майне": "Frankfurt am Main",
    "германия": "Germany",
    "хельсинки": "Helsinki",
    "финляндия": "Finland",
    "италия": "Italy",
    "выход": "Exit",
    "санкт-петербург": "Saint Petersburg",
    "россия": "Russia",
    "ваша сеть": "Your network",
    "вход в сеть": "Network entry",
    "примерно по ip": "Approximate by IP",
    "вы": "You",
    "устройство": "Device",
    "интернет": "Internet",
    "доступные выходы": "Available exits",
    "проверка точек": "Checking nodes",
    "вход · петербург": "Entry · Saint Petersburg",
    "выход · франкфурт": "Exit · Frankfurt"
  };
  const EN_ROUTES = {
    auto: {
      title: "Auto-select",
      eyebrow: "available exits",
      steps: [["you", "device"], ["auto", "checking nodes"], ["nl / de / fi / ru / it", "available exits"], ["web", "internet"]],
      description: "The shared entry chooses an available exit in Amsterdam, Frankfurt, Helsinki, Saint Petersburg, or Italy based on network conditions."
    },
    nl: {
      title: "Netherlands",
      eyebrow: "direct route",
      steps: [["you", "device"], ["nl", "Amsterdam"], ["web", "internet"]],
      description: "Traffic enters the network and reaches the internet through Amsterdam."
    },
    ru: {
      title: "Russia",
      eyebrow: "direct route",
      steps: [["you", "device"], ["ru", "Saint Petersburg"], ["web", "internet"]],
      description: "A direct entry and exit through Saint Petersburg."
    },
    de: {
      title: "Germany",
      eyebrow: "direct route",
      steps: [["you", "device"], ["de", "Frankfurt"], ["web", "internet"]],
      description: "Traffic enters the network and reaches the internet through Frankfurt."
    },
    fi: {
      title: "Finland",
      eyebrow: "direct route",
      steps: [["you", "device"], ["fi", "Helsinki"], ["web", "internet"]],
      description: "Traffic enters the network and reaches the internet through Helsinki."
    },
    it: {
      title: "Italy",
      eyebrow: "direct route",
      steps: [["you", "device"], ["it", "exit in Italy"], ["web", "internet"]],
      description: "Traffic enters the network and reaches the internet through an exit in Italy."
    },
    "ru-de": {
      title: "Russia + Germany",
      eyebrow: "double route",
      steps: [["you", "device"], ["ru", "entry · Saint Petersburg"], ["de", "exit · Frankfurt"], ["web", "internet"]],
      description: "Traffic first enters through Saint Petersburg, then travels inside the network to Frankfurt and exits in Germany."
    }
  };

  function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }
  function mix(a, b, amount) { return a + (b - a) * amount; }
  function dot(a, b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
  function vector(lat, lon) {
    const phi = lat * DEG;
    const lambda = lon * DEG;
    return { x: Math.cos(phi) * Math.cos(lambda), y: Math.sin(phi), z: Math.cos(phi) * Math.sin(lambda) };
  }
  function slerp(a, b, amount) {
    const omega = Math.acos(clamp(dot(a, b), -1, 1));
    if (omega < 0.0001) return a;
    const sine = Math.sin(omega);
    const first = Math.sin((1 - amount) * omega) / sine;
    const second = Math.sin(amount * omega) / sine;
    return { x: a.x * first + b.x * second, y: a.y * first + b.y * second, z: a.z * first + b.z * second };
  }
  function normalize(point) {
    const length = Math.hypot(point.x, point.y, point.z) || 1;
    return { x: point.x / length, y: point.y / length, z: point.z / length };
  }
  function locationFromVector(point) {
    return { lat: Math.asin(clamp(point.y, -1, 1)) / DEG, lon: Math.atan2(point.z, point.x) / DEG };
  }
  function nearestLongitude(from, target) {
    return from + ((((target - from) + 540) % 360) - 180);
  }

  function decodeTopology(topology) {
    const scale = topology.transform.scale;
    const translate = topology.transform.translate;
    const decoded = topology.arcs.map(function (arc) {
      let x = 0;
      let y = 0;
      return arc.map(function (delta) {
        x += delta[0];
        y += delta[1];
        return [x * scale[0] + translate[0], y * scale[1] + translate[1]];
      });
    });

    function resolve(index) {
      const points = decoded[index < 0 ? ~index : index];
      return index < 0 ? points.slice().reverse() : points;
    }
    function ring(arcIndexes) {
      const result = [];
      arcIndexes.forEach(function (index, arcIndex) {
        const points = resolve(index);
        points.forEach(function (point, pointIndex) {
          if (arcIndex && pointIndex === 0) return;
          result.push(point);
        });
      });
      return result;
    }

    function unwrapRing(ring) {
      if (!ring.length) return ring;
      let previous = ring[0][0];
      return ring.map(function (point, index) {
        if (!index) return [point[0], point[1]];
        let longitude = point[0];
        while (longitude - previous > 180) longitude -= 360;
        while (longitude - previous < -180) longitude += 360;
        previous = longitude;
        return [longitude, point[1]];
      });
    }
    function polygonRecord(rings) {
      const geographicRings = rings.map(unwrapRing);
      return {
        rings: geographicRings,
        vectors: geographicRings.map(function (points) {
          return points.map(function (point) { return vector(point[1], point[0]); });
        })
      };
    }
    function geometryPolygons(geometry) {
      const groups = geometry.type === "Polygon" ? [geometry.arcs] : geometry.arcs;
      return groups.map(function (polygon) { return polygonRecord(polygon.map(ring)); });
    }
    function boundaryLine(points) {
      return points.map(function (point) {
        return { lat: point[1], lon: point[0], vector: vector(point[1], point[0]) };
      });
    }

    const countries = topology.objects.countries.geometries.map(function (geometry) {
      return {
        id: String(geometry.id).padStart(3, "0"),
        name: geometry.properties && geometry.properties.name,
        polygons: geometryPolygons(geometry)
      };
    });
    const targetIds = new Set(LOCATION_ORDER.map(function (key) { return LOCATIONS[key].countryId; }));
    const targetCountries = countries.filter(function (country) { return targetIds.has(country.id); });
    const targetBoundaryLines = targetCountries.flatMap(function (country) {
      return country.polygons.flatMap(function (polygon) {
        return polygon.rings.map(function (points) {
          return { countryId: country.id, points: boundaryLine(points) };
        });
      });
    });
    return {
      boundaryLines: decoded.map(boundaryLine),
      targetBoundaryLines: targetBoundaryLines,
      landPolygons: countries.flatMap(function (country) {
        return country.polygons.map(function (polygon) { return { rings: polygon.vectors }; });
      })
    };
  }

  class NetworkAtlas {
    constructor(root, options) {
      this.root = root;
      this.canvas = root.querySelector("canvas");
      this.context = this.canvas.getContext("2d");
      this.tooltip = root.querySelector("[data-atlas-tooltip]");
      this.options = options || {};
      this.wheelZoomOnly = Boolean(this.options.wheelZoomOnly);
      this.variant = this.options.variant || "home";
      this.selectOnTap = this.options.selectOnTap !== false;
      this.root.dataset.atlasVariant = this.variant;
      this.route = "auto";
      this.language = "ru";
      this.userLocation = null;
      this.autoExitCountry = null;
      this.autoExitRouteKey = null;
      this.labelPlacements = new Map();
      this.routeInitialized = false;
      this.lastFocusSignature = null;
      this.availableLocationKeys = new Set(LOCATION_ORDER);
      this.showcaseFocused = false;
      this.overviewMode = false;
      this.connected = false;
      this.hovered = null;
      this.annotationFilter = null;
      this.dragging = false;
      this.activePointers = new Map();
      this.gesturePointers = new Set();
      this.gestureStartedInsideGlobe = false;
      this.pinchDistance = 0;
      this.centerLon = Number.isFinite(this.options.initialLon) ? this.options.initialLon : 15;
      this.centerLat = Number.isFinite(this.options.initialLat) ? this.options.initialLat : 50;
      this.targetLon = this.centerLon;
      this.targetLat = this.centerLat;
      this.zoom = this.options.initialZoom || 1.1;
      this.targetZoom = this.zoom;
      this.lastPointer = { x: 0, y: 0 };
      this.velocityLon = 0;
      this.width = 0;
      this.height = 0;
      this.centerX = 0;
      this.centerY = 0;
      this.viewportAnchor = null;
      this.radius = 0;
      this.lastFrame = 0;
      this.frame = 0;
      this.visible = true;
      this.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
      this.boundaryLines = [];
      this.targetBoundaryLines = [];
      this.landPolygons = [];
      this.pixelRatio = 1;
      this.staticCanvas = document.createElement("canvas");
      this.staticContext = this.staticCanvas.getContext("2d");
      this.routeOverlayCanvas = document.createElement("canvas");
      this.routeOverlayContext = this.routeOverlayCanvas.getContext("2d");
      this.foregroundOverlayCanvas = document.createElement("canvas");
      this.foregroundOverlayContext = this.foregroundOverlayCanvas.getContext("2d");
      this.overlayCacheSignature = "";
      this.overlayCacheBuildCount = 0;
      this.cachedRouteLinks = [];
      this.staticDirty = true;
      this.renderCount = 0;
      this.staticRenderCount = 0;
      this.cameraMoving = false;
      this.trafficActive = false;
      this.trafficStartedAt = 0;
      this.selectedTrafficFlow = null;
      this.routeHitSegments = [];
      this.pointerDown = null;
      this.suppressNextClick = false;
      this.projectedNodes = {};
      this.routeSegments = [];
      this.bind();
    }

    async init() {
      let response;
      let lastError;
      for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
          response = await fetch(this.options.topologyUrl, { cache: "force-cache", credentials: "omit" });
          if (!response.ok) throw new Error("geography unavailable: " + response.status);
          break;
        } catch (error) {
          lastError = error;
          if (attempt < 2) await new Promise((resolve) => setTimeout(resolve, 180 * (attempt + 1)));
        }
      }
      if (!response || !response.ok) throw lastError || new Error("geography unavailable");
      const topology = await response.json();
      const geography = decodeTopology(topology);
      this.boundaryLines = geography.boundaryLines;
      this.targetBoundaryLines = geography.targetBoundaryLines;
      this.landPolygons = geography.landPolygons;
      this.root.dataset.atlasStatus = "ready";
      this.root.classList.add("is-ready");
      this.resize();
      this.setRoute(this.options.route || "auto", false);
      this.render(performance.now());
      this.root.dispatchEvent(new CustomEvent("deytt:atlas-ready", { detail: { atlas: this } }));
      return this;
    }

    bind() {
      this.canvas.addEventListener("pointerdown", (event) => {
        if (this.gesturePointers.size === 0) {
          this.gestureStartedInsideGlobe = this.isInsideVisibleGlobe(event.clientX, event.clientY);
        }
        this.gesturePointers.add(event.pointerId);
        if (!this.gestureStartedInsideGlobe) return;
        this.pointerDown = { x: event.clientX, y: event.clientY };
        this.suppressNextClick = false;
        this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        this.dragging = true;
        this.velocityLon = 0;
        this.lastPointer = { x: event.clientX, y: event.clientY };
        this.canvas.setPointerCapture(event.pointerId);
        this.root.classList.add("is-dragging");
        if (this.activePointers.size === 2) {
          const points = Array.from(this.activePointers.values());
          this.pinchDistance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
        }
      });
      this.canvas.addEventListener("pointermove", (event) => {
        if (this.gesturePointers.has(event.pointerId) && !this.gestureStartedInsideGlobe) return;
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
        if (this.activePointers.has(event.pointerId)) this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (this.activePointers.size >= 2) {
          const points = Array.from(this.activePointers.values()).slice(0, 2);
          const distance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
          if (!this.wheelZoomOnly && this.pinchDistance > 0 && distance > 0) {
            this.targetZoom = this.clampZoom(this.targetZoom * distance / this.pinchDistance);
            this.staticDirty = true;
          }
          this.pinchDistance = distance;
          this.start();
          return;
        }
        if (this.dragging) {
          if (this.pointerDown && Math.hypot(event.clientX - this.pointerDown.x, event.clientY - this.pointerDown.y) > 6)
            this.suppressNextClick = true;
          const dx = event.clientX - this.lastPointer.x;
          const dy = event.clientY - this.lastPointer.y;
          // Use the intended zoom level so drag speed stays consistent while a
          // wheel animation is still settling.
          const dragScale = 1 / Math.max(1, this.targetZoom);
          this.targetLon -= dx * 0.28 * dragScale;
          this.targetLat = clamp(this.targetLat + dy * 0.2 * dragScale, -62, 72);
          this.velocityLon = -dx * 0.018 * dragScale;
          this.lastPointer = { x: event.clientX, y: event.clientY };
        } else if (event.pointerType !== "touch") {
          this.updateHover(pointer);
        }
        this.start();
      });
      this.canvas.addEventListener("wheel", (event) => {
        if (!this.isInsideVisibleGlobe(event.clientX, event.clientY)) return;
        event.preventDefault();
        const delta = event.deltaMode === 1 ? event.deltaY * 18 : event.deltaY;
        // Normalize large mouse-wheel bursts so the camera never jumps between frames.
        const normalizedDelta = clamp(delta, -160, 160);
        this.targetZoom = this.clampZoom(this.targetZoom * Math.exp(-normalizedDelta * .00135));
        this.staticDirty = true;
        this.start();
      }, { passive: false });
      const release = (event) => {
        if (!this.gesturePointers.has(event.pointerId)) return;
        this.gesturePointers.delete(event.pointerId);
        if (event.type === "pointercancel") this.suppressNextClick = false;
        if (!this.gestureStartedInsideGlobe) {
          if (this.gesturePointers.size === 0) this.gestureStartedInsideGlobe = false;
          return;
        }
        this.activePointers.delete(event.pointerId);
        this.pinchDistance = 0;
        this.dragging = this.activePointers.size > 0;
        const remaining = this.activePointers.values().next().value;
        if (remaining) this.lastPointer = remaining;
        else this.root.classList.remove("is-dragging");
        if (event.pointerType === "touch") this.setHovered(null);
        if (this.gesturePointers.size === 0) this.gestureStartedInsideGlobe = false;
        this.start();
      };
      // An outside-start mouse drag is intentionally not captured, so finish
      // its bookkeeping at document level if the pointer is released off-canvas.
      document.addEventListener("pointerup", release);
      document.addEventListener("pointercancel", release);
      this.canvas.addEventListener("pointerleave", () => {
        if (!this.dragging) this.setHovered(null);
      });
      this.canvas.addEventListener("click", (event) => {
        if (this.suppressNextClick) { this.suppressNextClick = false; return; }
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
        this.updateHover(pointer);
        const flow = this.findTrafficLineAt(pointer, 14);
        const node = this.projectedNodes[this.hovered];
        // Short links can lie entirely inside the nodes' generous touch padding.
        if (flow && (!node || Math.hypot(pointer.x - node.x, pointer.y - node.y) > NODE_TAP_CORE_RADIUS)) {
          try { window.DeyttAtlasBridge?.onTrafficTap?.(flow); } catch (_) { /* Native view may be closing. */ }
          return;
        }
        if (this.hovered && typeof window.deyttMapNodeTapped === "function") {
          window.deyttMapNodeTapped(this.hovered);
          return;
        }
        if (!this.selectOnTap || !this.hovered || this.hovered === "user") return;
        this.setRoute(this.hovered === "ru" && this.route === "de" ? "ru-de" : this.hovered);
      });
      this.canvas.addEventListener("keydown", (event) => {
        if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "+", "=", "-", "_", "0"].includes(event.key)) return;
        if (this.wheelZoomOnly && ["+", "=", "-", "_", "0"].includes(event.key)) {
          event.preventDefault();
          return;
        }
        event.preventDefault();
        if (event.key === "ArrowLeft") this.targetLon -= 9;
        if (event.key === "ArrowRight") this.targetLon += 9;
        if (event.key === "ArrowUp") this.targetLat = clamp(this.targetLat + 7, -62, 72);
        if (event.key === "ArrowDown") this.targetLat = clamp(this.targetLat - 7, -62, 72);
        if (event.key === "+" || event.key === "=") this.targetZoom = this.clampZoom(this.targetZoom * 1.2);
        if (event.key === "-" || event.key === "_") this.targetZoom = this.clampZoom(this.targetZoom / 1.2);
        if (event.key === "0") this.targetZoom = this.clampZoom(1);
        this.staticDirty = true;
        this.start();
      });
      new ResizeObserver(() => this.resize()).observe(this.canvas);
      new IntersectionObserver((entries) => {
        this.visible = entries[0].isIntersecting;
        if (this.visible) this.start();
      }, { rootMargin: "180px 0px" }).observe(this.canvas);
      new MutationObserver(() => {
        this.staticDirty = true;
        this.start();
      }).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
      this.reducedMotion.addEventListener("change", () => {
        this.staticDirty = true;
        this.start();
      });
      document.addEventListener("visibilitychange", () => {
        if (document.hidden && this.frame) {
          cancelAnimationFrame(this.frame);
          this.frame = 0;
        } else if (!document.hidden) {
          this.lastFrame = 0;
          this.start();
        }
      });
    }

    resize() {
      const rectangle = this.canvas.getBoundingClientRect();
      const previousWidth = this.width;
      const previousHeight = this.height;
      const pixelRatioCap = this.options.pixelRatioCap ||
        (navigator.connection && navigator.connection.saveData
          ? SAVE_DATA_PIXEL_RATIO_CAP
          : rectangle.width < 520 ? MOBILE_PIXEL_RATIO_CAP : DESKTOP_PIXEL_RATIO_CAP);
      const pixelRatioFloor = Math.min(this.options.pixelRatioFloor || 1, pixelRatioCap);
      const ratio = Math.min(Math.max(window.devicePixelRatio || 1, pixelRatioFloor), pixelRatioCap);
      this.pixelRatio = ratio;
      this.width = Math.max(1, Math.round(rectangle.width));
      this.height = Math.max(1, Math.round(rectangle.height));
      if ((!window.DeyttAtlasBridge && (this.width !== previousWidth || this.height !== previousHeight)) ||
          (this.width < 520) !== (previousWidth < 520)) this.labelPlacements.clear();
      this.canvas.width = Math.round(this.width * ratio);
      this.canvas.height = Math.round(this.height * ratio);
      this.context.setTransform(ratio, 0, 0, ratio, 0, 0);
      // Keep text at device resolution while limiting geography raster work on Android.
      const geographyRatio = window.DeyttAtlasBridge ? Math.min(ratio, ANDROID_GEOGRAPHY_PIXEL_RATIO_CAP) : ratio;
      this.staticCanvas.width = Math.round(this.width * geographyRatio);
      this.staticCanvas.height = Math.round(this.height * geographyRatio);
      this.staticContext.setTransform(geographyRatio, 0, 0, geographyRatio, 0, 0);
      [this.routeOverlayCanvas, this.foregroundOverlayCanvas].forEach((canvas) => {
        canvas.width = this.canvas.width;
        canvas.height = this.canvas.height;
      });
      this.routeOverlayContext.setTransform(ratio, 0, 0, ratio, 0, 0);
      this.foregroundOverlayContext.setTransform(ratio, 0, 0, ratio, 0, 0);
      const anchor = this.viewportAnchor;
      this.centerX = anchor ? anchor.x + anchor.width / 2 : this.width / 2;
      this.centerY = anchor ? anchor.y + anchor.height / 2 : this.height / 2;
      this.radius = Math.min(anchor ? anchor.width : this.width, anchor ? anchor.height : this.height) * (this.width < 520 ? 0.405 : 0.43);
      this.staticDirty = true;
      this.start();
    }

    setViewportAnchor(x, y, width, height) {
      if (![x, y, width, height].every(Number.isFinite) || width <= 0 || height <= 0) return;
      this.viewportAnchor = { x, y, width, height };
      this.resize();
    }

    isInsideVisibleGlobe(clientX, clientY) {
      if (window.DeyttAtlasBridge) return true;
      const box = this.canvas.getBoundingClientRect();
      const radius = Math.max(0, this.radius * this.zoom - .5);
      return Math.hypot(clientX - box.left - box.width / 2, clientY - box.top - box.height / 2) <= radius;
    }

    basis() {
      const phi = this.centerLat * DEG;
      const lambda = this.centerLon * DEG;
      return {
        east: { x: -Math.sin(lambda), y: 0, z: Math.cos(lambda) },
        north: { x: -Math.sin(phi) * Math.cos(lambda), y: Math.cos(phi), z: -Math.sin(phi) * Math.sin(lambda) },
        forward: { x: Math.cos(phi) * Math.cos(lambda), y: Math.sin(phi), z: Math.cos(phi) * Math.sin(lambda) }
      };
    }

    projectVector(point, lift) {
      const basis = this.currentBasis;
      const scale = this.radius * this.zoom * (lift || 1);
      return {
        x: this.centerX + dot(point, basis.east) * scale,
        y: this.centerY - dot(point, basis.north) * scale,
        z: dot(point, basis.forward),
        lift: lift || 1
      };
    }

    projectLocation(location, lift) { return this.projectVector(vector(location.lat, location.lon), lift); }

    clampZoom(value) { return clamp(value, .82, MAX_ZOOM); }

    colors() {
      const windowsPalette = document.documentElement.dataset.atlasPalette === "windows";
      const windowsDark = document.documentElement.dataset.theme !== "light";
      if (windowsPalette) return windowsDark ? {
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
        grid: null, land: "#1299DC", landDim: "#0D86C7", landHot: "#22A6ED", landAlpha: 1,
        landHighlight: "#22A6ED", landRelief: "rgba(18,55,95,.22)", landLow: "#0974AC",
        border: "#BAE6FD", borderAlpha: .60, borderWidth: .8, borderHot: "#10A889", borderGlow: "rgba(16,168,137,.22)", route: "#D99000", routeHot: "#F0A900",
        download: "#D99000", downloadHot: "#FFC857", upload: "#10A889", uploadHot: "#79D6C1",
        control: "#6E5BB4", controlHot: "#A58CEB",
        node: "#FFFFFF", nodeCore: "#123A67", label: "#1B2938", labelMuted: "#4D6175", labelBg: "rgba(246,247,251,.96)", labelLeader: "#354B60", labelBorder: "rgba(53,75,96,.22)"
      };
      const observatory = this.variant === "observatory";
      if (observatory) return {
        sphereLight: "#101832", sphereMid: "#0a1122", sphereShade: "#060a15", sphereEdge: "rgba(129,226,255,.42)", atmosphere: "rgba(99,126,255,.20)", atmosphereSoft: "rgba(99,126,255,.1)", rimLight: "rgba(129,226,255,.4)",
        grid: null, land: "rgba(196,212,248,.28)", landDim: "rgba(150,166,212,.30)", landHot: "#7fe0ff",
        border: "rgba(186,203,248,.20)", borderHot: "#7fe0ff", borderGlow: "rgba(129,226,255,.78)", route: "#5d6eff", routeHot: "#7fe0ff",
        download: "#ff62b0", downloadHot: "#ffd166", upload: "#63dfb3", uploadHot: "#b8f6dc",
        node: "#f6fbff", nodeCore: "#63dfb3", label: "#f7f9ff", labelMuted: "#9daac4", labelBg: "rgba(8,13,27,.91)"
      };
      const dark = document.documentElement.dataset.theme === "dark";
      return dark ? {
        sphereLight: "#101827", sphereMid: "#0b121d", sphereShade: "#070c13", sphereEdge: "rgba(140,163,255,.38)", atmosphere: "rgba(104,130,255,.16)", atmosphereSoft: "rgba(104,130,255,.07)", rimLight: "rgba(160,180,255,.26)",
        grid: "rgba(143,166,220,.08)", land: "rgba(178,199,250,.48)", landDim: "rgba(131,155,212,.25)", landHot: "rgba(122,232,255,1)",
        border: "rgba(168,191,242,.26)", borderHot: "rgba(133,234,255,.88)", borderGlow: "rgba(133,234,255,.58)", route: "#397f94", routeHot: "#8de8e4",
        download: "#f04d9e", downloadHot: "#ffc857", upload: "#73e0b5", uploadHot: "#c2f7df",
        node: "#f7f9ff", nodeCore: "#73e0b5", label: "#f5f7ff", labelMuted: "#9daac4", labelBg: "rgba(8,12,19,.94)"
      } : {
        /* Light theme: a saturated blue marble. The light panel needs contrast,
         * not another pale surface — deep ocean, crisp white coastlines and
         * bright traffic read far better than light-blue lines on near-white. */
        sphereLight: "#5d78ea", sphereMid: "#2c41b4", sphereShade: "#131f5e", sphereEdge: "rgba(168,190,255,.55)", atmosphere: "rgba(90,110,246,.4)", atmosphereSoft: "rgba(99,126,255,.16)", rimLight: "rgba(255,255,255,.85)",
        grid: "rgba(255,255,255,.13)", land: "rgba(255,255,255,.92)", landDim: "rgba(214,227,255,.55)", landHot: "rgba(110,231,255,1)",
        border: "rgba(255,255,255,.92)", borderAlpha: .52, borderHot: "rgba(140,240,255,.95)", borderGlow: "rgba(110,231,255,.72)", route: "#8ea4ff", routeHot: "#67e8f9",
        download: "#ff5fa8", downloadHot: "#ffc857", upload: "#34d399", uploadHot: "#a7f3d0",
        node: "#ffffff", nodeCore: "#34d399", label: "#f5f7ff", labelMuted: "rgba(224,234,255,.82)", labelBg: "rgba(13,20,48,.88)"
      };
    }

    drawSphere(colors) {
      const context = this.context;
      const x = this.centerX;
      const y = this.centerY;
      const radius = this.radius * this.zoom;

      /* Outer atmosphere: a wide luminous halo that breathes slightly while
       * the camera moves, drawn behind the sphere so land and routes stay
       * untouched. */
      const haloScale = this.cameraMoving ? 1.5 : 1.34;
      const halo = context.createRadialGradient(x, y, radius * .96, x, y, radius * haloScale);
      halo.addColorStop(0, colors.atmosphere);
      halo.addColorStop(.4, colors.atmosphereSoft);
      halo.addColorStop(1, "rgba(0,0,0,0)");
      context.save();
      context.globalCompositeOperation = "lighter";
      context.fillStyle = halo;
      context.beginPath();
      context.arc(x, y, radius * haloScale, 0, TAU);
      context.fill();
      context.restore();

      const gradient = context.createRadialGradient(x - radius * .36, y - radius * .42, radius * .03, x + radius * .12, y + radius * .16, radius * 1.14);
      gradient.addColorStop(0, colors.sphereLight);
      gradient.addColorStop(.55, colors.sphereLight);
      gradient.addColorStop(.86, colors.sphereMid);
      gradient.addColorStop(1, colors.sphereShade);
      context.save();
      context.beginPath();
      context.arc(x, y, radius, 0, TAU);
      context.clip();
      context.fillStyle = gradient;
      context.fill();
      /* Lit limb: two short bright arcs on the light side only — left-top
       * and right-bottom of the light source — instead of a full outline. */
      context.save();
      context.lineWidth = Math.max(1.5, radius * 0.012);
      context.lineCap = "round";
      context.strokeStyle = colors.rimLight;
      context.shadowColor = colors.rimLight;
      context.shadowBlur = radius * 0.035;
      const rimArc = (startAngle, endAngle, alpha) => {
        context.save();
        context.globalAlpha = alpha;
        context.beginPath();
        context.arc(x, y, radius - context.lineWidth * 0.6 + 0.5, startAngle, endAngle);
        context.stroke();
        context.restore();
      };
      rimArc(Math.PI * 0.98, Math.PI * 1.52, 0.5);
      rimArc(Math.PI * 0.06, Math.PI * 0.4, 0.28);
      context.restore();
      context.restore();

      context.strokeStyle = colors.sphereEdge;
      context.lineWidth = 1;
      context.beginPath();
      context.arc(x, y, radius, 0, TAU);
      context.stroke();
      const gloss = context.createRadialGradient(x - radius * .42, y - radius * .48, 0, x - radius * .42, y - radius * .48, radius * .82);
      gloss.addColorStop(0, "rgba(255,255,255,.16)");
      gloss.addColorStop(.44, "rgba(255,255,255,.03)");
      gloss.addColorStop(1, "rgba(255,255,255,0)");
      context.fillStyle = gloss;
      context.beginPath();
      context.arc(x, y, radius, 0, TAU);
      context.fill();
    }

    drawLand(colors) {
      const context = this.context;
      if (!this.landPolygons.length) return;
      const horizon = 0.012;
      const basis = this.currentBasis;
      const east = basis.east;
      const north = basis.north;
      const fwd = basis.forward;
      const scale = this.radius * this.zoom * 1.001;
      const cx = this.centerX;
      const cy = this.centerY;
      const globeX = cx, globeY = cy;
      const globeRadius = this.radius * this.zoom;

      const findHorizon = (from, to) => {
        const zFrom = from.x * fwd.x + from.y * fwd.y + from.z * fwd.z;
        const zTo = to.x * fwd.x + to.y * fwd.y + to.z * fwd.z;
        const denom = zTo - zFrom;
        const t = Math.abs(denom) > 1e-6 ? clamp((horizon - zFrom) / denom, 0, 1) : 0.5;
        const px = from.x + t * (to.x - from.x);
        const py = from.y + t * (to.y - from.y);
        const pz = from.z + t * (to.z - from.z);
        const len = Math.hypot(px, py, pz) || 1;
        const nx = px / len, ny = py / len, nz = pz / len;
        return {
          x: cx + (nx * east.x + ny * east.y + nz * east.z) * scale,
          y: cy - (nx * north.x + ny * north.y + nz * north.z) * scale,
          z: horizon
        };
      };

      if (!this.projectedRingScratch) this.projectedRingScratch = [];
      const scratch = this.projectedRingScratch;

      const pathForRing = (ring) => {
        const len = ring.length;
        if (len < 3) return [];
        while (scratch.length < len) scratch.push({ x: 0, y: 0, z: 0 });
        let firstHidden = -1;
        let visibleCount = 0;
        for (let i = 0; i < len; i++) {
          const pt = ring[i];
          const z = pt.x * fwd.x + pt.y * fwd.y + pt.z * fwd.z;
          scratch[i].z = z;
          if (z <= horizon) {
            if (firstHidden < 0) firstHidden = i;
          } else {
            visibleCount++;
            scratch[i].x = cx + (pt.x * east.x + pt.y * east.y + pt.z * east.z) * scale;
            scratch[i].y = cy - (pt.x * north.x + pt.y * north.y + pt.z * north.z) * scale;
          }
        }
        if (visibleCount === 0) return [];
        if (firstHidden < 0) return [scratch.slice(0, len)];
        const segments = [];
        let segment = [];
        for (let step = 0; step < len; step += 1) {
          const index = (firstHidden + step) % len;
          const nextIndex = (index + 1) % len;
          const current = scratch[index];
          const next = scratch[nextIndex];
          const currentVisible = current.z > horizon;
          const nextVisible = next.z > horizon;
          if (currentVisible && nextVisible) {
            if (!segment.length) segment.push(current);
            segment.push(next);
          } else if (currentVisible && !nextVisible) {
            if (!segment.length) segment.push(current);
            segment.push(findHorizon(ring[index], ring[nextIndex]));
            if (segment.length > 2) segments.push(segment);
            segment = [];
          } else if (!currentVisible && nextVisible) {
            segment = [findHorizon(ring[index], ring[nextIndex]), next];
          }
        }
        if (segment.length > 2) segments.push(segment);
        return segments;
      };

      const landPath = new Path2D();
      const traceClippedRing = (segment, globeX, globeY) => {
        const first = segment[0];
        const last = segment[segment.length - 1];
        landPath.moveTo(first.x, first.y);
        for (let index = 1; index < segment.length; index += 1)
          landPath.lineTo(segment[index].x, segment[index].y);

        if (first.z > horizon + .05 || last.z > horizon + .05) {
          landPath.closePath();
          return;
        }

        // A clipped land boundary ends on the globe horizon. Closing it with
        // one straight chord cuts a visible wedge across the sphere. Follow
        // the shorter horizon arc instead so the clipped polygon stays on the
        // globe's silhouette.
        const globeRadius = this.radius * this.zoom * 1.001;
        const startAngle = Math.atan2(first.y - globeY, first.x - globeX);
        const endAngle = Math.atan2(last.y - globeY, last.x - globeX);
        const arc = Math.atan2(Math.sin(startAngle - endAngle), Math.cos(startAngle - endAngle));
        landPath.arc(globeX, globeY, globeRadius, endAngle, endAngle + arc, arc < 0);
        landPath.closePath();
      };

      const landLight = context.createLinearGradient(
        globeX - globeRadius * .62, globeY - globeRadius * .68,
        globeX + globeRadius * .58, globeY + globeRadius * .72);
      landLight.addColorStop(0, colors.landHighlight || colors.land);
      landLight.addColorStop(.22, colors.land);
      landLight.addColorStop(.68, colors.landDim);
      landLight.addColorStop(1, colors.landLow || colors.landDim);
      const landEdge = context.createLinearGradient(
        globeX - globeRadius * .72, globeY - globeRadius * .7,
        globeX + globeRadius * .62, globeY + globeRadius * .74);
      landEdge.addColorStop(0, colors.landHighlight || colors.land);
      landEdge.addColorStop(.52, colors.landDim);
      landEdge.addColorStop(1, colors.landRelief || colors.landLow || colors.landDim);
      const traceLand = () => {
        this.landPolygons.forEach((polygon) => polygon.rings.forEach((ring) => {
          pathForRing(ring).forEach((segment) => traceClippedRing(segment, globeX, globeY));
        }));
      };
      // Zoom and native viewport changes only transform the same projected coastline.
      const cached = this.landPathCache;
      if (cached && cached.lon === this.centerLon && cached.lat === this.centerLat && cached.polygons === this.landPolygons) {
        landPath.addPath(cached.path, new DOMMatrix().translate(cx, cy).scale(scale / cached.scale).translate(-cached.cx, -cached.cy));
      } else {
        traceLand();
        this.landPathCache = { path: landPath, lon: this.centerLon, lat: this.centerLat, polygons: this.landPolygons, scale, cx, cy };
      }
      context.save();
      const isLight = document.documentElement.dataset.theme === "light";
      const relief = this.options.landDepth === false && !isLight ? 0 : (isLight ? 1.5 : clamp(globeRadius * .014, 2, 5));
      if (relief) {
        context.save();
        const shadowOffsetX = isLight ? 0.6 : (relief * .24);
        const shadowOffsetY = isLight ? 1.4 : (relief * .56);
        context.translate(shadowOffsetX, shadowOffsetY);
        context.fillStyle = colors.landRelief || (isLight ? "rgba(18,55,95,.22)" : "rgba(12,22,34,.64)");
        context.globalAlpha = isLight ? .50 : .68;
        context.fill(landPath, "evenodd");
        context.restore();
      }

      context.fillStyle = landLight;
      context.globalAlpha = colors.landAlpha ?? .98;
      context.shadowColor = "transparent";
      context.shadowBlur = 0;
      context.fill(landPath, "evenodd");
      context.shadowColor = "transparent";
      context.shadowBlur = 0;
      context.shadowOffsetX = 0;
      context.shadowOffsetY = 0;
      context.globalAlpha = isLight ? .45 : (relief ? .48 : .20);
      context.lineWidth = isLight ? 0.8 : clamp(globeRadius * .0032, .8, 1.3);
      context.strokeStyle = landEdge;
      context.stroke(landPath);
      context.restore();
    }

    drawBorders(colors) {
      const context = this.context;
      this.drawLand(colors);
      const trace = (points, lift) => {
        let drawing = false;
        let previous = null;
        points.forEach((border) => {
          const projected = this.projectVector(border.vector, lift);
          const jump = previous && Math.hypot(projected.x - previous.x, projected.y - previous.y) > this.radius * this.zoom * .24;
          if (projected.z <= .012 || jump) {
            drawing = false;
            previous = projected;
            return;
          }
          if (!drawing) context.moveTo(projected.x, projected.y);
          else context.lineTo(projected.x, projected.y);
          drawing = true;
          previous = projected;
        });
      };
      const strokeLines = (lines, style, width, alpha, shadowColor, shadowBlur) => {
        if (!lines.length) return;
        context.save();
        context.lineCap = "round";
        context.lineJoin = "round";
        context.strokeStyle = style;
        context.lineWidth = width;
        context.globalAlpha = alpha;
        context.shadowColor = shadowColor || "transparent";
        context.shadowBlur = shadowBlur || 0;
        context.beginPath();
        lines.forEach((line) => trace(line.points || line, 1.002));
        context.stroke();
        context.restore();
      };
      const borderAlpha = colors.borderAlpha || (this.variant === "observatory" ? .38 : .28);
      strokeLines(this.boundaryLines, colors.border, this.width < 520 ? .48 : .62, borderAlpha);
      if (this.variant !== "observatory") return;
      const route = ROUTES[this.route] || ROUTES.auto;
      const activeKeys = this.annotationFilter ? Array.from(this.annotationFilter) : route.nodes;
      const activeCountryIds = new Set(activeKeys.map((key) => LOCATIONS[key] && LOCATIONS[key].countryId).filter(Boolean));
      const activeLines = this.targetBoundaryLines.filter((line) => activeCountryIds.has(line.countryId));
      // A diffused pass gives the active countries a little depth; the crisp
      // pass keeps borders legible at the close-up measurement zoom.
      strokeLines(activeLines, colors.borderGlow, this.width < 520 ? 2.6 : 3.4, .24, colors.borderGlow, this.cameraMoving ? 4 : 9);
      strokeLines(activeLines, colors.borderHot, this.width < 520 ? .82 : 1.05, .72);
    }

    drawRoutes(colors, time, drawPackets = true) {
      this.routeHitSegments = [];
      const links = this.routeLinks();
      this.drawTrafficLines(links);
      if (drawPackets && this.trafficActive) this.drawTraffic(links, time || performance.now());
      return links;
    }

    routeLinks() {
      const activeRoute = this.activeRouteDefinition();
      const links = (activeRoute ? activeRoute.links : []).filter((link) =>
        link.every((key) => this.availableLocationKeys.has(key))
      );
      const firstHop = this.activeRouteNodeKeys().find((key) => this.availableLocationKeys.has(key));
      if (this.userLocation && firstHop) links.unshift(["user", firstHop]);
      return links;
    }

    drawTrafficLines(links) {
      const context = this.context;
      const dark = document.documentElement.dataset.theme === "dark";
      this.trafficStreams().filter(stream => this.selectedTrafficFlow == null || stream.id === this.selectedTrafficFlow).forEach(stream => {
        const directed = stream.reverse ? links.slice().reverse().map(link => [link[1], link[0]]) : links;
        directed.forEach(([fromKey, toKey]) => {
          const points = [];
          for (let i = 0; i <= 80; i++) points.push(this.projectLanePoint(fromKey, toKey, i / 80, stream.lane));
          const emphasized = this.selectedTrafficFlow === stream.id;
          context.save(); context.beginPath();
          points.forEach((point, i) => { if (point.z <= -.02) return; if (!i || points[i - 1].z <= -.02) context.moveTo(point.x, point.y); else context.lineTo(point.x, point.y); });
          context.lineCap = context.lineJoin = "round";
          context.strokeStyle = stream.color; context.globalAlpha = emphasized ? .24 : dark ? .18 : .13;
          context.lineWidth = emphasized ? 7 : 5.8; context.shadowBlur = 0; context.stroke();
          const sheen = context.createLinearGradient(points[0].x, points[0].y, points[80].x, points[80].y);
          sheen.addColorStop(0, stream.color); sheen.addColorStop(.22, stream.color); sheen.addColorStop(.5, stream.bright);
          sheen.addColorStop(.78, stream.color); sheen.addColorStop(1, stream.color);
          context.strokeStyle = sheen; context.globalAlpha = 1; context.lineWidth = emphasized ? 3.1 : 2.3; context.stroke();
          if (emphasized) { context.globalAlpha = .9; context.strokeStyle = "#FFFFFF"; context.lineWidth = .75; context.shadowBlur = 0; context.stroke(); }
          context.restore();
          for (let i = 1; i < points.length; i++) {
            const a = points[i - 1], b = points[i];
            if (a.z <= -.02 || b.z <= -.02) continue;
            this.routeHitSegments.push({ stream: stream.id, a, b });
            this.routeSegments.push({ left: Math.min(a.x, b.x), top: Math.min(a.y, b.y),
              right: Math.max(a.x, b.x), bottom: Math.max(a.y, b.y) });
          }
        });
      });
    }

    drawTraffic(links, time) {

      if (!links.length) {
        // Without the location opt-in there is no truthful origin point for a
        // route line. Pulse the confirmed exit instead of inventing a source.
        const exit = this.activeRouteNodeKeys().find((key) => this.availableLocationKeys.has(key));
        if (!exit) return;
        const point = this.projectLocation(LOCATIONS[exit], 1.018);
        if (point.z <= .015) return;
        const context = this.context;
        this.trafficStreams().forEach((stream, index) => {
          if (this.selectedTrafficFlow != null && stream.id !== this.selectedTrafficFlow) return;
          const phase = this.reducedMotion.matches ? .5 : ((time - this.trafficStartedAt) / (stream.duration * 2) + stream.phase) % 1;
          const orbit = 9 + index * 5;
          context.save(); context.globalAlpha = .22; context.beginPath(); context.arc(point.x, point.y, orbit, 0, TAU);
          context.strokeStyle = stream.color; context.lineWidth = 1; context.stroke(); context.restore();
          for (let packet = 0; packet < (this.reducedMotion.matches ? 1 : 4); packet++) {
            const angle = (phase + packet / 4) * TAU;
            this.drawTrafficDot(point.x + Math.cos(angle) * orbit, point.y + Math.sin(angle) * orbit, stream, packet === 0, .82);
          }
        });
        return;
      }

      const elapsed = Math.max(0, time - this.trafficStartedAt);
      this.trafficStreams().filter(stream => this.selectedTrafficFlow == null || stream.id === this.selectedTrafficFlow).forEach(stream => {
        const directed = stream.reverse ? links.slice().reverse().map(link => [link[1], link[0]]) : links;
        const count = this.reducedMotion.matches ? 1 : 5;
        for (let packet = 0; packet < count; packet++) {
          const position = this.reducedMotion.matches ? directed.length * .52 : ((elapsed / (stream.duration * directed.length) + packet / count + stream.phase) % 1) * directed.length;
          const linkIndex = Math.min(directed.length - 1, Math.floor(position)), amount = position - linkIndex;
          const [fromKey, toKey] = directed[linkIndex], point = this.projectLanePoint(fromKey, toKey, amount, stream.lane);
          if (point.z <= -.02) continue;
          if (!this.reducedMotion.matches && packet === 0) this.drawTrafficTrail(directed, position, stream);
          this.drawTrafficDot(point.x, point.y, stream, packet % 4 === 0, 1, .86 + (packet % 3) * .14);
        }
      });
    }

    trafficStreams() {
      return document.documentElement.dataset.theme === "dark" ? [
        { id: "upload", color: "#FF7798", bright: "#FFE2EA", lane: .07, duration: 3400, phase: 0 },
        { id: "download", color: "#FFCE55", bright: "#FFF4CB", lane: .17, duration: 3000, phase: .34, reverse: true },
        { id: "control", color: "#AF8CFF", bright: "#EFE5FF", lane: .27, duration: 3800, phase: .68 }
      ] : [
        { id: "upload", color: "#FF5278", bright: "#FFCDDD", lane: .07, duration: 3400, phase: 0 },
        { id: "download", color: "#F6AD11", bright: "#FFE9A6", lane: .17, duration: 3000, phase: .34, reverse: true },
        { id: "control", color: "#824BFF", bright: "#D9C5FF", lane: .27, duration: 3800, phase: .68 }
      ];
    }

    projectLanePoint(fromKey, toKey, amount, lane) {
      const fromLocation = fromKey === "user" ? this.userLocation : LOCATIONS[fromKey];
      const toLocation = toKey === "user" ? this.userLocation : LOCATIONS[toKey];
      if (!fromLocation || !toLocation) return { x: 0, y: 0, z: -1 };
      const from = vector(fromLocation.lat, fromLocation.lon), to = vector(toLocation.lat, toLocation.lon);
      const start = this.projectVector(from, 1.008), end = this.projectVector(to, 1.008);
      const point = this.projectVector(slerp(from, to, amount), 1.008);
      return { x: point.x, y: point.y - Math.sin(amount * Math.PI) * Math.hypot(end.x - start.x, end.y - start.y) * lane, z: point.z };
    }

    drawTrafficTrail(links, position, stream) {
      const points = [];
      for (let step = 10; step >= 0; step--) {
        const sample = position - .13 * step / 10;
        if (sample < 0 || sample >= links.length) continue;
        const index = Math.floor(sample), [from, to] = links[index];
        points.push(this.projectLanePoint(from, to, sample - index, stream.lane));
      }
      const context = this.context; context.save(); context.lineCap = context.lineJoin = "round"; context.shadowColor = stream.color; context.shadowBlur = 2;
      for (let i = 1; i < points.length; i++) { const a = points[i-1], b = points[i]; if (a.z <= -.02 || b.z <= -.02) continue; const intensity = i / (points.length - 1); context.beginPath(); context.moveTo(a.x, a.y); context.lineTo(b.x, b.y); context.globalAlpha = intensity * .72; context.strokeStyle = intensity > .7 ? stream.bright : stream.color; context.lineWidth = .45 + intensity * .95; context.stroke(); }
      context.restore();
    }

    drawTrafficDot(x, y, stream, head, alpha = 1, size = 1) {
      this.trafficSprites ||= new Map();
      const key = stream.color + "|" + stream.bright + "|" + head;
      let sprite = this.trafficSprites.get(key);
      if (!sprite) {
        sprite = document.createElement("canvas"); sprite.width = sprite.height = 48;
        const paint = sprite.getContext("2d"); paint.scale(2, 2); const radius = head ? 2.25 : 1.72;
        const halo = paint.createRadialGradient(12, 12, .6, 12, 12, 5.7); halo.addColorStop(0, stream.color + "58"); halo.addColorStop(1, stream.color + "00"); paint.fillStyle = halo; paint.fillRect(4, 4, 16, 16);
        const sphere = paint.createRadialGradient(11, 10.8, .1, 12, 12, radius); sphere.addColorStop(0, "#FFFFFF"); sphere.addColorStop(.32, stream.bright); sphere.addColorStop(1, stream.color);
        paint.beginPath(); paint.arc(12, 12, radius, 0, TAU); paint.fillStyle = sphere; paint.shadowColor = stream.color; paint.shadowBlur = head ? 3 : 1.5; paint.fill();
        if (head) { paint.beginPath(); paint.arc(11.3, 10.9, .5, 0, TAU); paint.fillStyle = "#FFFFFF"; paint.fill(); }
        this.trafficSprites.set(key, sprite);
      }
      this.context.globalAlpha = alpha; this.context.drawImage(sprite, x - 12 * size, y - 12 * size, 24 * size, 24 * size); this.context.globalAlpha = 1;
    }

    findTrafficLineAt(pointer, tolerance) {
      let closest = tolerance, flow = null;
      this.routeHitSegments.forEach(segment => { const dx = segment.b.x - segment.a.x, dy = segment.b.y - segment.a.y; const t = clamp(((pointer.x - segment.a.x) * dx + (pointer.y - segment.a.y) * dy) / Math.max(.001, dx * dx + dy * dy), 0, 1); const distance = Math.hypot(pointer.x - segment.a.x - dx * t, pointer.y - segment.a.y - dy * t); if (distance < closest) { closest = distance; flow = segment.stream; } });
      return flow;
    }

    selectTrafficFlow(flow) {
      this.selectedTrafficFlow = ["upload", "download", "control"].includes(flow) ? flow : null;
      this.start();
    }

    activeRouteNodeKeys() {
      if (this.route === "auto") {
        if (this.autoExitRouteKey && ROUTES[this.autoExitRouteKey]) return ROUTES[this.autoExitRouteKey].nodes;
        const key = COUNTRY_EXIT_KEYS[this.autoExitCountry];
        return key && this.availableLocationKeys.has(key) ? [key] : [];
      }
      return ROUTES[this.route].nodes;
    }

    activeRouteDefinition() {
      if (this.route === "auto") {
        if (this.autoExitRouteKey && ROUTES[this.autoExitRouteKey]) return ROUTES[this.autoExitRouteKey];
        if (this.connected) return null;
      }
      return ROUTES[this.route] || null;
    }

    drawNode(key, location, colors, kind) {
      const active = kind === "user" || this.activeRouteNodeKeys().includes(key);
      if (this.connected && kind !== "user" && !active) return;
      const context = this.context;
      const projected = this.projectLocation(location, 1.008);
      this.projectedNodes[key] = projected;
      if (projected.z <= 0.015) return;
      const hovered = this.hovered === key;
      const outer = active || hovered ? (hovered ? 12 : 8) : 0;
      if (outer) {
        context.beginPath();
        context.arc(projected.x, projected.y, outer, 0, TAU);
        context.fillStyle = kind === "user" ? "rgba(117,216,255,.16)" : "rgba(83,101,246,.08)";
        context.fill();
        context.globalAlpha = 1;
      }
      context.beginPath();
      context.arc(projected.x, projected.y, hovered ? 5 : active ? 4.1 : 2.1, 0, TAU);
      context.fillStyle = kind === "user" ? colors.routeHot : active || hovered ? colors.nodeCore : colors.landDim;
      context.shadowColor = kind === "user" ? colors.routeHot : active || hovered ? colors.nodeCore : colors.border;
      context.shadowBlur = hovered ? 18 : active ? 12 : 3;
      context.fill();
      context.shadowBlur = 0;
      if (active || hovered) {
        context.beginPath();
        context.arc(projected.x, projected.y, hovered ? 5 : 4.1, 0, TAU);
        context.lineWidth = 1;
        context.strokeStyle = kind === "user" ? colors.node : colors.routeHot;
        context.stroke();
      }
    }

    drawLabel(key, colors, occupied) {
      const point = this.projectedNodes[key];
      if (!point || point.z <= .03) return;
      if (window.DeyttAtlasBridge && !this.isAndroidViewportAnchorVisible(point)) return;
      if (this.annotationFilter && key !== "user" && !this.annotationFilter.has(key)) return;
      const active = key === "user" || this.activeRouteNodeKeys().includes(key);
      if (this.connected && key !== "user" && !active) return;
      const autoCandidate = !this.connected && this.route === "auto" && ROUTES.auto.nodes.includes(key);
      if (!active && !autoCandidate && this.hovered !== key) return;
      const context = this.context;
      const location = key === "user" ? this.userLocation : LOCATIONS[key];
      const compact = this.width < 520;
      const labelScale = this.options.labelScale || 1;
      const baseOffset = compact ? COMPACT_LABEL_OFFSETS[key] : LABEL_OFFSETS[key];
      const offset = { x: baseOffset.x * labelScale, y: baseOffset.y * labelScale, align: baseOffset.align };
      const title = key === "user" ? (this.copy(location.city) || this.copy("ваша сеть")) : this.copy(location.city);
      const meta = key === "user" ? (location.country || this.copy("вход в сеть")) : location.code + " · " + this.copy(location.country);
      const titleFontSize = Math.round((compact ? 9 : 10) * labelScale);
      const metaFontSize = Math.round((compact ? 7 : 8) * labelScale);
      const inset = Math.round((compact ? 10 : 12) * labelScale);
      const height = Math.round((compact ? 35 : 39) * labelScale);
      const fontFamily = this.options.labelFontFamily || '"Segoe UI", sans-serif';
      const titleFont = "700 " + titleFontSize + "px " + fontFamily;
      const metaFont = "600 " + metaFontSize + "px " + fontFamily;
      context.save();
      context.font = titleFont;
      const titleWidth = context.measureText(title).width;
      context.font = metaFont;
      const width = Math.max(titleWidth, context.measureText(meta).width) + inset * 2;
      const preferredSide = this.connected && window.DeyttAtlasBridge ? (key === "user" ? 1 : -1) : 0;
      // Prefer nearby labels; score all alternatives instead of falling back
      // to (8,8), which detached crowded user labels from their actual pin.
      const alternatives = [offset,
        { x: 14 * labelScale, y: -height / 2 - 12 * labelScale, align: "left" },
        { x: -14 * labelScale, y: -height / 2 - 12 * labelScale, align: "right" },
        { x: 14 * labelScale, y: height / 2 + 12 * labelScale, align: "left" },
        { x: -14 * labelScale, y: height / 2 + 12 * labelScale, align: "right" },
        { x: -width / 2, y: -height - 30 * labelScale, align: "left" },
        { x: -width / 2, y: height + 30 * labelScale, align: "left" }
      ];
      const previousPlacement = this.labelPlacements.get(key);
      let placementIndex = -1;
      let bestScore = Infinity;
      for (let index = 0; index < alternatives.length; index += 1) {
        const candidate = alternatives[index];
        const candidateLeft = clamp(point.x + candidate.x - (candidate.align === "right" ? width : 0), 8, Math.max(8, this.width - width - 8));
        const candidateTop = clamp(point.y + candidate.y - height / 2, 8, this.height - height - 8);
        const overlapsLabel = occupied.some(label =>
          candidateLeft < label.left + label.width + 5 && candidateLeft + width + 5 > label.left &&
          candidateTop < label.top + label.height + 5 && candidateTop + height + 5 > label.top);
        const coversPin = Object.values(this.projectedNodes).some(p => p.z > .03 &&
          p.x > candidateLeft - 5 && p.x < candidateLeft + width + 5 &&
          p.y > candidateTop - 5 && p.y < candidateTop + height + 5);
        const routeCrossings = this.routeSegments.reduce((count, segment) => count + Number(
          segment.left < candidateLeft + width + 4 && segment.right > candidateLeft - 4 &&
          segment.top < candidateTop + height + 4 && segment.bottom > candidateTop - 4), 0);
        const dx = point.x - clamp(point.x, candidateLeft, candidateLeft + width);
        const dy = point.y - clamp(point.y, candidateTop, candidateTop + height);
        const oppositeSide = preferredSide > 0 ? candidateLeft < point.x + 5 :
          preferredSide < 0 && candidateLeft + width > point.x - 5;
        const score = (coversPin ? 1e9 : 0) + (overlapsLabel ? 1e8 : 0) + routeCrossings * 1e5 +
          (oppositeSide ? CONNECTED_LABEL_SIDE_PENALTY : 0) +
          dx * dx + dy * dy + (candidate.y > 0 ? 900 : 0) - (previousPlacement === index ? 30 : 0);
        if (score < bestScore) { bestScore = score; placementIndex = index; }
      }
      if (placementIndex < 0) { context.restore(); return; }
      this.labelPlacements.set(key, placementIndex);
      const placement = alternatives[placementIndex];
      const left = clamp(point.x + placement.x - (placement.align === "right" ? width : 0), 8, Math.max(8, this.width - width - 8));
      const top = clamp(point.y + placement.y - height / 2, 8, this.height - height - 8);
      const labelOverlap = occupied.some(label => left < label.left + label.width + 5 && left + width + 5 > label.left &&
        top < label.top + label.height + 5 && top + height + 5 > label.top);
      const pinOverlap = Object.values(this.projectedNodes).some(p => p.z > .03 &&
        p.x > left - 5 && p.x < left + width + 5 && p.y > top - 5 && p.y < top + height + 5);
      const routeOverlap = this.routeSegments.some(segment =>
        segment.left < left + width + 4 && segment.right > left - 4 &&
        segment.top < top + height + 4 && segment.bottom > top - 4);
      // Keep pins visible when a dense route leaves no collision-free label slot.
      if (labelOverlap || pinOverlap || routeOverlap) {
        context.restore();
        return;
      }
      const endX = clamp(point.x, left + 5, left + width - 5);
      const endY = clamp(point.y, top + 5, top + height - 5);
      context.beginPath();
      context.moveTo(point.x, point.y);
      context.lineTo(endX, endY);
      context.strokeStyle = key === "user" ? colors.node : active ? colors.nodeCore : colors.border;
      context.lineWidth = 1;
      context.globalAlpha = active ? .7 : .35;
      context.stroke();
      occupied.push({ left: left, top: top, width: width, height: height });
      context.beginPath();
      context.roundRect(left, top, width, height, Math.round((compact ? 10 : 12) * labelScale));
      context.fillStyle = colors.labelBg;
      context.globalAlpha = active ? .96 : .68;
      context.shadowColor = "rgba(16,29,65,.22)";
      context.shadowBlur = 18;
      context.fill();
      context.shadowBlur = 0;
      context.strokeStyle = this.hovered === key ? colors.borderHot : colors.border;
      context.globalAlpha = active ? (this.hovered === key ? .9 : .65) : .35;
      context.stroke();
      context.textBaseline = "middle";
      context.textAlign = "left";
      context.fillStyle = active ? colors.label : colors.labelMuted;
      context.globalAlpha = 1;
      context.font = titleFont;
      context.fillText(title.toLowerCase(), left + inset, top + Math.round((compact ? 11 : 12) * labelScale));
      context.fillStyle = colors.labelMuted;
      context.font = metaFont;
      context.fillText(meta.toLowerCase(), left + inset, top + Math.round((compact ? 25 : 28) * labelScale));
      context.restore();
    }

    drawNodes(colors) {
      this.projectedNodes = {};
      LOCATION_ORDER.forEach((key) => {
        if (this.availableLocationKeys.has(key)) this.drawNode(key, LOCATIONS[key], colors, "server");
      });
      if (this.userLocation) this.drawNode("user", this.userLocation, colors, "user");
    }

    drawLabels(colors) {
      const occupied = [];
      if (this.hovered) this.drawLabel(this.hovered, colors, occupied);
      if (this.userLocation && this.hovered !== "user") this.drawLabel("user", colors, occupied);
      LOCATION_ORDER.forEach((key) => {
        if (this.availableLocationKeys.has(key) && this.hovered !== key) this.drawLabel(key, colors, occupied);
      });
    }

    isAndroidViewportAnchorVisible(point) {
      return point.x >= 0 && point.x <= this.width && point.y >= 0 && point.y <= this.height;
    }

    renderStatic(colors) {
      const liveContext = this.context;
      const context = this.staticContext;
      this.context = context;
      try {
        context.clearRect(0, 0, this.width, this.height);
        this.drawSphere(colors);
        context.save();
        context.beginPath();
        const clipRadius = this.radius * this.zoom - .5;
        if (clipRadius > 0) {
          context.arc(this.centerX, this.centerY, clipRadius, 0, TAU);
          context.clip();
          this.drawBorders(colors);
        }
        context.restore();
      } finally {
        this.context = liveContext;
      }
      this.staticDirty = false;
      this.staticRenderCount += 1;
    }

    overlaySignature(colors) {
      const root = document.documentElement;
      return JSON.stringify([
        this.staticRenderCount, this.width, this.height, this.pixelRatio,
        this.centerLon, this.centerLat, this.zoom, this.centerX, this.centerY, this.radius,
        root.dataset.theme, root.dataset.atlasPalette, root.dataset.atlasBackdrop,
        this.root.classList.contains("is-backdrop-stage"), colors,
        this.variant, this.showcaseFocused, this.overviewMode, this.connected, this.hovered,
        this.route, this.autoExitCountry, this.autoExitRouteKey,
        Array.from(this.availableLocationKeys).sort(), this.userLocation, this.language,
        this.annotationFilter && Array.from(this.annotationFilter).sort(), this.selectedTrafficFlow,
        this.options.labelScale, this.options.labelFontFamily
      ]);
    }

    renderOverlayCache(colors, time) {
      const liveContext = this.context;
      try {
        this.context = this.routeOverlayContext;
        this.context.clearRect(0, 0, this.width, this.height);
        this.routeSegments = [];
        this.cachedRouteLinks = this.drawRoutes(colors, time, false);

        this.context = this.foregroundOverlayContext;
        this.context.clearRect(0, 0, this.width, this.height);
        this.drawNodes(colors);
        this.drawLabels(colors);
      } finally {
        this.context = liveContext;
      }
      this.overlayCacheSignature = this.overlaySignature(colors);
      this.overlayCacheBuildCount += 1;
    }

    render(time) {
      this.frame = 0;
      if (!this.visible || document.hidden || !this.width) return;
      if (window.DeyttAtlasBridge && !this.viewportAnchor) return;
      const cameraMoving = this.dragging || Math.abs(this.targetZoom - this.zoom) > .006 || Math.abs(this.targetLon - this.centerLon) > .06 || Math.abs(this.targetLat - this.centerLat) > .06;
      this.cameraMoving = cameraMoving;
      // Static geography and route overlays stay cached; only packets change each frame.
      const elapsed = this.lastFrame ? time - this.lastFrame : 16;
      const delta = Math.min(48, elapsed);
      this.lastFrame = time;
      const previousLon = this.centerLon;
      const previousLat = this.centerLat;
      const previousZoom = this.zoom;
      if (!this.dragging) {
        this.targetLon += this.velocityLon;
        this.velocityLon *= .86;
        if (Math.abs(this.velocityLon) < .004) this.velocityLon = 0;
      }
      // Pointer events already arrive as the user's input stream. Interpolating
      // targetLon a second time made the globe visibly trail and then catch up.
      const panBlend = this.reducedMotion.matches || this.dragging ? 1 : 1 - Math.exp(-Math.max(1, delta) / PAN_EASE_MS);
      this.centerLon = mix(this.centerLon, this.targetLon, panBlend);
      this.centerLat = mix(this.centerLat, this.targetLat, panBlend);
      const zoomBlend = this.reducedMotion.matches ? 1 : 1 - Math.exp(-Math.max(1, delta) / ZOOM_EASE_MS);
      this.zoom = mix(this.zoom, this.targetZoom, zoomBlend);
      // The animation loop intentionally stops at these same visual
      // tolerances. Snap the final sub-pixel remainder now so the next hover
      // cannot wake the camera and force a needless geography redraw.
      if (Math.abs(this.targetLon - this.centerLon) < .01) this.centerLon = this.targetLon;
      if (Math.abs(this.targetLat - this.centerLat) < .01) this.centerLat = this.targetLat;
      if (Math.abs(this.targetZoom - this.zoom) < .002) this.zoom = this.targetZoom;
      if (Math.abs(this.centerLon - previousLon) > .0001 || Math.abs(this.centerLat - previousLat) > .0001 || Math.abs(this.zoom - previousZoom) > .0001) {
        this.staticDirty = true;
      }
      const cameraSettled = !this.dragging && Math.abs(this.targetLon - this.centerLon) <= .01 &&
        Math.abs(this.targetLat - this.centerLat) <= .01 && Math.abs(this.targetZoom - this.zoom) <= .002 && Math.abs(this.velocityLon) <= .001;
      this.currentBasis = this.basis();
      this.context.clearRect(0, 0, this.width, this.height);
      const colors = this.colors();
      if (this.staticDirty) this.renderStatic(colors);
      this.context.drawImage(this.staticCanvas, 0, 0, this.width, this.height);
      if (!cameraSettled) {
        this.routeSegments = [];
        this.drawRoutes(colors, time);
        this.drawNodes(colors);
        this.drawLabels(colors);
      } else {
        const signature = this.overlaySignature(colors);
        if (signature !== this.overlayCacheSignature) this.renderOverlayCache(colors, time);
        this.context.drawImage(this.routeOverlayCanvas, 0, 0, this.width, this.height);
        if (this.trafficActive) this.drawTraffic(this.cachedRouteLinks, time || performance.now());
        this.context.drawImage(this.foregroundOverlayCanvas, 0, 0, this.width, this.height);
      }
      this.renderCount += 1;
      if (Math.abs(this.targetLon - this.centerLon) > .01 || Math.abs(this.targetLat - this.centerLat) > .01 || Math.abs(this.targetZoom - this.zoom) > .002 || Math.abs(this.velocityLon) > .001 || (this.trafficActive && !this.reducedMotion.matches)) this.start();
    }

    start() {
      if (!this.frame && this.visible && !document.hidden) this.frame = requestAnimationFrame((time) => this.render(time));
    }

    updateHover(pointer) {
      let nearest = null;
      let nearestDistance = 30;
      Object.keys(this.projectedNodes).forEach((key) => {
        const point = this.projectedNodes[key];
        if (!point || point.z <= 0.015) return;
        const distance = Math.hypot(point.x - pointer.x, point.y - pointer.y);
        if (distance < nearestDistance) { nearest = key; nearestDistance = distance; }
      });
      this.setHovered(nearest);
    }

    setHovered(key) {
      if (this.hovered === key) return;
      this.hovered = key;
      this.canvas.style.cursor = key ? "pointer" : this.dragging ? "grabbing" : "grab";
      // Hover invalidates only the foreground overlay, not the high-DPI geography.
      if (this.tooltip) this.tooltip.hidden = true;
      this.start();
    }

    setRoute(key, announce) {
      if (!ROUTES[key]) return;
      if (this.variant === "showcase" && announce !== false) this.showcaseFocused = true;
      const routeChanged = !this.routeInitialized || this.route !== key;
      this.route = key;
      this.routeInitialized = true;
      if (routeChanged) this.labelPlacements.clear();
      this.staticDirty = true;
      this.root.querySelectorAll("[data-atlas-route]").forEach(function (button) {
        const active = button.dataset.atlasRoute === key;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      const route = ROUTES[key];
      this.renderRouteCopy();
      if (routeChanged) this.focusRoute();
      if (announce !== false) this.root.dispatchEvent(new CustomEvent("deytt:route-change", { detail: { key: key, route: route } }));
      this.start();
    }

    copy(value) {
      if (this.language !== "en" || !value) return value || "";
      return EN_COPY[String(value).toLowerCase()] || value;
    }

    renderRouteCopy() {
      const source = ROUTES[this.route] || ROUTES.auto;
      const route = this.language === "en" ? (EN_ROUTES[this.route] || EN_ROUTES.auto) : source;
      const title = this.root.querySelector("[data-atlas-title]");
      const eyebrow = this.root.querySelector("[data-atlas-eyebrow]");
      const description = this.root.querySelector("[data-atlas-description]");
      const steps = this.root.querySelector("[data-atlas-steps]");
      if (title) title.textContent = route.title.toLowerCase();
      if (eyebrow) eyebrow.textContent = route.eyebrow.toLowerCase();
      if (description) description.textContent = route.description.toLowerCase();
      if (steps) {
        steps.innerHTML = route.steps.map(function (step, index) {
          const arrow = index ? '<span class="atlas-step__arrow" aria-hidden="true">→</span>' : "";
          return arrow + '<span class="atlas-step"><b>' + step[0].toLowerCase() + '</b><small>' + step[1].toLowerCase() + '</small></span>';
        }).join("");
        steps.setAttribute("aria-label", route.steps.map(function (step) { return step.join(" — "); }).join(" → "));
      }
      const mapLabel = this.language === "en"
        ? "Interactive globe. Route: " + route.title + ". Drag to rotate; pinch to zoom. Choose a route from the list below.".toLowerCase()
        : "интерактивный глобус: маршрут «" + route.title + "». проведите пальцем, чтобы повернуть; сведите или разведите два пальца, чтобы изменить масштаб. маршрут выбирается в списке ниже.";
      this.canvas.setAttribute("aria-label", mapLabel);
    }

    setLanguage(language) {
      const nextLanguage = language === "en" ? "en" : "ru";
      if (this.language !== nextLanguage) this.labelPlacements.clear();
      this.language = nextLanguage;
      document.documentElement.lang = this.language;
      document.title = (this.language === "en" ? "DEYTT network map" : "Карта сети DEYTT").toLowerCase();
      const hint = this.root.querySelector(".network-atlas__hint");
      const loader = this.root.querySelector(".network-atlas__loader");
      if (hint) hint.textContent = (this.language === "en" ? "drag · pinch to zoom" : "потяните · два пальца — масштаб").toLowerCase();
      if (loader) loader.textContent = (this.language === "en" ? "Loading map" : "Загружаю карту").toLowerCase();
      this.renderRouteCopy();
      this.staticDirty = true;
      this.start();
    }

    setAnnotationFilter(keys) {
      this.annotationFilter = keys == null ? null : new Set(keys);
      this.start();
    }

    setAvailableLocations(keys) {
      const next = new Set((Array.isArray(keys) ? keys : []).map((key) => String(key).toLowerCase()).filter((key) => LOCATION_ORDER.includes(key)));
      const changed = next.size !== this.availableLocationKeys.size || Array.from(next).some((key) => !this.availableLocationKeys.has(key));
      if (!changed) return;
      this.availableLocationKeys = next;
      this.labelPlacements.clear();
      this.focusRoute();
      this.staticDirty = true;
      this.start();
    }

    setUserLocation(latitude, longitude, details) {
      const previousLocation = this.userLocation;
      const nextLocation = latitude == null || longitude == null ? null : {
        lat: clamp(Number(latitude), -85, 85),
        lon: ((Number(longitude) + 540) % 360) - 180,
        city: details && details.city,
        country: details && details.country
      };
      if (JSON.stringify(this.userLocation) === JSON.stringify(nextLocation)) return;
      this.userLocation = nextLocation;
      this.labelPlacements.clear();
      // The first origin fix (or an explicit removal) may change the useful
      // framing. Later IP-location refreshes update the endpoint in place so
      // a small geolocation correction cannot make the whole atlas jump.
      if (previousLocation == null || nextLocation == null) this.focusRoute();
      this.staticDirty = true;
      this.start();
    }

    setAutoExitCountry(countryCode) {
      const nextCountry = String(countryCode || "").toUpperCase();
      const nextKey = COUNTRY_EXIT_KEYS[nextCountry] && this.availableLocationKeys.has(COUNTRY_EXIT_KEYS[nextCountry])
        ? nextCountry
        : null;
      if (this.autoExitCountry === nextKey) return;
      this.autoExitCountry = nextKey;
      this.staticDirty = true;
      this.start();
    }

    setActiveAutoRoute(routeKey) {
      const allowed = ["nl", "de", "fi", "ru", "it", "ru-de"];
      const requestedKey = String(routeKey || "").toLowerCase();
      const nextRouteKey = allowed.includes(requestedKey)
        ? requestedKey
        : null;
      if (this.autoExitRouteKey === nextRouteKey) return;
      this.autoExitRouteKey = nextRouteKey;
      if (this.route === "auto" && nextRouteKey) this.focusRoute();
      // Egress metadata changes the active endpoint, never the user's camera
      // after an active route is selected. The first active route update frames
      // the actual path; later country corrections only refresh its marker.
      this.staticDirty = true;
      this.start();
    }

    setTrafficActive(active) {
      const next = Boolean(active);
      if (this.trafficActive === next) return;
      this.trafficActive = next;
      this.trafficStartedAt = next ? performance.now() : 0;
      this.start();
    }

    setConnected(active) {
      const next = Boolean(active);
      if (this.connected === next) return;
      this.connected = next;
      if (!next) {
        this.autoExitCountry = null;
        this.autoExitRouteKey = null;
      }
      this.labelPlacements.clear();
      this.start();
    }

    focusRoute() {
      const focusNodes = this.variant === "observatory"
        ? LOCATION_ORDER
        : this.route === "auto" && this.activeRouteNodeKeys().length
          ? this.activeRouteNodeKeys()
          : ROUTES[this.route].nodes;
      const routeNodes = focusNodes
        .filter((key) => this.availableLocationKeys.has(key));
      const locations = routeNodes.map((key) => LOCATIONS[key]);
      if (this.userLocation) locations.push(this.userLocation);
      if (!locations.length) return;
      const originKey = this.userLocation
        ? Math.round(this.userLocation.lat * 5) / 5 + ":" + Math.round(this.userLocation.lon * 5) / 5
        : "none";
      const focusSignature = this.variant + "|" + routeNodes.join(",") + "|" + originKey;
      if (focusSignature === this.lastFocusSignature) return;
      this.lastFocusSignature = focusSignature;
      const vectors = locations.map((location) => vector(location.lat, location.lon));
      const center = normalize(vectors.reduce(function (total, point) {
        return { x: total.x + point.x, y: total.y + point.y, z: total.z + point.z };
      }, { x: 0, y: 0, z: 0 }));
      const maxAngle = Math.max.apply(Math, vectors.map((point) => Math.acos(clamp(dot(point, center), -1, 1))));
      const target = locationFromVector(center);
      this.targetLat = clamp(target.lat, -62, 72);
      this.targetLon = nearestLongitude(this.centerLon, target.lon);
      if (this.overviewMode) {
        this.targetZoom = this.clampZoom(this.targetZoom);
        this.staticDirty = true;
        return;
      }
      // Desktop opens on the selected segment; wheel zoom reveals the whole globe.
      // Other clients retain the established Android/site framing.
      if (Number.isFinite(this.options.routeFocusMinZoom)) {
        const singleNode = routeNodes.length === 1 && !this.userLocation;
        const fitRadius = Math.min(this.width, this.height) * (this.options.routeFocusFill || .39);
        const fitZoom = fitRadius / Math.max(this.radius * Math.sin(maxAngle), this.radius * .055);
        const minZoom = this.options.routeFocusMinZoom;
        const maxZoom = this.options.routeFocusMaxZoom || minZoom;
        this.targetZoom = singleNode
          ? clamp(this.options.routeFocusSingleZoom || maxZoom, minZoom, maxZoom)
          : clamp(fitZoom * (this.options.routeFocusScale || 1), minZoom, maxZoom);
      } else {
        this.targetZoom = clamp(2.0 / Math.max(Math.sin(maxAngle), .2), 2.15, 2.85);
      }
      this.staticDirty = true;
    }

    focusMeasurement(key) {
      const location = LOCATIONS[key];
      if (!location) return;
      const locations = this.userLocation ? [this.userLocation, location] : [location];
      const vectors = locations.map((item) => vector(item.lat, item.lon));
      const center = normalize(vectors.reduce(function (total, point) {
        return { x: total.x + point.x, y: total.y + point.y, z: total.z + point.z };
      }, { x: 0, y: 0, z: 0 }));
      const maxAngle = Math.max.apply(Math, vectors.map((point) => Math.acos(clamp(dot(point, center), -1, 1))));
      const target = locationFromVector(center);
      this.targetLat = clamp(target.lat, -62, 72);
      this.targetLon = nearestLongitude(this.centerLon, target.lon);
      // A measurement is a close-up of one segment, not the whole network.
      // Fit the two endpoints first, then use the full close-up range for nearby points.
      const availableRadius = Math.min(this.width, this.height) * .47;
      const fitZoom = availableRadius / Math.max(this.radius * Math.sin(maxAngle), this.radius * .0125);
      this.targetZoom = clamp(Math.min(MAX_ZOOM, fitZoom), 1.28, MAX_ZOOM);
      this.staticDirty = true;
      this.start();
    }
  }

  async function create(root, options) {
    const atlas = new NetworkAtlas(root, options);
    root.querySelectorAll("[data-atlas-route]").forEach(function (button) {
      button.addEventListener("click", function () { atlas.setRoute(button.dataset.atlasRoute); });
    });
    try {
      return await atlas.init();
    } catch (error) {
      root.classList.add("has-error");
      const status = root.querySelector("[data-atlas-status]");
      if (status) status.textContent = "карта временно недоступна";
      throw error;
    }
  }

  window.DeyttAtlas = { create: create, routes: ROUTES, locations: LOCATIONS };
}());
