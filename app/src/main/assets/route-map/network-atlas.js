(function () {
  "use strict";

  const DEG = Math.PI / 180;
  const TAU = Math.PI * 2;
  const MAX_ZOOM = 32;
  const ZOOM_EASE_MS = 120;
  const LOCATION_ORDER = ["nl", "de", "fi", "ru"];
  const LABEL_OFFSETS = {
    nl: { x: -24, y: -42, align: "right" },
    de: { x: -24, y: 54, align: "right" },
    fi: { x: -22, y: -42, align: "right" },
    ru: { x: 24, y: -42, align: "left" },
    user: { x: 22, y: -38, align: "left" }
  };
  const COMPACT_LABEL_OFFSETS = {
    nl: { x: -20, y: -42, align: "right" },
    de: { x: -20, y: 56, align: "right" },
    fi: { x: -18, y: -82, align: "right" },
    ru: { x: 18, y: -42, align: "left" },
    user: { x: 18, y: -62, align: "left" }
  };
  const LOCATIONS = {
    nl: { code: "nl", city: "амстердам", country: "нидерланды", countryId: "528", lat: 52.3676, lon: 4.9041 },
    de: { code: "de", city: "франкфурт", country: "германия", countryId: "276", lat: 50.1109, lon: 8.6821 },
    fi: { code: "fi", city: "хельсинки", country: "финляндия", countryId: "246", lat: 60.1699, lon: 24.9384 },
    ru: { code: "ru", city: "санкт-петербург", country: "россия", countryId: "643", lat: 59.9311, lon: 30.3609 }
  };
  const ROUTES = {
    auto: {
      title: "автоподбор",
      eyebrow: "два доступных выхода",
      nodes: ["nl", "de", "fi", "ru"],
      links: [],
      steps: [["вы", "устройство"], ["auto", "проверка точек"], ["nl / de / fi / ru", "доступные выходы"], ["web", "интернет"]],
      description: "общий адрес выбирает доступный выход из амстердама, франкфурта, хельсинки или санкт-петербурга по состоянию сети."
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
    "германия": "Germany",
    "хельсинки": "Helsinki",
    "финляндия": "Finland",
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
      steps: [["you", "device"], ["auto", "checking nodes"], ["nl / de / fi / ru", "available exits"], ["web", "internet"]],
      description: "The shared entry chooses an available exit in Amsterdam, Frankfurt, Helsinki, or Saint Petersburg based on network conditions."
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
      return { rings: rings.map(unwrapRing) };
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
      targetBoundaryLines: targetBoundaryLines
    };
  }

  class NetworkAtlas {
    constructor(root, options) {
      this.root = root;
      this.canvas = root.querySelector("canvas");
      this.context = this.canvas.getContext("2d");
      this.tooltip = root.querySelector("[data-atlas-tooltip]");
      this.options = options || {};
      this.variant = this.options.variant || "home";
      this.animateTraffic = this.options.animateTraffic !== false;
      this.selectOnTap = this.options.selectOnTap !== false;
      this.root.dataset.atlasVariant = this.variant;
      this.route = "auto";
      this.language = "ru";
      this.trafficMode = "download";
      this.userLocation = null;
      this.availableLocationKeys = new Set(LOCATION_ORDER);
      this.showcaseFocused = false;
      this.hovered = null;
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
      this.phase = 0;
      this.lastFrame = 0;
      this.frame = 0;
      this.visible = true;
      this.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
      this.boundaryLines = [];
      this.targetBoundaryLines = [];
      this.pixelRatio = 1;
      this.staticCanvas = document.createElement("canvas");
      this.staticContext = this.staticCanvas.getContext("2d");
      this.staticDirty = true;
      this.renderCount = 0;
      this.staticRenderCount = 0;
      this.cameraMoving = false;
      this.projectedNodes = {};
      this.bind();
    }

    async init() {
      const response = await fetch(this.options.topologyUrl, { cache: "force-cache", credentials: "omit" });
      if (!response.ok) throw new Error("geography unavailable: " + response.status);
      const topology = await response.json();
      const geography = decodeTopology(topology);
      this.boundaryLines = geography.boundaryLines;
      this.targetBoundaryLines = geography.targetBoundaryLines;
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
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
        if (this.activePointers.has(event.pointerId)) this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (this.activePointers.size >= 2) {
          const points = Array.from(this.activePointers.values()).slice(0, 2);
          const distance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
          if (this.pinchDistance > 0 && distance > 0) {
            this.targetZoom = clamp(this.targetZoom * distance / this.pinchDistance, .82, MAX_ZOOM);
            this.staticDirty = true;
          }
          this.pinchDistance = distance;
          this.start();
          return;
        }
        if (this.dragging) {
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
        event.preventDefault();
        const delta = event.deltaMode === 1 ? event.deltaY * 18 : event.deltaY;
        // Normalize large mouse-wheel bursts so the camera never jumps between frames.
        const normalizedDelta = clamp(delta, -160, 160);
        this.targetZoom = clamp(this.targetZoom * Math.exp(-normalizedDelta * .00135), .82, MAX_ZOOM);
        this.staticDirty = true;
        this.start();
      }, { passive: false });
      const release = (event) => {
        this.activePointers.delete(event.pointerId);
        this.pinchDistance = 0;
        this.dragging = this.activePointers.size > 0;
        const remaining = this.activePointers.values().next().value;
        if (remaining) this.lastPointer = remaining;
        else this.root.classList.remove("is-dragging");
        if (event.pointerType === "touch") this.setHovered(null);
        this.start();
      };
      this.canvas.addEventListener("pointerup", release);
      this.canvas.addEventListener("pointercancel", release);
      this.canvas.addEventListener("pointerleave", () => {
        if (!this.dragging) this.setHovered(null);
      });
      this.canvas.addEventListener("click", (event) => {
        const box = this.canvas.getBoundingClientRect();
        const pointer = { x: event.clientX - box.left, y: event.clientY - box.top };
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
      this.canvas.addEventListener("keydown", (event) => {
        if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "+", "=", "-", "_", "0"].includes(event.key)) return;
        event.preventDefault();
        if (event.key === "ArrowLeft") this.targetLon -= 9;
        if (event.key === "ArrowRight") this.targetLon += 9;
        if (event.key === "ArrowUp") this.targetLat = clamp(this.targetLat + 7, -62, 72);
        if (event.key === "ArrowDown") this.targetLat = clamp(this.targetLat - 7, -62, 72);
        if (event.key === "+" || event.key === "=") this.targetZoom = clamp(this.targetZoom * 1.2, .82, MAX_ZOOM);
        if (event.key === "-" || event.key === "_") this.targetZoom = clamp(this.targetZoom / 1.2, .82, MAX_ZOOM);
        if (event.key === "0") this.targetZoom = 1;
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
      const pixelRatioCap = rectangle.width < 520 || (navigator.connection && navigator.connection.saveData) ? 1.5 : 2;
      const ratio = Math.min(window.devicePixelRatio || 1, pixelRatioCap);
      this.pixelRatio = ratio;
      this.width = Math.max(1, Math.round(rectangle.width));
      this.height = Math.max(1, Math.round(rectangle.height));
      this.canvas.width = Math.round(this.width * ratio);
      this.canvas.height = Math.round(this.height * ratio);
      this.context.setTransform(ratio, 0, 0, ratio, 0, 0);
      this.staticCanvas.width = this.canvas.width;
      this.staticCanvas.height = this.canvas.height;
      this.staticContext.setTransform(ratio, 0, 0, ratio, 0, 0);
      this.radius = Math.min(this.width, this.height) * (this.width < 520 ? 0.405 : 0.43);
      this.staticDirty = true;
      this.start();
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
        x: this.width / 2 + dot(point, basis.east) * scale,
        y: this.height / 2 - dot(point, basis.north) * scale,
        z: dot(point, basis.forward),
        lift: lift || 1
      };
    }

    projectLocation(location, lift) { return this.projectVector(vector(location.lat, location.lon), lift); }

    colors() {
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
      const x = this.width / 2;
      const y = this.height / 2;
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

    drawBorders(colors) {
      const context = this.context;
      // Keep the same topology while the camera moves. Decimating every second
      // point during pan/zoom made country outlines visibly change shape.
      const pointStep = 1;
      const trace = (points, lift) => {
        context.beginPath();
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
        lines.forEach((line) => {
          trace(line.points || line, 1.002);
          context.stroke();
        });
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

    drawArc(fromKey, toKey, colors, emphasized, flowDirection) {
      const context = this.context;
      const from = fromKey === "user" ? vector(this.userLocation.lat, this.userLocation.lon) : vector(LOCATIONS[fromKey].lat, LOCATIONS[fromKey].lon);
      const to = vector(LOCATIONS[toKey].lat, LOCATIONS[toKey].lon);
      const segments = 48;
      const baseLift = fromKey === "user" ? .16 : .11;
      const points = [];
      for (let index = 0; index <= segments; index += 1) {
        const amount = index / segments;
        points.push(this.projectVector(slerp(from, to, amount), 1 + Math.sin(amount * Math.PI) * baseLift));
      }
      const visible = points.filter((point) => point.z > -.02);
      if (visible.length < 2) return;
      const upload = this.trafficMode === "upload";
      const routeColor = this.animateTraffic
        ? upload ? (colors.upload || "#63dfb3") : (colors.download || colors.route)
        : colors.route;
      const routeHotColor = this.animateTraffic
        ? upload ? (colors.uploadHot || "#b8f6dc") : (colors.downloadHot || colors.routeHot)
        : colors.routeHot;
      const gradient = context.createLinearGradient(visible[0].x, visible[0].y, visible[visible.length - 1].x, visible[visible.length - 1].y);
      gradient.addColorStop(0, routeColor);
      gradient.addColorStop(.54, routeHotColor);
      gradient.addColorStop(1, routeColor);
      const trace = (points) => {
        context.beginPath();
        let drawing = false;
        points.forEach((point) => {
          if (point.z <= -.02) { drawing = false; return; }
          if (!drawing) context.moveTo(point.x, point.y);
          else context.lineTo(point.x, point.y);
          drawing = true;
        });
      };
      trace(points);
      context.strokeStyle = routeColor;
      context.lineWidth = emphasized ? (this.animateTraffic ? 10 : 4) : 7;
      context.globalAlpha = emphasized ? (this.animateTraffic ? .12 : .08) : .08;
      context.shadowColor = routeHotColor;
      context.shadowBlur = this.animateTraffic ? 26 : 8;
      context.stroke();
      trace(points);
      context.strokeStyle = gradient;
      context.lineWidth = emphasized ? (this.animateTraffic ? 2.25 : 1.5) : 1.65;
      context.globalAlpha = emphasized ? (this.animateTraffic ? .96 : .86) : .78;
      context.shadowBlur = this.animateTraffic ? 11 : 5;
      context.stroke();
      context.shadowBlur = 0;
      context.globalAlpha = 1;

      if (this.reducedMotion.matches || !this.animateTraffic) return;
      const particleCount = emphasized ? 3 : 4;
      for (let particle = 0; particle < particleCount; particle += 1) {
        const speed = fromKey === "user" ? .014 : .011;
        const amount = (this.phase * speed + particle / particleCount) % 1;
        const reverse = flowDirection === "reverse";
        const travelAmount = reverse ? 1 - amount : amount;
        const lift = 1 + Math.sin(travelAmount * Math.PI) * baseLift;
        const point = this.projectVector(slerp(from, to, travelAmount), lift);
        if (point.z <= -0.02) continue;
        context.beginPath();
        for (let trail = 4; trail >= 0; trail -= 1) {
          const trailAmount = reverse ? clamp(travelAmount + trail * .016, 0, 1) : clamp(travelAmount - trail * .016, 0, 1);
          const trailLift = 1 + Math.sin(trailAmount * Math.PI) * baseLift;
          const trailPoint = this.projectVector(slerp(from, to, trailAmount), trailLift);
          if (trail === 4) context.moveTo(trailPoint.x, trailPoint.y);
          else context.lineTo(trailPoint.x, trailPoint.y);
        }
        context.strokeStyle = routeHotColor;
        context.lineWidth = emphasized ? 2.3 : 1.8;
        context.globalAlpha = .25;
        context.shadowColor = routeHotColor;
        context.shadowBlur = 13;
        context.stroke();
        context.beginPath();
        context.arc(point.x, point.y, emphasized ? 2.3 : 1.8, 0, TAU);
        context.fillStyle = routeHotColor;
        context.shadowBlur = 12;
        context.globalAlpha = 1;
        context.fill();
        context.shadowBlur = 0;
      }
      context.globalAlpha = 1;
    }

    drawRoutes(colors) {
      const route = ROUTES[this.route];
      const internalDirection = this.trafficMode === "upload" ? "forward" : "reverse";
      route.links.forEach((link) => {
        if (link.every((key) => this.availableLocationKeys.has(key))) {
          this.drawArc(link[0], link[1], colors, true, internalDirection);
        }
      });
      // A direct path is only drawn when the user explicitly approved the
      // approximate origin marker. Auto-pick has no resolved exit while idle.
      const firstHop = route.nodes.find((key) => this.availableLocationKeys.has(key));
      if (this.userLocation && this.route !== "auto" && firstHop) {
        this.drawArc("user", firstHop, colors, true, internalDirection);
      }
    }

    drawNode(key, location, colors, kind) {
      const context = this.context;
      const projected = this.projectLocation(location, 1.008);
      this.projectedNodes[key] = projected;
      if (projected.z <= 0.015) return;
      const active = kind === "user" || ROUTES[this.route].nodes.includes(key);
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
      if (this.hovered === key) return;
      if (this.annotationFilter && key !== "user" && !this.annotationFilter.has(key)) return;
      const active = key === "user" || ROUTES[this.route].nodes.includes(key);
      if (!active && this.hovered !== key) return;
      const context = this.context;
      const location = key === "user" ? this.userLocation : LOCATIONS[key];
      const compact = this.width < 520;
      const offset = compact ? COMPACT_LABEL_OFFSETS[key] : LABEL_OFFSETS[key];
      const title = key === "user" ? (this.copy(location.city) || this.copy("ваша сеть")) : this.copy(location.city);
      const meta = key === "user" ? (location.country || this.copy("вход в сеть")) : location.code + " · " + this.copy(location.country);
      context.save();
      context.font = "700 " + (compact ? 9 : 10) + "px ui-monospace, SFMono-Regular, Menlo, monospace";
      const width = Math.max(context.measureText(title).width, context.measureText(meta).width) + (compact ? 20 : 24);
      const height = compact ? 35 : 39;
      const alternatives = [
        offset,
        { x: offset.x, y: offset.y - height - 12, align: offset.align },
        { x: offset.x, y: offset.y + height + 12, align: offset.align },
        { x: -offset.x, y: offset.y, align: offset.align === "right" ? "left" : "right" },
      ];
      let left = 8;
      let top = 8;
      let align = offset.align;
      for (const candidate of alternatives) {
        const anchorX = point.x + candidate.x;
        const anchorY = point.y + candidate.y;
        const candidateLeft = clamp(candidate.align === "right" ? anchorX - width : anchorX, 8, this.width - width - 8);
        const candidateTop = clamp(anchorY - height / 2, 8, this.height - height - 8);
        const collides = occupied.some((label) => candidateLeft < label.left + label.width + 8
          && candidateLeft + width + 8 > label.left
          && candidateTop < label.top + label.height + 8
          && candidateTop + height + 8 > label.top);
        if (collides) continue;
        left = candidateLeft;
        top = candidateTop;
        align = candidate.align;
        break;
      }
      occupied.push({ left: left, top: top, width: width, height: height });
      context.beginPath();
      context.roundRect(left, top, width, height, compact ? 10 : 12);
      context.fillStyle = colors.labelBg;
      context.globalAlpha = .96;
      context.shadowColor = "rgba(16,29,65,.22)";
      context.shadowBlur = 18;
      context.fill();
      context.shadowBlur = 0;
      context.strokeStyle = this.hovered === key ? colors.borderHot : colors.border;
      context.globalAlpha = this.hovered === key ? .9 : .65;
      context.stroke();
      context.textBaseline = "middle";
      context.textAlign = "left";
      context.fillStyle = colors.label;
      context.globalAlpha = 1;
      context.font = "700 " + (compact ? 9 : 10) + "px ui-monospace, SFMono-Regular, Menlo, monospace";
      context.fillText(title, left + (compact ? 10 : 12), top + (compact ? 11 : 12));
      context.fillStyle = colors.labelMuted;
      context.font = "600 " + (compact ? 7 : 8) + "px ui-monospace, SFMono-Regular, Menlo, monospace";
      context.fillText(meta, left + (compact ? 10 : 12), top + (compact ? 25 : 28));
      context.restore();
    }

    drawNodes(colors) {
      this.projectedNodes = {};
      LOCATION_ORDER.forEach((key) => {
        if (this.availableLocationKeys.has(key)) this.drawNode(key, LOCATIONS[key], colors, "server");
      });
      if (this.userLocation) this.drawNode("user", this.userLocation, colors, "user");
      const occupied = [];
      LOCATION_ORDER.forEach((key) => {
        if (this.availableLocationKeys.has(key)) this.drawLabel(key, colors, occupied);
      });
      if (this.userLocation) this.drawLabel("user", colors, occupied);
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
          context.arc(this.width / 2, this.height / 2, clipRadius, 0, TAU);
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

    render(time) {
      this.frame = 0;
      if (!this.visible || document.hidden || !this.width) return;
      const cameraMoving = this.dragging || Math.abs(this.targetZoom - this.zoom) > .006 || Math.abs(this.targetLon - this.centerLon) > .06 || Math.abs(this.targetLat - this.centerLat) > .06;
      this.cameraMoving = cameraMoving;
      // Traffic particles keep moving while idle, so throttle only the
      // expensive camera-motion pass. A 24/31 FPS idle cap made the animation
      // visibly stutter after the user stopped interacting with the globe.
      const frameInterval = cameraMoving ? (this.width < 520 ? 20 : 16) : 16;
      const elapsed = this.lastFrame ? time - this.lastFrame : frameInterval;
      if (elapsed < frameInterval && !this.dragging) {
        this.start();
        return;
      }
      const delta = Math.min(48, elapsed);
      this.lastFrame = time;
      if (this.animateTraffic && !this.reducedMotion.matches) this.phase += delta * 0.06;
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
      const panBlend = this.reducedMotion.matches || this.dragging ? 1 : .13;
      this.centerLon = mix(this.centerLon, this.targetLon, panBlend);
      this.centerLat = mix(this.centerLat, this.targetLat, panBlend);
      const zoomBlend = this.reducedMotion.matches ? 1 : 1 - Math.exp(-Math.max(1, delta) / ZOOM_EASE_MS);
      this.zoom = mix(this.zoom, this.targetZoom, zoomBlend);
      if (Math.abs(this.centerLon - previousLon) > .0001 || Math.abs(this.centerLat - previousLat) > .0001 || Math.abs(this.zoom - previousZoom) > .0001) {
        this.staticDirty = true;
      }
      this.currentBasis = this.basis();
      this.context.clearRect(0, 0, this.width, this.height);
      const colors = this.colors();
      if (this.staticDirty) this.renderStatic(colors);
      this.context.drawImage(this.staticCanvas, 0, 0, this.width, this.height);
      this.drawRoutes(colors);
      this.drawNodes(colors);
      this.renderCount += 1;
      if ((this.animateTraffic && !this.reducedMotion.matches) || Math.abs(this.targetLon - this.centerLon) > .01 || Math.abs(this.targetLat - this.centerLat) > .01 || Math.abs(this.targetZoom - this.zoom) > .002 || Math.abs(this.velocityLon) > .001) this.start();
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
      this.staticDirty = true;
      this.canvas.style.cursor = key ? "pointer" : this.dragging ? "grabbing" : "grab";
      if (!key || !this.tooltip) {
        if (this.tooltip) this.tooltip.hidden = true;
      } else {
        const location = key === "user" ? this.userLocation : LOCATIONS[key];
        const point = this.projectedNodes[key];
        const span = document.createElement("span");
        const strong = document.createElement("strong");
        const small = document.createElement("small");
        if (key === "user") {
          span.textContent = this.copy("ваша сеть");
          strong.textContent = this.copy(location.city) || location.lat.toFixed(2) + "°, " + location.lon.toFixed(2) + "°";
          small.textContent = location.country || this.copy("примерно по ip");
        } else {
          span.textContent = location.code + " · " + this.copy(location.country);
          strong.textContent = this.copy(location.city);
          small.textContent = location.lat.toFixed(2) + "°, " + location.lon.toFixed(2) + "°";
        }
        this.tooltip.replaceChildren(span, strong, small);
        this.tooltip.hidden = false;
        const left = clamp(point.x, 92, this.width - 92);
        const top = clamp(point.y - 28, 80, this.height - 80);
        this.tooltip.style.left = left + "px";
        this.tooltip.style.top = top + "px";
      }
      this.start();
    }

    setRoute(key, announce) {
      if (!ROUTES[key]) return;
      if (this.variant === "showcase" && announce !== false) this.showcaseFocused = true;
      this.route = key;
      this.staticDirty = true;
      this.root.querySelectorAll("[data-atlas-route]").forEach(function (button) {
        const active = button.dataset.atlasRoute === key;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      const route = ROUTES[key];
      this.renderRouteCopy();
      this.focusRoute();
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
      if (title) title.textContent = route.title;
      if (eyebrow) eyebrow.textContent = route.eyebrow;
      if (description) description.textContent = route.description;
      if (steps) {
        steps.innerHTML = route.steps.map(function (step, index) {
          const arrow = index ? '<span class="atlas-step__arrow" aria-hidden="true">→</span>' : "";
          return arrow + '<span class="atlas-step"><b>' + step[0] + '</b><small>' + step[1] + '</small></span>';
        }).join("");
        steps.setAttribute("aria-label", route.steps.map(function (step) { return step.join(" — "); }).join(" → "));
      }
      const mapLabel = this.language === "en"
        ? "Interactive globe. Route: " + route.title + ". Drag to rotate; pinch to zoom. Choose a route from the list below."
        : "интерактивный глобус: маршрут «" + route.title + "». проведите пальцем, чтобы повернуть; сведите или разведите два пальца, чтобы изменить масштаб. маршрут выбирается в списке ниже.";
      this.canvas.setAttribute("aria-label", mapLabel);
    }

    setLanguage(language) {
      this.language = language === "en" ? "en" : "ru";
      document.documentElement.lang = this.language;
      document.title = this.language === "en" ? "DEYTT network map" : "Карта сети DEYTT";
      const hint = this.root.querySelector(".network-atlas__hint");
      const loader = this.root.querySelector(".network-atlas__loader");
      if (hint) hint.textContent = this.language === "en" ? "drag · pinch to zoom" : "потяните · два пальца — масштаб";
      if (loader) loader.textContent = this.language === "en" ? "Loading map" : "Загружаю карту";
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
      this.availableLocationKeys = next;
      this.focusRoute();
      this.staticDirty = true;
      this.start();
    }

    setTrafficMode(mode) {
      if (mode !== "upload" && mode !== "download") return;
      if (this.trafficMode === mode) return;
      this.trafficMode = mode;
      this.start();
    }

    setUserLocation(latitude, longitude, details) {
      this.userLocation = { lat: clamp(Number(latitude), -85, 85), lon: ((Number(longitude) + 540) % 360) - 180, city: details && details.city, country: details && details.country };
      this.focusRoute();
      this.staticDirty = true;
      this.start();
    }

    focusRoute() {
      const routeNodes = (this.variant === "observatory" ? LOCATION_ORDER : ROUTES[this.route].nodes)
        .filter((key) => this.availableLocationKeys.has(key));
      const locations = routeNodes.map((key) => LOCATIONS[key]);
      if (this.userLocation) locations.push(this.userLocation);
      if (!locations.length) return;
      const vectors = locations.map((location) => vector(location.lat, location.lon));
      const center = normalize(vectors.reduce(function (total, point) {
        return { x: total.x + point.x, y: total.y + point.y, z: total.z + point.z };
      }, { x: 0, y: 0, z: 0 }));
      const maxAngle = Math.max.apply(Math, vectors.map((point) => Math.acos(clamp(dot(point, center), -1, 1))));
      const target = locationFromVector(center);
      this.targetLat = clamp(target.lat, -62, 72);
      this.targetLon = nearestLongitude(this.centerLon, target.lon);
      // Start with the provisioned server cluster filling the screen. Pinch
      // still reaches the whole globe; idle pages do not imply any traffic.
      this.targetZoom = clamp(2.0 / Math.max(Math.sin(maxAngle), .2), 2.15, 2.85);
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
