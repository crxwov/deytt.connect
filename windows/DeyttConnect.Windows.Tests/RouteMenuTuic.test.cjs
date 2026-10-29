const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../DeyttConnect.Windows/Assets/route-map/index.html'), 'utf8');
const cards = ['NL', 'DE'].map(country => {
  const key = country.toLowerCase() + '-tuic';
  assert.match(html, new RegExp(`data-route-key="${key}" data-route-tag="route:${country}:TUIC" hidden`));
  assert.match(html, new RegExp(`data-action-route="${key}"`));
  const label = {}, button = {}, classes = new Set();
  return {
    dataset: { routeKey: key, routeTag: `route:${country}:TUIC` }, style: {}, hidden: true,
    classList: { toggle: (name, value) => value ? classes.add(name) : classes.delete(name) },
    querySelector: selector => selector === '.proto-name' ? label : selector === '.btn-select-route' ? button : null,
    label, button, classes
  };
});
const messages = [];
const context = {
  window: {}, postHost: message => messages.push(message),
  document: { querySelectorAll: selector => selector === '.proto-row, .smart-route-row' ? cards : [] }
};
const start = html.indexOf('    let currentRouteCatalog = [];');
const end = html.indexOf('    // Routes Selection & Syncing', start);
assert.ok(start >= 0 && end > start);
vm.runInNewContext('let activeRouteKey = "auto";\n' + html.slice(start, end) + '\nthis.resolve = routeForUiKey; this.active = () => activeRouteKey;', context);
const routes = ['NL', 'DE'].map(country => ({
  id: `route:${country}:TUIC`, tag: `route:${country}:TUIC`, countryCode: country,
  protocol: 'TUIC', protocolName: 'tuic', isSelected: country === 'DE'
}));
context.window.deyttSetRoutesData(routes);
for (let i = 0; i < cards.length; i++) {
  assert.equal(cards[i].hidden, false);
  assert.equal(cards[i].style.display, '');
  assert.equal(cards[i].label.textContent, 'tuic');
  assert.equal(context.resolve(cards[i].dataset.routeKey), routes[i]);
}
assert.equal(context.active(), 'de-tuic');
assert.equal(cards[1].button.textContent, 'активен ✓');
assert.deepEqual(messages, []); // Rendering a subscription must never select/connect a route.
context.window.deyttSetRoutesData([{ id: 'route:NL:VLESS', tag: 'route:NL:VLESS', countryCode: 'NL', protocol: 'VLESS' }]);
assert.ok(cards.every(card => card.hidden && card.style.display === 'none'));
console.log('PASS: TUIC NL/DE visible only when supplied; route keys resolve; rendering sends no host commands.');