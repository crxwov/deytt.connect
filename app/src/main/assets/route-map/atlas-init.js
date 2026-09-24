(function () {
  const root = document.querySelector(".network-atlas");
  document.documentElement.dataset.theme = "light";
  window.deyttMapRoute = "auto";
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
  }).catch(function () {
    const status = root.querySelector("[data-atlas-status]");
    if (status) {
      status.removeAttribute("aria-hidden");
      status.textContent = "карта временно недоступна";
    }
  });
}());
