(function () {
  "use strict";

  const DEG = Math.PI / 180;
  const TAU = Math.PI * 2;
  const MAX_ZOOM = 32;
  const ZOOM_EASE_MS = 115;
  const PAN_EASE_MS = 120;
  const LOCATION_ORDER = ["nl", "de", "fi", "ru", "it"];
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
    it: { code: "it", city: "Милан", country: "Италия", countryId: "380", lat: 45.4642, lon: 9.1900 }
  };
  const COUNTRY_EXIT_KEYS = { NL: "nl", DE: "de", FI: "fi", RU: "ru", IT: "it" };
  const ROUTES = {
    auto: {
      title: "автоподбор",
      eyebrow: "европейские выходы",
      nodes: ["nl", "de", "fi", "it"],
      links: [],
      steps: [["вы", "устройство"], ["auto", "проверка точек"], ["nl / de / fi / it", "доступные выходы"], ["web", "интернет"]],
      description: "автоподбор выбирает доступный европейский выход из амстердама, франкфурта, хельсинки или италии по состоянию сети."
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
    "уфа": "Ufa",
    "амстердам": "Amsterdam",
    "нидерланды": "Netherlands",
    "франкфурт": "Frankfurt",
    "франкфурт-на-майне": "Frankfurt am Main",
    "германия": "Germany",
    "хельсинки": "Helsinki",
    "финляндия": "Finland",
    "милан": "Milan",
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
      steps: [["you", "device"], ["auto", "checking nodes"], ["nl / de / fi / it", "available exits"], ["web", "internet"]],
      description: "Auto-select chooses an available European exit in Amsterdam, Frankfurt, Helsinki, or Italy based on network conditions."
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
      this.canvas = root.querySelector("canvas[data-atlas-interactive]") || root.querySelector("canvas");
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
      this.hovered = null;
      this.hoveredTrafficStream = null;
      this.inspectingStream = null;
      this.annotationFilter = null;
      this.dragging = false;
      this.activePointers = new Map();
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
      this.radius = 0;
      this.lastFrame = 0;
      this.frame = 0;
      this.visible = true;
      this.hostActive = true;
      this.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
      this.boundaryLines = [];
      this.targetBoundaryLines = [];
      this.landPolygons = [];
      this.pixelRatio = 1;
      this.preferredPixelRatio = 1;
      this.interactionQuality = false;
      this.staticCanvas = document.createElement("canvas");
      this.staticContext = this.staticCanvas.getContext("2d");
      this.overlayCanvas = document.createElement("canvas");
      this.overlayContext = this.overlayCanvas.getContext("2d");
      [this.staticCanvas, this.overlayCanvas].forEach((layer) => {
        layer.className = "network-atlas__cached-layer";
        layer.setAttribute("aria-hidden", "true");
        this.canvas.parentElement.insertBefore(layer, this.canvas);
      });
      this.packetSprites = new Map();
      this.staticDirty = true;
      this.overlayDirty = true;
      this.renderCount = 0;
      this.staticRenderCount = 0;
      this.cameraMoving = false;
      this.trafficActive = false;
      this.trafficStartedAt = 0;
      this.projectedNodes = {};
      this.packetHits = [];
      this.routeHitSegments = [];
      this.trafficPausedAt = null;
      this.lastTrafficSelection = null;
      this.dragDistance = 0;
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
      this.start();
      this.root.dispatchEvent(new CustomEvent("deytt:atlas-ready", { detail: { atlas: this } }));
      return this;
    }

    bind() {
      this.canvas.addEventListener("pointerdown", (event) => {
        if (event.button !== 0) return;
        this.canvas.focus({ preventScroll: true });
        this.setInteractionQuality(true);
        this.canvas.style.cursor = "grabbing";
        if (this.tooltip) this.tooltip.hidden = true;
        this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        this.dragging = true;
        this.dragDistance = 0;
        this.velocityLon = 0;
        this.lastPointer = { x: event.clientX, y: event.clientY };
        this.canvas.setPointerCapture(event.pointerId);
        this.root.classList.add("is-dragging");
        if (this.activePointers.size === 2) {
          const points = Array.from(this.activePointers.values());
          this.pinchDistance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
        }
        // Changing resolution clears every canvas. Repaint even when the
        // pointer stays still, rather than waiting for the first move event.
        this.start();
      });
      this.canvas.addEventListener("pointermove", (event) => {
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
        if (this.activePointers.has(event.pointerId)) this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (this.activePointers.size >= 2) {
          const points = Array.from(this.activePointers.values()).slice(0, 2);
          const distance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
          if (!this.wheelZoomOnly && this.pinchDistance > 0 && distance > 0) {
            this.targetZoom = clamp(this.targetZoom * distance / this.pinchDistance, .82, MAX_ZOOM);
            this.markMapDirty();
          }
          this.pinchDistance = distance;
          this.start();
          return;
        }
        if (this.dragging) {
          const dx = event.clientX - this.lastPointer.x;
          const dy = event.clientY - this.lastPointer.y;
          this.dragDistance += Math.hypot(dx, dy);
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
        event.preventDefault();
        this.canvas.focus({ preventScroll: true });
        this.setInteractionQuality(true);
        const delta = event.deltaMode === 1 ? event.deltaY * 40
          : event.deltaMode === 2 ? event.deltaY * this.height : event.deltaY;
        // A 120px wheel detent is the same 18% step at every zoom level.
        const detents = clamp(delta / 120, -2, 2);
        this.targetZoom = clamp(this.targetZoom * Math.pow(1.18, -detents), .82, MAX_ZOOM);
        this.markMapDirty();
        this.start();
      }, { passive: false });
      const release = (event) => {
        if (!this.activePointers.has(event.pointerId)) return;
        this.activePointers.delete(event.pointerId);
        this.pinchDistance = 0;
        this.dragging = this.activePointers.size > 0;
        const remaining = this.activePointers.values().next().value;
        if (remaining) this.lastPointer = remaining;
        else {
          this.root.classList.remove("is-dragging");
          this.canvas.style.cursor = "grab";
        }
        if (event.pointerType === "touch") this.setHovered(null);
        if (event.type !== "pointerup") {
          this.velocityLon = 0;
          this.dragDistance = 6;
        }
        this.start();
      };
      this.canvas.addEventListener("pointerup", release);
      this.canvas.addEventListener("pointercancel", release);
      this.canvas.addEventListener("lostpointercapture", release);
      this.canvas.addEventListener("pointerleave", () => {
        if (!this.dragging) {
          this.setHovered(null);
          this.setHoveredTrafficStream(null);
          this.canvas.style.cursor = "grab";
        }
      });
      this.canvas.addEventListener("click", (event) => {
        if (this.dragDistance > 5) return;
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
        const line = this.findLineAt(pointer, 10);
        if (line) {
          this.lastTrafficSelection = line;
          this.lastTrafficPointer = { ...pointer, time: performance.now() };
          if (event.detail === 1) this.showTrafficHint();
          return;
        }
        if (this.tooltip) this.tooltip.hidden = true;
        // Touch clears hover during pointerup, so resolve the tap from its
        // coordinates instead of relying on the last mouse-hovered node.
        this.updateHover(pointer);
        if (this.hovered && typeof window.deyttMapNodeTapped === "function") {
          window.deyttMapNodeTapped(this.hovered);
          return;
        }
        if (!this.selectOnTap || !this.hovered || this.hovered === "user") return;
        this.setRoute(this.hovered === "ru" && this.route === "de" ? "ru-de" : this.hovered);
      });
      this.canvas.addEventListener("dblclick", (event) => {
        if (this.dragDistance > 5) return;
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
        const recent = this.lastTrafficPointer && performance.now() - this.lastTrafficPointer.time < 700 && Math.hypot(pointer.x - this.lastTrafficPointer.x, pointer.y - this.lastTrafficPointer.y) < 25;
        const line = this.findLineAt(pointer, 12) || (recent ? this.lastTrafficSelection : null);
        if (!line?.isLine) return;
        event.preventDefault();
        clearTimeout(this.hintTimer);
        this.inspectingStream = line.stream.id;
        this.hoveredTrafficStream = line.stream.id;
        this.overlayDirty = true;
        this.showTrafficInspector(line);
        this.start();
      });
      this.canvas.addEventListener("keydown", (event) => {
        if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "+", "=", "-", "_", "0"].includes(event.key)) return;
        event.preventDefault();
        if (event.key === "ArrowLeft") this.targetLon -= 9;
        if (event.key === "ArrowRight") this.targetLon += 9;
        if (event.key === "ArrowUp") this.targetLat = clamp(this.targetLat + 7, -62, 72);
        if (event.key === "ArrowDown") this.targetLat = clamp(this.targetLat - 7, -62, 72);
        if (event.key === "+" || event.key === "=") this.targetZoom = clamp(this.targetZoom * 1.18, .82, MAX_ZOOM);
        if (event.key === "-" || event.key === "_") this.targetZoom = clamp(this.targetZoom / 1.18, .82, MAX_ZOOM);
        if (event.key === "0") this.targetZoom = 1;
        this.setInteractionQuality(true);
        this.markMapDirty();
        this.start();
      });
      new ResizeObserver(() => this.resize()).observe(this.canvas);
      window.addEventListener("resize", () => this.resize());
      new IntersectionObserver((entries) => {
        this.visible = entries[0].isIntersecting;
        if (this.visible) this.start();
      }, { rootMargin: "180px 0px" }).observe(this.canvas);
      new MutationObserver(() => {
        this.markMapDirty();
        this.start();
      }).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
      this.reducedMotion.addEventListener("change", () => {
        this.markMapDirty();
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
      // Retained native pages briefly report a zero-sized surface when hidden.
      // Keep their geography bitmap instead of rebuilding a 1px globe and back.
      if (rectangle.width < 1 || rectangle.height < 1 || !this.hostActive) return;
      const previousWidth = this.width;
      const previousHeight = this.height;
      const pixelRatioCap = this.options.pixelRatioCap ||
        (rectangle.width < 520 || (navigator.connection && navigator.connection.saveData) ? 1.35 : 1.5);
      const pixelRatioFloor = Math.min(this.options.pixelRatioFloor || 1, pixelRatioCap);
      const preferredRatio = Math.min(Math.max(window.devicePixelRatio || 1, pixelRatioFloor), pixelRatioCap);
      const layoutMode = [this.root.classList.contains("is-immersive"), this.root.classList.contains("is-zoomed")].join(":");
      if (previousWidth === Math.round(rectangle.width) && previousHeight === Math.round(rectangle.height)
        && this.preferredPixelRatio === preferredRatio && this.layoutMode === layoutMode) return;
      this.layoutMode = layoutMode;
      this.preferredPixelRatio = preferredRatio;
      this.width = Math.max(1, Math.round(rectangle.width));
      this.height = Math.max(1, Math.round(rectangle.height));
      if (this.width !== previousWidth || this.height !== previousHeight) this.labelPlacements.clear();
      this.setCanvasResolution(this.interactionQuality
        ? this.options.stableGeography ? this.preferredPixelRatio : Math.min(this.preferredPixelRatio, this.options.interactionPixelRatio || .85)
        : this.preferredPixelRatio);
      this.radius = this.root.classList.contains("is-immersive")
        ? Math.min(this.width, this.height) * 0.47
        : this.width < 1180
          ? Math.min(this.width, this.height) * 0.405
          : Math.min(this.width * 0.33, this.height * 0.48);
      this.targetCenterX = this.width * (this.width >= 1180 && !this.root.classList.contains("is-zoomed") && !this.root.classList.contains("is-immersive") ? .34 : .5);
      if (!Number.isFinite(this.centerX) || previousWidth !== this.width) this.centerX = this.targetCenterX;
      this.targetCenterY = this.height * (this.root.classList.contains("is-zoomed") || this.root.classList.contains("is-immersive") ? .62 : .5);
      if (!Number.isFinite(this.centerY) || previousHeight !== this.height) this.centerY = this.targetCenterY;
      this.markMapDirty();
      if (this.currentBasis) {
        // ResizeObserver runs after RAF and before paint. Replacing a canvas
        // bitmap here clears it; waiting for the next RAF exposes a blank
        // strip for one native resize frame. Paint the new geometry now.
        this.currentBasis = this.basis();
        const colors = this.colors();
        this.renderStatic(colors);
        this.placeStaticCanvas();
        this.renderOverlay(colors);
        this.context.clearRect(0, 0, this.width, this.height);
        if (this.trafficActive) this.drawTraffic(colors, performance.now());
      }
      this.start();
    }

    setCanvasResolution(ratio) {
      const detailRatio = this.preferredPixelRatio;
      const padding = this.options.stableGeography ? 64 : 0;
      const staticWidth = this.width + padding * 2, staticHeight = this.height + padding * 2;
      if (Math.abs(this.pixelRatio - ratio) < .01 && this.staticCanvas.width === Math.round(staticWidth * ratio)
        && this.staticCanvas.height === Math.round(staticHeight * ratio)
        && this.canvas.width === Math.round(this.width * detailRatio)
        && this.canvas.height === Math.round(this.height * detailRatio)) return;
      this.pixelRatio = ratio;
      const width = Math.round(this.width * detailRatio);
      const height = Math.round(this.height * detailRatio);
      this.canvas.width = width;
      this.canvas.height = height;
      this.context.setTransform(detailRatio, 0, 0, detailRatio, 0, 0);
      // Geographic pixels may be lighter during motion; labels and packets
      // retain their display resolution throughout the gesture.
      // A small overscan keeps center easing on the compositor rather than
      // repainting the entire geography for each newly exposed edge pixel.
      this.staticPadding = padding;
      this.staticCanvas.width = Math.round(staticWidth * ratio);
      this.staticCanvas.height = Math.round(staticHeight * ratio);
      Object.assign(this.staticCanvas.style, { left: -padding + "px", top: -padding + "px", right: "auto", bottom: "auto", width: staticWidth + "px", height: staticHeight + "px" });
      this.staticContext.setTransform(ratio, 0, 0, ratio, padding * ratio, padding * ratio);
      this.overlayCanvas.width = width;
      this.overlayCanvas.height = height;
      this.overlayContext.setTransform(detailRatio, 0, 0, detailRatio, 0, 0);
      this.markMapDirty();
    }

    setInteractionQuality(active) {
      this.interactionQuality = Boolean(active);
      const ratio = this.interactionQuality
        ? this.options.stableGeography ? this.preferredPixelRatio : Math.min(this.preferredPixelRatio, this.options.interactionPixelRatio || .85)
        : this.preferredPixelRatio;
      this.setCanvasResolution(ratio);
    }

    markMapDirty() {
      this.staticDirty = true;
      this.overlayDirty = true;
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

    colors() {
      const windowsHome = document.documentElement.dataset.atlasProfile === "windows";
      const observatory = this.variant === "observatory";
      if (windowsHome) {
        const dark = document.documentElement.dataset.theme !== "light";
        return dark ? {
          sphereLight: "#34465B", sphereMid: "#243345", sphereShade: "#111B29",
          sphereEdge: "rgba(141,161,185,.38)", atmosphere: "rgba(0,0,0,0)", atmosphereSoft: "rgba(0,0,0,0)", rimLight: "rgba(0,0,0,0)",
          grid: null, land: "rgba(118,140,164,.93)", landDim: "rgba(96,114,135,.92)", landHot: "#71869E",
          landHighlight: "rgba(194,216,240,.29)", landRelief: "rgba(18,29,43,.74)", landLow: "rgba(80,98,119,.96)",
          border: "#AAB8C9", borderAlpha: .38, borderWidth: .8, borderHot: "#39E8BD", borderGlow: "rgba(57,232,189,.24)", route: "#FFBD32", routeHot: "#FFC857",
          download: "#FFBD32", downloadHot: "#FFE08A", upload: "#39E8BD", uploadHot: "#90F4DA",
          node: "#F2F5FC", nodeCore: "#39E8BD", label: "#F2F5FC", labelMuted: "#98A6BF", labelBg: "rgba(17,26,41,.94)"
        } : {
          sphereLight: "#FFFFFF", sphereMid: "#F4F7FB", sphereShade: "#E8EEF5",
          sphereEdge: "rgba(89,131,173,.58)", atmosphere: "rgba(0,0,0,0)", atmosphereSoft: "rgba(0,0,0,0)", rimLight: "rgba(0,0,0,0)",
          grid: null, land: "#1299DC", landDim: "#1299DC", landHot: "#1299DC", landAlpha: 1,
          landHighlight: "#1299DC", landRelief: "rgba(22,64,98,.18)", landLow: "#1299DC",
          border: "#AFE0F5", borderAlpha: .62, borderWidth: .8, borderHot: "#10A889", borderGlow: "rgba(16,168,137,.22)", route: "#D99000", routeHot: "#F0A900",
          download: "#D99000", downloadHot: "#FFC857", upload: "#10A889", uploadHot: "#79D6C1",
          node: "#FFFFFF", nodeCore: "#123A67", label: "#111823", labelMuted: "#5C6B82", labelBg: "rgba(246,247,251,.96)"
        };
      }
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
      context.shadowBlur = this.cameraMoving ? 0 : radius * 0.035;
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
      context.lineWidth = 1.5;
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

    drawBorders(colors) {
      const context = this.context;
      this.drawLand(colors);
      // Keep the same topology while the camera moves. Decimating every second
      // point during pan/zoom made country outlines visibly change shape.
      const pointStep = 1;
      const trace = (points, lift) => {
        let drawing = false;
        let previous = null;
        points.forEach((border, index) => {
          if (pointStep > 1 && index % pointStep) return;
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
      const borderAlpha = colors.borderAlpha ?? (this.variant === "observatory" ? .38 : .28);
      const borderWidth = colors.borderWidth ?? (this.width < 520 ? .48 : .62);
      strokeLines(this.boundaryLines, colors.border, borderWidth, borderAlpha);
      if (this.variant !== "observatory") return;
      const route = ROUTES[this.route] || ROUTES.auto;
      const activeKeys = this.annotationFilter ? Array.from(this.annotationFilter) : route.nodes;
      const activeCountryIds = new Set(activeKeys.map((key) => LOCATIONS[key] && LOCATIONS[key].countryId).filter(Boolean));
      const activeLines = this.targetBoundaryLines.filter((line) => activeCountryIds.has(line.countryId));
      // A diffused pass gives the active countries a little depth; the crisp
      // pass keeps borders legible at the close-up measurement zoom.
      strokeLines(activeLines, colors.borderGlow, this.width < 520 ? 2.6 : 3.4, .24, colors.borderGlow, this.cameraMoving ? 0 : 9);
      strokeLines(activeLines, colors.borderHot, this.width < 520 ? .82 : 1.05, .72);
    }

    drawLand(colors) {
      const context = this.context;
      if (!this.landPolygons.length) return;
      const horizon = 0.012;
      const findHorizon = (from, to) => {
        let low = 0;
        let high = 1;
        const visibleAtStart = dot(from, this.currentBasis.forward) > horizon;
        for (let step = 0; step < 12; step += 1) {
          const middle = (low + high) / 2;
          if ((dot(slerp(from, to, middle), this.currentBasis.forward) > horizon) === visibleAtStart) low = middle;
          else high = middle;
        }
        return this.projectVector(slerp(from, to, (low + high) / 2), 1.001);
      };
      const pathForRing = (ring) => {
        if (ring.length < 3) return [];
        const projected = ring.map((point) => this.projectVector(point, 1.001));
        const firstHidden = projected.findIndex((point) => point.z <= horizon);
        if (firstHidden < 0) return [projected];
        const segments = [];
        let segment = [];
        for (let step = 0; step < projected.length; step += 1) {
          const index = (firstHidden + step) % projected.length;
          const nextIndex = (index + 1) % projected.length;
          const current = projected[index];
          const next = projected[nextIndex];
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
        const steps = Math.max(1, Math.ceil(Math.abs(arc) * globeRadius / 3));
        for (let step = 1; step <= steps; step += 1) {
          const angle = endAngle + arc * step / steps;
          landPath.lineTo(globeX + Math.cos(angle) * globeRadius, globeY + Math.sin(angle) * globeRadius);
        }
        landPath.closePath();
      };

      const globeX = this.centerX;
      const globeY = this.centerY;
      const globeRadius = this.radius * this.zoom;
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
      traceLand();
      context.save();
      // The coastline atlas has no elevation tiles. Draw a short offset
      // silhouette beneath the land, then light the upper surface separately.
      // This produces a readable embossed edge at normal map size without
      // inventing topographic texture.
      const relief = this.options.landDepth === false ? 0 : clamp(globeRadius * .014, 2, 5);
      context.save();
      context.translate(relief * .2, relief * .52);
      context.fillStyle = colors.landRelief || "rgba(12,22,34,.64)";
      context.globalAlpha = .64;
      if (relief) context.fill(landPath, "evenodd");
      context.restore();

      context.fillStyle = landLight;
      context.globalAlpha = colors.landAlpha ?? .98;
      context.shadowColor = (this.cameraMoving && !this.options.stableGeography) || !relief || this.options.softLandShadow === false ? "transparent" : colors.landRelief || "rgba(12,22,34,.52)";
      context.shadowBlur = this.options.softLandShadow === false || this.cameraMoving && !this.options.stableGeography ? 0 : relief * .92;
      context.shadowOffsetX = relief * .12;
      context.shadowOffsetY = relief * .36;
      context.fill(landPath, "evenodd");
      context.shadowColor = "transparent";
      context.shadowBlur = 0;
      context.shadowOffsetX = 0;
      context.shadowOffsetY = 0;
      context.globalAlpha = relief ? .48 : .20;
      context.lineWidth = clamp(globeRadius * .0032, .8, 1.3);
      context.strokeStyle = landEdge;
      if (!this.cameraMoving || this.options.stableGeography) context.stroke(landPath);
      context.restore();
    }

    routeLinks() {
      const route = ROUTES[this.route];
      const activeRoute = this.route === "auto" && this.autoExitRouteKey
        ? ROUTES[this.autoExitRouteKey]
        : route;
      const links = (activeRoute || route).links.filter((link) =>
        link.every((key) => this.availableLocationKeys.has(key))
      );
      const firstHop = this.activeRouteNodeKeys().find((key) => this.availableLocationKeys.has(key));
      if (this.userLocation && firstHop) links.unshift(["user", firstHop]);
      return links;
    }

    trafficStreams(colors) {
      const english = this.language === "en";
      const windowsHome = document.documentElement.dataset.atlasProfile === "windows";
      const dark = document.documentElement.dataset.theme !== "light";
      const contrast = windowsHome
        ? dark
          ? { upload: "#FF7798", uploadHot: "#FFE2EA", download: "#FFCE55", downloadHot: "#FFF4CB", control: "#AF8CFF", controlHot: "#EFE5FF" }
          : { upload: "#FF5278", uploadHot: "#FFCDDD", download: "#F6AD11", downloadHot: "#FFE9A6", control: "#824BFF", controlHot: "#D9C5FF" }
        : colors;
      return [
        { id: "upload", label: english ? "Outbound data" : "Исходящие данные", direction: "forward", color: contrast.upload || contrast.routeHot, bright: contrast.uploadHot || contrast.routeHot, lane: .07, duration: 3400, phase: 0 },
        { id: "download", label: english ? "Return traffic" : "Ответный трафик", direction: "reverse", color: contrast.download || contrast.route, bright: contrast.downloadHot || contrast.routeHot, lane: .17, duration: 3000, phase: .34 },
        { id: "control", label: english ? "Service traffic" : "Служебный трафик", direction: "forward", color: contrast.control || (dark ? "#D58CFF" : "#E052C0"), bright: contrast.controlHot || contrast.routeHot, lane: .27, duration: 3800, phase: .68 }
      ];
    }

    projectLanePoint(fromKey, toKey, amount, laneOffset) {
      const fromLocation = fromKey === "user" ? this.userLocation : LOCATIONS[fromKey];
      const toLocation = toKey === "user" ? this.userLocation : LOCATIONS[toKey];
      if (!fromLocation || !toLocation) return { x: 0, y: 0, z: -1 };
      const from = vector(fromLocation.lat, fromLocation.lon);
      const to = vector(toLocation.lat, toLocation.lon);
      const start = this.projectVector(from, 1.008);
      const end = this.projectVector(to, 1.008);
      const point = this.projectVector(slerp(from, to, amount), 1.008);
      const distance = Math.hypot(end.x - start.x, end.y - start.y);
      // A shared geographic anchor at either end, with three visibly different
      // bows above the surface. Reversing a flow never changes its lane.
      return { x: point.x, y: point.y - Math.sin(amount * Math.PI) * distance * laneOffset, z: point.z };
    }

    drawArc(fromKey, toKey, stream) {
      const context = this.context;
      const points = [];
      const segments = 80;
      for (let index = 0; index <= segments; index += 1)
        points.push(this.projectLanePoint(fromKey, toKey, index / segments, stream.lane));
      context.save();
      context.beginPath();
      let drawing = false;
      points.forEach((point) => {
        if (point.z <= -.02) { drawing = false; return; }
        if (!drawing) context.moveTo(point.x, point.y);
        else context.lineTo(point.x, point.y);
        drawing = true;
      });
      context.lineCap = "round";
      context.lineJoin = "round";
      const emphasized = this.hoveredTrafficStream === stream.id || this.inspectingStream === stream.id;
      const dark = document.documentElement.dataset.theme !== "light";
      // A faint colored halo and a continuous satin core keep the three
      // lanes vivid. No offset outline that makes them look like wires.
      context.strokeStyle = stream.color;
      context.globalAlpha = emphasized ? .24 : dark ? .18 : .13;
      context.lineWidth = emphasized ? 7 : 5.8;
      context.shadowBlur = 0;
      context.stroke();
      const sheen = context.createLinearGradient(points[0].x, points[0].y, points[segments].x, points[segments].y);
      sheen.addColorStop(0, stream.color);
      sheen.addColorStop(.22, stream.color);
      sheen.addColorStop(.5, stream.bright);
      sheen.addColorStop(.78, stream.color);
      sheen.addColorStop(1, stream.color);
      context.strokeStyle = sheen;
      context.globalAlpha = 1;
      context.lineWidth = emphasized ? 3.1 : 2.3;
      context.stroke();
      if (emphasized) {
        context.globalAlpha = .9;
        context.strokeStyle = "#FFFFFF";
        context.lineWidth = .75;
        context.shadowBlur = 0;
        context.stroke();
      }
      context.globalAlpha = 1;
      context.shadowBlur = 0;
      context.restore();
      for (let index = 1; index < points.length; index += 1) {
        if (points[index - 1].z > .015 && points[index].z > .015)
          this.routeHitSegments.push({ a: points[index - 1], b: points[index], stream, fromKey, toKey });
      }
    }

    drawRoutes(colors) {
      this.routeHitSegments = [];
      const links = this.routeLinks();
      if (!links.length) return;
      const streams = this.trafficStreams(colors).filter((stream) =>
        this.inspectingStream == null || stream.id === this.inspectingStream);
      links.forEach((link) => streams.forEach((stream) => this.drawArc(link[0], link[1], stream)));
    }

    drawTraffic(colors, time) {
      this.packetHits = [];
      const links = this.routeLinks();
      const streams = this.trafficStreams(colors).filter((stream) =>
        this.inspectingStream == null || stream.id === this.inspectingStream);

      if (!links.length) {
        // Without location consent, keep the user's origin private. Show the
        // three stream types at the confirmed exit without inventing a map path.
        const exit = this.activeRouteNodeKeys().find((key) => this.availableLocationKeys.has(key));
        if (!exit) return;
        const point = this.projectLocation(LOCATIONS[exit], 1.018);
        if (point.z <= .015) return;
        const context = this.context;
        streams.forEach((stream, streamIndex) => {
          const phase = this.reducedMotion.matches ? .5 : ((time - this.trafficStartedAt) / (stream.duration * 2) + stream.phase) % 1;
          const orbit = 9 + streamIndex * 5;
          context.save();
          context.globalAlpha = .22;
          context.beginPath();
          context.arc(point.x, point.y, orbit, 0, TAU);
          context.strokeStyle = stream.color;
          context.lineWidth = 1;
          context.stroke();
          context.restore();
          for (let packet = 0; packet < (this.reducedMotion.matches ? 1 : 4); packet += 1) {
            const angle = (phase + packet / 4) * TAU;
            const x = point.x + Math.cos(angle) * orbit;
            const y = point.y + Math.sin(angle) * orbit;
            this.drawPacketDot(x, y, stream, packet === 0, .82);
            this.packetHits.push({ x, y, stream, fromKey: exit, toKey: exit, sequence: packet + 1, hop: 0, noOrigin: true });
          }
        });
        return;
      }

      const elapsed = Math.max(0, time - this.trafficStartedAt);
      const packetCount = this.reducedMotion.matches ? 1 : 5;
      streams.forEach((stream) => {
        const directedLinks = stream.direction === "reverse"
          ? links.slice().reverse().map((link) => [link[1], link[0]])
          : links;
        const lane = stream.lane;
        for (let packet = 0; packet < packetCount; packet += 1) {
          const progress = this.reducedMotion.matches
            ? .52
            : ((elapsed / (stream.duration * directedLinks.length) + packet / packetCount + stream.phase) % 1);
          const position = progress * directedLinks.length;
          const linkIndex = Math.min(directedLinks.length - 1, Math.floor(position));
          const amount = position - linkIndex;
          const [fromKey, toKey] = directedLinks[linkIndex];
          const point = this.projectLanePoint(fromKey, toKey, amount, lane);
          if (point.z <= -.02) continue;
          if (!this.reducedMotion.matches && packet === 0)
            this.drawPacketTrail(directedLinks, position, lane, stream, elapsed);
          this.drawPacketDot(point.x, point.y, stream, packet % 4 === 0, 1, .86 + (packet % 3) * .14);
          this.packetHits.push({ x: point.x, y: point.y, stream, fromKey, toKey, sequence: packet + 1, hop: linkIndex + 1, noOrigin: false });
        }
      });
    }

    drawPacketTrail(directedLinks, position, lane, stream, elapsed) {
      const context = this.context;
      const points = [];
      const linkCount = directedLinks.length;
      const trailLength = .13;
      const samples = 10;
      for (let step = samples; step >= 0; step -= 1) {
        // Keep the trail on the current pass. Wrapping its samples from the
        // first hop to the final hop draws a detached chord across the map.
        const sample = position - trailLength * step / samples;
        if (sample < 0 || sample >= linkCount) continue;
        const linkIndex = Math.floor(sample);
        const amount = sample - linkIndex;
        const [fromKey, toKey] = directedLinks[linkIndex];
        points.push(this.projectLanePoint(fromKey, toKey, amount, lane));
      }

      context.save();
      context.lineCap = "round";
      context.lineJoin = "round";
      context.shadowColor = stream.color;
      context.shadowBlur = 2;
      for (let index = 1; index < points.length; index += 1) {
        const from = points[index - 1], to = points[index];
        if (from.z <= -.02 || to.z <= -.02) continue;
        const intensity = index / (points.length - 1);
        context.beginPath();
        context.moveTo(from.x, from.y);
        context.lineTo(to.x, to.y);
        context.globalAlpha = intensity * .72;
        context.strokeStyle = intensity > .7 ? stream.bright : stream.color;
        context.lineWidth = .45 + intensity * .95;
        context.stroke();
      }
      context.restore();
    }

    drawPacketDot(x, y, stream, head, alpha, size = 1) {
      const context = this.context;
      const key = stream.color + "|" + stream.bright + "|" + head;
      let sprite = this.packetSprites.get(key);
      if (!sprite) {
        sprite = document.createElement("canvas");
        sprite.width = sprite.height = 48;
        const paint = sprite.getContext("2d");
        paint.scale(2, 2);
        const radius = head ? 2.25 : 1.72;
        const halo = paint.createRadialGradient(12, 12, .6, 12, 12, 5.7);
        halo.addColorStop(0, stream.color + "58");
        halo.addColorStop(1, stream.color + "00");
        paint.fillStyle = halo;
        paint.fillRect(4, 4, 16, 16);
        const sphere = paint.createRadialGradient(11, 10.8, .1, 12, 12, radius);
        sphere.addColorStop(0, "#FFFFFF");
        sphere.addColorStop(.32, stream.bright);
        sphere.addColorStop(1, stream.color);
        paint.beginPath();
        paint.arc(12, 12, radius, 0, TAU);
        paint.fillStyle = sphere;
        paint.shadowColor = stream.color;
        paint.shadowBlur = head ? 3 : 1.5;
        paint.fill();
        if (head) {
          paint.beginPath();
          paint.arc(11.3, 10.9, .5, 0, TAU);
          paint.fillStyle = "#FFFFFF";
          paint.fill();
        }
        this.packetSprites.set(key, sprite);
      }
      context.globalAlpha = alpha;
      context.drawImage(sprite, x - 12 * size, y - 12 * size, 24 * size, 24 * size);
      context.globalAlpha = 1;
    }

    findPacketAt(pointer, tolerance) {
      let nearest = null;
      let nearestDistance = tolerance;
      this.packetHits.forEach((packet) => {
        const distance = Math.hypot(packet.x - pointer.x, packet.y - pointer.y);
        if (distance < nearestDistance) { nearest = packet; nearestDistance = distance; }
      });
      return nearest;
    }

    findLineAt(pointer, tolerance) {
      let nearest = null;
      let closest = tolerance;
      this.routeHitSegments.forEach((segment) => {
        const dx = segment.b.x - segment.a.x, dy = segment.b.y - segment.a.y;
        const t = clamp(((pointer.x - segment.a.x) * dx + (pointer.y - segment.a.y) * dy) / Math.max(.001, dx * dx + dy * dy), 0, 1);
        const distance = Math.hypot(pointer.x - segment.a.x - dx * t, pointer.y - segment.a.y - dy * t);
        if (distance < closest) { closest = distance; nearest = { ...segment, isLine: true }; }
      });
      return nearest;
    }

    setHoveredTrafficStream(streamId) {
      const next = streamId || null;
      if (this.hoveredTrafficStream === next) return;
      this.hoveredTrafficStream = next;
      this.overlayDirty = true;
      this.start();
    }

    showTrafficHint() {
      const hint = this.root.querySelector("[data-traffic-hint]");
      if (!hint) return;
      hint.textContent = this.language === "en"
        ? "Double-click a line to inspect and highlight its flow"
        : "Для описания потока нажмите на линию дважды";
      hint.hidden = false;
      clearTimeout(this.hintTimer);
      this.hintTimer = setTimeout(() => { hint.hidden = true; }, 4200);
    }

    resumeTraffic() {
      this.inspectingStream = null;
      this.root.classList.remove("is-inspecting");
      const mainPanel = this.root.querySelector(".home-panel-shell");
      const compactMode = this.root.classList.contains("is-zoomed") || this.root.classList.contains("is-immersive");
      mainPanel?.toggleAttribute("inert", compactMode);
      mainPanel?.setAttribute("aria-hidden", compactMode ? "true" : "false");
      this.hoveredTrafficStream = null;
      const inspector = this.root.querySelector("[data-traffic-inspector]");
      if (inspector) {
        const restoreFocus = inspector.contains(document.activeElement);
        inspector.hidden = true;
        if (restoreFocus) this.canvas.focus({ preventScroll: true });
      }
      this.overlayDirty = true;
      this.start();
    }

    showTrafficInspector(line) {
      const inspector = this.root.querySelector("[data-traffic-inspector]");
      if (!inspector) return;
      const english = this.language === "en";
      const placeName = (key) => key === "user" ? this.copy(this.userLocation?.city || (english ? "Your network" : "Ваша сеть")) : this.copy(LOCATIONS[key]?.city || key);
      const from = line.stream.direction === "reverse" ? line.toKey : line.fromKey;
      const to = line.stream.direction === "reverse" ? line.fromKey : line.toKey;
      const explanations = english ? {
        upload: "Requests and data travel from your device to the exit node: opening a page, sending a message or uploading a file.",
        download: "Responses travel back to your device: page content, incoming messages and downloaded files.",
        control: "Small service exchanges keep a connection working. DNS resolves names; other control messages coordinate the tunnel."
      } : {
        upload: "Запросы и данные идут от устройства к выходному узлу: открытие страницы, отправка сообщения или загрузка файла на сервер.",
        download: "Ответы возвращаются к устройству: содержимое страниц, входящие сообщения и скачиваемые файлы.",
        control: "Небольшие служебные обмены поддерживают соединение. DNS определяет адрес по имени, остальные сообщения согласуют работу туннеля."
      };
      inspector.style.setProperty("--traffic-accent", line.stream.color);
      inspector.querySelector("[data-inspector-kind]").textContent = english ? "flow highlighted" : "поток выделен";
      inspector.querySelector("[data-inspector-title]").textContent = line.stream.label.toLowerCase();
      inspector.querySelector("[data-inspector-path]").textContent = placeName(from) + " → " + placeName(to);
      inspector.querySelector("[data-inspector-body]").textContent = explanations[line.stream.id];
      inspector.querySelector("[data-inspector-item]").textContent = english ? "Only this flow is highlighted on the map" : "На карте выделен только этот поток";
      inspector.querySelector("[data-inspector-note]").textContent = english
        ? "The moving points illustrate traffic direction; packet contents are not captured."
        : "Движущиеся точки показывают направление потока. Содержимое пакетов не считывается.";
      const resume = inspector.querySelector("[data-inspector-resume]");
      resume.textContent = english ? "Show all traffic lines" : "Показать все линии";
      resume.onclick = () => this.resumeTraffic();
      const opening = inspector.hidden;
      this.root.classList.add("is-inspecting");
      const mainPanel = this.root.querySelector(".home-panel-shell");
      mainPanel?.setAttribute("inert", "");
      mainPanel?.setAttribute("aria-hidden", "true");
      inspector.hidden = false;
      this.placeTrafficInspector(inspector);
      if (opening) {
        inspector.animate(this.reducedMotion.matches
          ? [{ opacity: .35 }, { opacity: 1 }]
          : [{ opacity: 0, transform: "translateY(8px) scale(.98)" }, { opacity: 1, transform: "translateY(0) scale(1)" }],
          { duration: 200, easing: "cubic-bezier(.23,1,.32,1)" });
        resume.focus({ preventScroll: true });
      }
      const hint = this.root.querySelector("[data-traffic-hint]");
      if (hint) hint.hidden = true;
    }

    placeTrafficInspector(inspector) {
      const width = inspector.offsetWidth, height = inspector.offsetHeight;
      const candidates = [{ x: 22, y: Math.max(70, this.height - height - 86) }, { x: 22, y: 70 }];
      if (this.width >= 1180) candidates.unshift({ x: this.width - width - 64, y: Math.max(70, (this.height - height) / 2) });
      if (this.root.classList.contains("is-zoomed") || this.root.classList.contains("is-immersive"))
        candidates.push({ x: Math.max(22, this.width - width - 22), y: 70 });
      for (const candidate of candidates) {
        candidate.score = Object.values(this.projectedNodes).reduce((score, point) => {
          if (point.z <= 0) return score;
          const overlapX = Math.max(0, Math.min(candidate.x + width, point.x + 100) - Math.max(candidate.x, point.x - 100));
          const overlapY = Math.max(0, Math.min(candidate.y + height, point.y + 44) - Math.max(candidate.y, point.y - 44));
          return score + overlapX * overlapY;
        }, 0);
      }
      candidates.sort((a, b) => a.score - b.score);
      inspector.style.left = candidates[0].x + "px";
      inspector.style.top = candidates[0].y + "px";
      inspector.style.bottom = "auto";
    }

    renderOverlay(colors) {
      const liveContext = this.context;
      this.context = this.overlayContext;
      try {
        this.context.clearRect(0, 0, this.width, this.height);
        this.drawRoutes(colors);
        this.drawNodes(colors);
      } finally {
        this.context = liveContext;
      }
      this.overlayDirty = false;
    }

    activeRouteNodeKeys() {
      if (this.route === "auto") {
        if (this.autoExitRouteKey && ROUTES[this.autoExitRouteKey]) return ROUTES[this.autoExitRouteKey].nodes;
        const key = COUNTRY_EXIT_KEYS[this.autoExitCountry];
        return key && this.availableLocationKeys.has(key) ? [key] : [];
      }
      return ROUTES[this.route].nodes;
    }

    drawNode(key, location, colors, kind) {
      const context = this.context;
      const projected = this.projectLocation(location, 1.008);
      this.projectedNodes[key] = projected;
      if (projected.z <= 0.015) return;
      const active = kind === "user" || this.activeRouteNodeKeys().includes(key);
      const candidate = this.route === "auto" && ROUTES.auto.nodes.includes(key);
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
      const nodeRadius = hovered ? 5.5 : active ? 4.6 : candidate ? 3.8 : 2.7;
      context.arc(projected.x, projected.y, nodeRadius, 0, TAU);
      context.fillStyle = kind === "user" ? colors.routeHot : active || hovered || candidate ? colors.nodeCore : colors.landDim;
      context.shadowColor = kind === "user" ? colors.routeHot : active || hovered || candidate ? colors.nodeCore : colors.border;
      context.shadowBlur = hovered ? 18 : active ? 12 : 3;
      context.fill();
      context.shadowBlur = 0;
      if (active || hovered || candidate) {
        context.beginPath();
        context.arc(projected.x, projected.y, nodeRadius, 0, TAU);
        context.lineWidth = document.documentElement.dataset.theme === "light" ? 1.8 : 1.5;
        context.strokeStyle = colors.node;
        context.stroke();
      }
    }

    drawLabel(key, colors, occupied) {
      const point = this.projectedNodes[key];
      if (!point || point.z <= .03) return;
      if (point.x < 0 || point.x > this.width || point.y < 0 || point.y > this.height) return;
      if (this.annotationFilter && key !== "user" && !this.annotationFilter.has(key)) return;
      const active = key === "user" || this.activeRouteNodeKeys().includes(key);
      const autoCandidate = this.route === "auto" && !this.autoExitRouteKey && ROUTES.auto.nodes.includes(key);
      if (!active && !autoCandidate && this.hovered !== key) return;
      const context = this.context;
      const location = key === "user" ? this.userLocation : LOCATIONS[key];
      const compact = this.width < 820;
      const labelScale = this.options.labelScale || 1;
      const offset = compact ? COMPACT_LABEL_OFFSETS[key] : LABEL_OFFSETS[key];
      const detailed = key === "user" || active || autoCandidate || this.hovered === key;
      const title = key === "user" ? (this.copy(location.city) || this.copy("ваша сеть")) : this.copy(detailed ? location.city : location.country);
      const countryNames = { RU: "Россия", NL: "Нидерланды", DE: "Германия", FI: "Финляндия", IT: "Италия" };
      // Country annotations keep the same city/country format in manual and automatic modes.
      const meta = key === "user" ? this.copy(countryNames[location.country?.toUpperCase()] || location.country || "вход в сеть") : detailed ? this.copy(location.country) : null;
      const titleFontSize = Math.round(10 * labelScale);
      const metaFontSize = Math.round(8 * labelScale);
      const inset = Math.round(6 * labelScale);
      const height = Math.round((meta ? 29 : 20) * labelScale);
      const fontFamily = this.options.labelFontFamily || '"Segoe UI", sans-serif';
      const titleFont = (active ? "600 " : "500 ") + titleFontSize + "px " + fontFamily;
      const metaFont = "500 " + metaFontSize + "px " + fontFamily;
      context.save();
      context.font = titleFont;
      const titleWidth = context.measureText(title).width;
      context.font = metaFont;
      const width = Math.max(titleWidth, meta ? context.measureText(meta).width : 0) + inset * 2;
      // Prefer nearby labels; score all alternatives instead of falling back
      // to (8,8), which detached crowded user labels from their actual pin.
      const alternatives = [];
      const cluster = this.overviewLabelPlacements?.get(key);
      if (cluster) alternatives.push({ x: cluster.right - width - point.x, y: cluster.top + height / 2 - point.y, align: "left", cluster: true, ring: 0 });
      if (offset) alternatives.push({ ...offset, preferred: true, ring: 0 });
      const gap = Math.max(10, Math.round(9 * labelScale));
      const rowStep = height + gap;
      const columnStep = width * .62 + gap;
      for (let ring = 0; ring < 6; ring += 1) {
        const extra = Math.round(ring * Math.max(18, 22 * labelScale));
        for (let row = -5; row <= 5; row += 1) {
          alternatives.push({ x: gap + extra, y: row * rowStep, align: "left", ring: ring + 1 });
          alternatives.push({ x: -gap - extra, y: row * rowStep, align: "right", ring: ring + 1 });
        }
        for (let column = -3; column <= 3; column += 1) {
          const x = -width / 2 + column * columnStep;
          alternatives.push({ x, y: -height / 2 - gap - extra, align: "left", ring: ring + 1 });
          alternatives.push({ x, y: height / 2 + gap + extra, align: "left", ring: ring + 1 });
        }
      }
      const scorePlacement = (candidate) => {
        const rawLeft = point.x + candidate.x - (candidate.align === "right" ? width : 0);
        const rawTop = point.y + candidate.y - height / 2;
        const left = clamp(rawLeft, 8, Math.max(8, this.width - width - 8));
        const top = clamp(rawTop, 8, Math.max(8, this.height - height - 8));
        const overlap = occupied.reduce((sum, label) => sum +
          Math.max(0, Math.min(left + width + 4, label.left + label.width + 4) - Math.max(left - 4, label.left - 4)) *
          Math.max(0, Math.min(top + height + 4, label.top + label.height + 4) - Math.max(top - 4, label.top - 4)), 0);
        const dx = point.x - clamp(point.x, left, left + width);
        const dy = point.y - clamp(point.y, top, top + height);
        const coversPin = Object.entries(this.projectedNodes).some(([otherKey, node]) => otherKey !== key && node.z > .03 &&
          node.x > left - 7 && node.x < left + width + 7 && node.y > top - 7 && node.y < top + height + 7);
        const clipped = Math.abs(rawLeft - left) + Math.abs(rawTop - top);
        // Collisions dominate distance so a nearby-but-overlapping label can
        // never beat a clear placement farther around the pin.
        const coversArc = this.routeHitSegments.some(({ a, b }) => [a, b].some(p => p.x > left - 8 && p.x < left + width + 8 && p.y > top - 8 && p.y < top + height + 8));
          const end = { x: clamp(point.x, left + 5, left + width - 5), y: clamp(point.y, top + 5, top + height - 5) };
          const intersects = (a, b, c, d) => {
            const side = (p, q, r) => (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x);
            const within = (p, q, r) => r.x >= Math.min(p.x, q.x) - .01 && r.x <= Math.max(p.x, q.x) + .01 && r.y >= Math.min(p.y, q.y) - .01 && r.y <= Math.max(p.y, q.y) + .01;
            const s1 = side(a, b, c), s2 = side(a, b, d), s3 = side(c, d, a), s4 = side(c, d, b);
            return s1 * s2 < 0 && s3 * s4 < 0 || Math.abs(s1) < .01 && within(a, b, c) || Math.abs(s2) < .01 && within(a, b, d) || Math.abs(s3) < .01 && within(c, d, a) || Math.abs(s4) < .01 && within(c, d, b);
          };
          const crossesLeader = occupied.some(label => label.leader && intersects(point, end, label.leader[0], label.leader[1]));
          const crossesLabel = occupied.some(label => {
            const l = label.left - 6, r = label.left + label.width + 6, t = label.top - 6, b = label.top + label.height + 6;
            return intersects(point, end, { x: l, y: t }, { x: r, y: t }) || intersects(point, end, { x: r, y: t }, { x: r, y: b }) || intersects(point, end, { x: r, y: b }, { x: l, y: b }) || intersects(point, end, { x: l, y: b }, { x: l, y: t });
          });
          const coversLeader = occupied.some(label => {
            if (!label.leader) return false;
            const [a, b] = label.leader, l = left - 6, r = left + width + 6, t = top - 6, bottom = top + height + 6;
            return intersects(a, b, { x: l, y: t }, { x: r, y: t }) || intersects(a, b, { x: r, y: t }, { x: r, y: bottom }) || intersects(a, b, { x: r, y: bottom }, { x: l, y: bottom }) || intersects(a, b, { x: l, y: bottom }, { x: l, y: t });
          });
          const clearCluster = candidate.cluster && !overlap && !coversPin && !crossesLabel && !coversLeader && !crossesLeader;
          return { left, top, overlap, coversPin, coversArc, crossesLeader, crossesLabel, coversLeader, score: overlap * 10000000 + (coversPin || crossesLabel || coversLeader ? 1000000000 : 0) + (crossesLeader ? 10000000 : 0) + (coversArc ? 250000 : 0) + clipped * 3000 + dx * dx + dy * dy + (candidate.ring || 0) - (clearCluster ? 100000000 : 0) };
      };
      const cachedIndex = this.labelPlacements.get(key);
      const cached = cachedIndex != null && alternatives[cachedIndex] ? scorePlacement(alternatives[cachedIndex]) : null;
      const reuse = cached && cached.overlap === 0 && !cached.coversPin && !cached.coversArc && !cached.crossesLeader && !cached.crossesLabel && !cached.coversLeader;
      const scored = reuse ? [] : alternatives.map(scorePlacement);
      let placementIndex = 0;
      let bestScore = Infinity;
      for (let index = 0; index < scored.length; index += 1) {
        if (scored[index].score < bestScore) {
          bestScore = scored[index].score;
          placementIndex = index;
        }
      }
      if (reuse) placementIndex = cachedIndex;
      this.labelPlacements.set(key, placementIndex);
        // A smaller scene may have no room for a secondary callout. Never put
        // it on top of another label or an action; keep its map marker visible.
        const chosen = reuse ? cached : scored[placementIndex];
        if (chosen.overlap > 0 || chosen.coversPin || chosen.crossesLabel || chosen.coversLeader || chosen.crossesLeader) { context.restore(); return; }
      const left = (reuse ? cached : scored[placementIndex]).left;
      const top = (reuse ? cached : scored[placementIndex]).top;
      const endX = clamp(point.x, left + 5, left + width - 5);
      const endY = clamp(point.y, top + 5, top + height - 5);
      context.beginPath();
      const distance = Math.max(1, Math.hypot(endX - point.x, endY - point.y));
      context.moveTo(point.x + (endX - point.x) * 7 / distance, point.y + (endY - point.y) * 7 / distance);
      context.lineTo(endX, endY);
      context.strokeStyle = colors.labelLeader || (key === "user" ? colors.node : active ? colors.nodeCore : colors.node);
      context.lineWidth = 1;
      context.globalAlpha = .72;
      context.stroke();
      const labelBox = { key, left, top, width, height, leader: [point, { x: endX, y: endY }] };
      occupied.push(labelBox);
      this.labelBoxes.push(labelBox);
      context.beginPath();
      context.roundRect(left, top, width, height, Math.round(3 * labelScale));
      context.fillStyle = colors.labelBg;
      context.globalAlpha = 1;
      context.shadowBlur = 0;
      context.fill();
      context.shadowBlur = 0;
      context.strokeStyle = this.hovered === key ? colors.borderHot : colors.labelBorder || colors.border;
      context.globalAlpha = this.hovered === key ? .8 : .55;
      context.lineWidth = .8;
      context.stroke();
      context.textBaseline = "middle";
      context.textAlign = "left";
      context.fillStyle = colors.label;
      context.globalAlpha = 1;
      context.font = titleFont;
      context.fillText(title, left + inset, top + (meta ? Math.round(10 * labelScale) : height / 2));
      if (meta) {
        context.fillStyle = colors.labelMuted;
        context.font = metaFont;
        context.fillText(meta, left + inset, top + Math.round(22 * labelScale));
      }
      context.restore();
    }

    drawNodes(colors) {
      this.projectedNodes = {};
      (this.route === "auto" ? ROUTES.auto.nodes : LOCATION_ORDER).forEach((key) => {
        if (this.availableLocationKeys.has(key)) this.drawNode(key, LOCATIONS[key], colors, "server");
      });
      if (this.userLocation) this.drawNode("user", this.userLocation, colors, "user");
      this.labelBoxes = [];
      const canvasRect = this.canvas.getBoundingClientRect();
      const occupied = [];
      this.root.querySelectorAll(".home-panel-shell, .home-compact, [data-traffic-inspector]").forEach(control => {
        if (control.hidden || control.getAttribute("aria-hidden") === "true" || getComputedStyle(control).display === "none" || getComputedStyle(control).visibility === "hidden") return;
        const rect = control.getBoundingClientRect();
        if (rect.width && rect.height) occupied.push({ left: rect.left - canvasRect.left - 10, top: rect.top - canvasRect.top - 10, width: rect.width + 20, height: rect.height + 20 });
      });
      const labelOrder = [];
      this.overviewLabelPlacements = new Map();
      const overview = (this.route === "auto" ? ROUTES.auto.nodes : LOCATION_ORDER).filter(key => this.availableLocationKeys.has(key) &&
        this.projectedNodes[key]?.z > .03 && this.projectedNodes[key].x >= 0 && this.projectedNodes[key].x <= this.width &&
        this.projectedNodes[key].y >= 0 && this.projectedNodes[key].y <= this.height)
        .sort((a, b) => this.projectedNodes[a].y - this.projectedNodes[b].y);
      // Lay out a close country cluster together. A shared column preserves
      // pin order, so later countries cannot be squeezed behind earlier cards.
      if (this.route === "auto" && !this.autoExitRouteKey && overview.length >= 3 &&
          Math.max(...overview.map(key => this.projectedNodes[key].x)) - Math.min(...overview.map(key => this.projectedNodes[key].x)) < this.radius * this.zoom * .65) {
        const scale = this.options.labelScale || 1;
        const heights = overview.map(() => Math.round(29 * scale));
        const totalHeight = heights.reduce((sum, height) => sum + height, 0) + (overview.length - 1) * 10;
        let top = clamp(overview.reduce((sum, key) => sum + this.projectedNodes[key].y, 0) / overview.length - totalHeight / 2, 8, Math.max(8, this.height - totalHeight - 8));
        const right = Math.min(...overview.map(key => this.projectedNodes[key].x)) - 18;
        overview.forEach((key, index) => {
          this.overviewLabelPlacements.set(key, { right, top });
          top += heights[index] + 10;
        });
      }
      const addLabel = (key) => { if (key && !labelOrder.includes(key)) labelOrder.push(key); };
      if (this.userLocation) addLabel("user");
      this.activeRouteNodeKeys().forEach(addLabel);
      LOCATION_ORDER.slice().sort((a, b) => (this.projectedNodes[a]?.y || 0) - (this.projectedNodes[b]?.y || 0)).forEach((key) => {
        if (this.availableLocationKeys.has(key)) addLabel(key);
      });
      addLabel(this.hovered);
      labelOrder.forEach((key) => {
        if (key === "user" ? this.userLocation : this.availableLocationKeys.has(key))
          this.drawLabel(key, colors, occupied);
      });
    }

    renderStatic(colors) {
      const liveContext = this.context;
      const context = this.staticContext;
      const liveZoom = this.zoom;
      const liveX = this.centerX, liveY = this.centerY;
      // Zoom is an affine change of the orthographic projection. Keep its
      // geography bitmap and let the compositor scale it during the wheel
      // animation. Coverage is checked before reuse, then the final camera
      // is rasterized again so no clipped edge or enlarged pixels remain.
      this.zoom = this.cameraMoving ? Math.min(this.zoom, this.targetZoom) : this.zoom;
      if (this.cameraMoving && this.targetZoom < liveZoom) {
        this.centerX = this.targetCenterX;
        this.centerY = this.targetCenterY;
      }
      this.staticCamera = { zoom: this.zoom, x: this.centerX, y: this.centerY };
      this.context = context;
      try {
        const padding = this.staticPadding || 0;
        context.clearRect(-padding, -padding, this.width + padding * 2, this.height + padding * 2);
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
        this.zoom = liveZoom;
        this.centerX = liveX;
        this.centerY = liveY;
      }
      this.staticDirty = false;
      this.staticRenderCount += 1;
    }

    placeStaticCanvas() {
      if (!this.staticCamera) return;
      const scale = this.zoom / this.staticCamera.zoom;
      const x = this.centerX - this.staticCamera.x * scale;
      const y = this.centerY - this.staticCamera.y * scale;
      this.staticCanvas.style.transformOrigin = `${this.staticPadding || 0}px ${this.staticPadding || 0}px`;
      this.staticCanvas.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
    }

    render(time) {
      this.frame = 0;
      if (!this.hostActive || !this.visible || document.hidden || !this.width) return;
      const wasCameraMoving = this.cameraMoving;
      const cameraMoving = this.dragging || Math.abs(this.targetCenterY - this.centerY) > .5 || Math.abs(this.targetCenterX - this.centerX) > .5 || Math.abs(this.targetZoom - this.zoom) > .006 || Math.abs(this.targetLon - this.centerLon) > .06 || Math.abs(this.targetLat - this.centerLat) > .06;
      this.cameraMoving = cameraMoving;
      if (cameraMoving !== this.interactionQuality) this.setInteractionQuality(cameraMoving);
      // Let requestAnimationFrame follow the display refresh rate so the traffic
      // and camera never fall to the old 30 fps idle-animation cap.
      const elapsed = this.lastFrame ? time - this.lastFrame : 16;
      const delta = Math.min(48, elapsed);
      this.lastFrame = time;
      const previousLon = this.centerLon;
      const previousLat = this.centerLat;
      const previousZoom = this.zoom;
      const previousCenterX = this.centerX;
      const previousCenterY = this.centerY;
      this.centerX = mix(this.centerX, this.targetCenterX, this.reducedMotion.matches ? 1 : 1 - Math.exp(-Math.max(1, delta) / ZOOM_EASE_MS));
      this.centerY = mix(this.centerY, this.targetCenterY, this.reducedMotion.matches ? 1 : 1 - Math.exp(-Math.max(1, delta) / ZOOM_EASE_MS));
      if (Math.abs(this.targetCenterX - this.centerX) < .1) this.centerX = this.targetCenterX;
      if (Math.abs(this.targetCenterY - this.centerY) < .1) this.centerY = this.targetCenterY;
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
      if (Math.abs(this.centerLon - previousLon) > .0001 || Math.abs(this.centerLat - previousLat) > .0001) {
        this.markMapDirty();
      } else if (Math.abs(this.centerY - previousCenterY) > .001 || Math.abs(this.centerX - previousCenterX) > .001 || Math.abs(this.zoom - previousZoom) > .0001) {
        this.overlayDirty = true;
      }
      if (this.staticCamera && this.targetZoom < this.staticCamera.zoom - .01) this.staticDirty = true;
      if (this.staticCamera) {
        const scale = this.zoom / this.staticCamera.zoom;
        const x = this.centerX - this.staticCamera.x * scale;
        const y = this.centerY - this.staticCamera.y * scale;
        const padding = (this.staticPadding || 0) * scale;
        const radius = this.radius * this.zoom + 2;
        const left = Math.max(0, this.centerX - radius), right = Math.min(this.width, this.centerX + radius);
        const top = Math.max(0, this.centerY - radius), bottom = Math.min(this.height, this.centerY + radius);
        // Center motion can uncover a bitmap edge even while scale increases.
        // Check in scene coordinates, without layout reads in the frame loop.
        if (x - padding > left + .25 || y - padding > top + .25 || x + this.width * scale + padding < right - .25 || y + this.height * scale + padding < bottom - .25)
          this.staticDirty = true;
        const affineSettled = this.zoom === this.targetZoom && this.centerX === this.targetCenterX && this.centerY === this.targetCenterY;
        if ((affineSettled && (scale !== 1 || x !== 0 || y !== 0)) || (wasCameraMoving && !cameraMoving))
          this.staticDirty = true;
      }
      this.currentBasis = this.basis();
      this.context.clearRect(0, 0, this.width, this.height);
      const colors = this.colors();
      if (this.staticDirty) this.renderStatic(colors);
      this.placeStaticCanvas();
      if (this.overlayDirty) this.renderOverlay(colors);
      // The browser composites the two persistent map layers. Only particle
      // pixels change on this canvas; no full globe bitmap copy per frame.
      if ((this.autoSelecting || this.trafficActive) && !this.reducedMotion.matches) {
        const selecting = this.autoSelecting && this.route === "auto";
        const keys = (selecting ? ROUTES.auto.nodes : this.activeRouteNodeKeys())
          .filter(key => this.availableLocationKeys.has(key));
        keys.forEach((key, index) => {
          const pulse = ((time || performance.now()) / (selecting ? 1600 : 2600) + index * .23) % 1;
          const point = this.projectedNodes[key];
          if (!point || point.z <= .03) return;
          this.context.save();
          this.context.globalAlpha = Math.sin(pulse * Math.PI) * (selecting ? .85 : .55);
          this.context.beginPath(); this.context.arc(point.x, point.y, 6 + pulse * (selecting ? 13 : 10), 0, TAU);
          this.context.strokeStyle = colors.node; this.context.lineWidth = 3.8; this.context.stroke();
          this.context.strokeStyle = colors.nodeCore; this.context.lineWidth = 2; this.context.stroke();
          this.context.restore();
        });
      }
      if (this.trafficActive) this.drawTraffic(colors, time || performance.now());
      else this.packetHits = [];
      this.renderCount += 1;
      // Complete one final frame after easing snaps to its target. This also
      // clears the moving state when reduced motion completes in a single step.
      if ((cameraMoving && !this.dragging) || Math.abs(this.targetCenterY - this.centerY) > .1 || Math.abs(this.targetCenterX - this.centerX) > .1 || Math.abs(this.targetLon - this.centerLon) > .01 || Math.abs(this.targetLat - this.centerLat) > .01 || Math.abs(this.targetZoom - this.zoom) > .002 || Math.abs(this.velocityLon) > .001 || ((this.trafficActive && this.trafficPausedAt == null || this.autoSelecting) && !this.reducedMotion.matches)) this.start();
    }

    start() {
      if (!this.frame && this.hostActive && this.visible && !document.hidden) this.frame = requestAnimationFrame((time) => this.render(time));
    }

    setHostActive(active) {
      if (this.hostActive === Boolean(active)) return;
      this.hostActive = Boolean(active);
      if (!this.hostActive) {
        if (this.frame) cancelAnimationFrame(this.frame);
        this.frame = 0;
        this.lastFrame = 0;
        this.dragging = false;
        this.activePointers.forEach((_, id) => {
          try { if (this.canvas.hasPointerCapture(id)) this.canvas.releasePointerCapture(id); } catch (_) { /* Native host may already be hidden. */ }
        });
        this.activePointers.clear();
        this.velocityLon = 0;
        this.hovered = null;
        this.hoveredTrafficStream = null;
        this.overlayDirty = true;
        if (this.tooltip) this.tooltip.hidden = true;
        clearTimeout(this.hintTimer);
        const hint = this.root.querySelector("[data-traffic-hint]");
        if (hint) hint.hidden = true;
        this.hostPausedAt = performance.now();
      } else {
        if (this.hostPausedAt && this.trafficActive) {
          const hiddenDuration = performance.now() - this.hostPausedAt;
          this.trafficStartedAt += hiddenDuration;
          if (this.trafficPausedAt != null) this.trafficPausedAt += hiddenDuration;
        }
        this.hostPausedAt = null;
        this.lastFrame = 0;
        this.resize();
        this.start();
      }
    }

    resumeHost() {
      this.setHostActive(true);
      // A native view can return before its intersection notification. Clear
      // a stale RAF handle and trust the explicitly shown Windows host surface.
      if (this.frame) cancelAnimationFrame(this.frame);
      this.frame = 0;
      this.lastFrame = 0;
      if (this.options.stableGeography) {
        const bounds = this.canvas.getBoundingClientRect();
        this.visible = bounds.width > 0 && bounds.height > 0;
      }
      this.resize();
      this.start();
    }

    updateHover(pointer) {
      const line = this.findLineAt(pointer, 12);
      this.setHoveredTrafficStream(line?.stream.id);
      let nearest = null;
      let nearestDistance = 30;
      Object.keys(this.projectedNodes).forEach((key) => {
        const point = this.projectedNodes[key];
        if (!point || point.z <= 0.015) return;
        const distance = Math.hypot(point.x - pointer.x, point.y - pointer.y);
        if (distance < nearestDistance) { nearest = key; nearestDistance = distance; }
      });
      this.setHovered(nearest);
      this.canvas.style.cursor = line || nearest ? "pointer" : this.dragging ? "grabbing" : "grab";
    }

    setHovered(key) {
      if (this.hovered === key) return;
      this.hovered = key;
      if (this.tooltip) this.tooltip.hidden = true;
      this.overlayDirty = true;
      this.start();
    }

    setRoute(key, announce) {
      if (!ROUTES[key]) return;
      if (this.routeInitialized && this.route === key) return;
      if (this.variant === "showcase" && announce !== false) this.showcaseFocused = true;
      const routeChanged = !this.routeInitialized || this.route !== key;
      if (routeChanged && this.inspectingStream != null) this.resumeTraffic();
      this.route = key;
      this.routeInitialized = true;
      if (routeChanged) this.labelPlacements.clear();
      this.markMapDirty();
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
      if (this.language === nextLanguage) return;
      if (this.language !== nextLanguage) this.labelPlacements.clear();
      this.language = nextLanguage;
      document.documentElement.lang = this.language;
      document.title = (this.language === "en" ? "DEYTT network map" : "Карта сети DEYTT").toLowerCase();
      const hint = this.root.querySelector(".network-atlas__hint");
      const loader = this.root.querySelector(".network-atlas__loader");
      if (hint) hint.textContent = (this.language === "en" ? "drag · pinch to zoom" : "потяните · два пальца — масштаб").toLowerCase();
      if (loader) loader.textContent = (this.language === "en" ? "Loading map" : "Загружаю карту").toLowerCase();
      this.renderRouteCopy();
      this.markMapDirty();
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
      this.markMapDirty();
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
      this.markMapDirty();
      this.start();
    }

    setAutoExitCountry(countryCode) {
      const nextCountry = String(countryCode || "").toUpperCase();
      const nextKey = ROUTES.auto.nodes.includes(COUNTRY_EXIT_KEYS[nextCountry]) && this.availableLocationKeys.has(COUNTRY_EXIT_KEYS[nextCountry])
        ? nextCountry
        : null;
      if (this.autoExitCountry === nextKey) return;
      this.autoExitCountry = nextKey;
      this.markMapDirty();
      this.start();
    }

    setActiveAutoRoute(routeKey) {
      const allowed = ["nl", "de", "fi", "it"];
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
      this.markMapDirty();
      this.start();
    }

    setTrafficActive(active) {
      const next = Boolean(active);
      if (this.trafficActive === next) return;
      this.trafficActive = next;
      this.trafficStartedAt = next ? performance.now() : 0;
      if (!next && this.inspectingStream != null) this.resumeTraffic();
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
      this.targetLat = clamp(target.lat - (this.options.routeFocusLatitudeOffset || 0), -62, 72);
      this.targetLon = nearestLongitude(this.centerLon, target.lon);
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
      this.markMapDirty();
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
      this.markMapDirty();
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
