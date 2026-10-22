// Run with Node 22+ and installed Edge. Exercises rendered UI contracts without account/service access.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const assets = path.join(repo, 'windows/DeyttConnect.Windows/Assets');
const output = path.resolve(process.argv[2] || 'D:/DeyttConnectBuild/windows-release-0.11.45/qa/web');
fs.mkdirSync(output, { recursive: true });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const types = { '.html':'text/html', '.css':'text/css', '.js':'text/javascript', '.json':'application/json', '.svg':'image/svg+xml', '.ttf':'font/ttf' };
const server = http.createServer((request, response) => {
  const relative = decodeURIComponent(new URL(request.url, 'http://localhost').pathname).replace(/^\//, '');
  let target = path.resolve(assets, 'route-map', relative || 'index.html');
  if (relative.startsWith('fonts/')) target = path.resolve(assets, 'Fonts', relative.slice(6));
  if (relative.startsWith('protocol-marks/')) target = path.resolve(assets, 'ProtocolMarks', relative.slice(15));
  if (relative === 'world-land.json') target = path.join(repo, 'app/src/main/assets/world-land.json');
  if ((!target.startsWith(assets + path.sep) && target !== path.join(repo, 'app/src/main/assets/world-land.json')) || !fs.existsSync(target) || !fs.statSync(target).isFile()) {
    response.writeHead(404); response.end(); return;
  }
  response.writeHead(200, { 'Content-Type': types[path.extname(target)] || 'application/octet-stream', 'Cache-Control':'no-store' });
  fs.createReadStream(target).pipe(response);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const profile = path.join(output, 'edge-profile-' + Date.now());
const edge = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'
], { windowsHide: true, stdio:'ignore' });
let socket;
const checks = [];
try {
  const portFile = path.join(profile, 'DevToolsActivePort');
  for (let i=0; i<100 && !fs.existsSync(portFile); i++) await delay(100);
  assert.ok(fs.existsSync(portFile), 'Edge DevTools did not start');
  const port = fs.readFileSync(portFile, 'utf8').split(/\r?\n/)[0];
  const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const tab = tabs.find(item => item.type === 'page');
  socket = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once:true }); socket.addEventListener('error', reject, { once:true }); });
  let sequence=0;
  const pending = new Map();
  const exceptions=[];
  socket.addEventListener('message', event => {
    const message=JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
    if (!message.id) return;
    const waiter=pending.get(message.id); if (!waiter) return;
    pending.delete(message.id); clearTimeout(waiter.timeout);
    message.error ? waiter.reject(Error(JSON.stringify(message.error))) : waiter.resolve(message.result);
  });
  const cdp = (method, params={}) => new Promise((resolve, reject) => {
    const id=++sequence;
    const timeout=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout: '+method));},20000);
    pending.set(id,{resolve,reject,timeout}); socket.send(JSON.stringify({id,method,params}));
  });
  const evaluate = async expression => {
    const result=await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
    if(result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails));
    return result.result?.value;
  };
  const screenshot = async name => {
    await evaluate('document.fonts.ready.then(()=>true)');
    const shot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    fs.writeFileSync(path.join(output,name+'.png'),Buffer.from(shot.data,'base64'));
  };
  const waitFor = async expression => {
    for(let i=0;i<75;i++){if(await evaluate(expression))return;await delay(100);}
    throw Error('Timed out: '+expression);
  };
  await cdp('Runtime.enable'); await cdp('Page.enable');
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:820,deviceScaleFactor:1,mobile:false});
  await cdp('Page.navigate',{url:`http://127.0.0.1:${server.address().port}/`});
  await waitFor('Boolean(window.deyttMapAtlas && window.deyttSetProfileData)');
  await evaluate(`window.qaMessages=[];window.invokeCSharpAction=value=>window.qaMessages.push(value);window.deyttSetMapReducedMotion(true);window.deyttSetMapLocations(['nl','de','fi','ru','it']);window.deyttSetMapUserLocation(54.7388,55.9721,{city:'уфа',country:'RU'});`);
  await evaluate(`const flags=document.createElement('div');flags.id='qa-flags';flags.innerHTML=['NL','DE','RU','FI','IT','NL','DE'].map(code=>window.getFlagSvg(code)).join('');document.body.append(flags);`);
  assert.equal(await evaluate(`(()=>{const ids=[...document.querySelectorAll('clipPath[id]')].map(element=>element.id);return ids.length-new Set(ids).size;})()`),0,'flag SVG IDs must be unique');
  await evaluate(`document.getElementById('qa-flags').remove();window.deyttSelectRouteFromAtlas('nl-vless');`);
  assert.equal(await evaluate(`document.querySelector('#home-flag svg.svg-flag g rect').getAttribute('fill')`),'#C93D4B','NL route uses the red-white-blue vector flag');
  assert.equal(await evaluate(`document.querySelector('#compact-flag svg.svg-flag g rect').getAttribute('fill')`),'#C93D4B','compact NL flag is a single vector flag');
  assert.equal(await evaluate(`document.querySelectorAll('#home-flag svg.svg-flag, #compact-flag svg.svg-flag').length`),2,'each route flag instance has one SVG, without a second flag background');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('#compact-flag')).backgroundImage.includes('174, 28, 40')`),false,'country flag stripes are not duplicated behind the SVG');
  assert.equal(await evaluate(`document.querySelector('#compact-city').textContent`),'nl./vless+ws','compact protocol route uses the short tag');
  assert.equal(await evaluate(`document.querySelector('#compact-country').hidden`),true,'compact protocol route hides the long country name');
  assert.equal(await evaluate(`document.querySelector('#compact-protocol').hidden`),true,'compact tag is shown once');
  await evaluate(`window.deyttSetHomePresentation({status:'подключено',statusTone:'good',countryCode:'NL',country:'нидерланды',city:'амстердам',protocol:'vless+ws',actionLabel:'отключить',action:'home-disconnect',actionEnabled:true,actionTone:'connected',latency:'498',latencyTone:'good',speed:'70,5',speedTone:'good',measurementEnabled:true});window.deyttSwitchStage('home');window.deyttSetMapRoute('nl');window.deyttSetMapActiveAutoRoute('nl');window.deyttSetMapEgressCountry('NL');window.deyttSetMapTrafficActive(true);window.deyttSetMapImmersive(true);const toast=document.getElementById('toast');toast?.classList.remove('is-visible');if(toast)toast.textContent='';`);
  assert.equal(await evaluate(`document.querySelector('#compact-city').textContent`),'nl./vless+ws','native profile presentation preserves compact route tag');
  assert.equal(await evaluate(`document.querySelector('#compact-action').dataset.action`),'home-disconnect','connected compact card exposes the disconnect action');
  assert.equal(await evaluate(`document.querySelector('#compact-action').querySelector('[data-action-label]').textContent`),'отключить','disconnect action has its visible primary label');
  assert.equal(await evaluate(`document.querySelector('#compact-action').disabled`),false,'disconnect action remains enabled');
  assert.deepEqual(await evaluate(`window.deyttMapAtlas.routeLinks().map(link=>link.join('→'))`),['user→nl'],'connected compact fixture renders only the confirmed NL path');
  assert.ok(await evaluate(`getComputedStyle(document.querySelector('.home-compact')).backdropFilter.includes('blur')`),'immersive compact panel uses glass blur');
  assert.ok(await evaluate(`Number(getComputedStyle(document.querySelector('.home-compact')).backgroundColor.split(',').at(-1).replace(')',''))<1`),'immersive compact panel background is translucent');
  await evaluate(`const qaBackdrop=document.createElement('div');qaBackdrop.className='glass-modal-backdrop';document.body.append(qaBackdrop);`);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('.glass-modal-backdrop')).backgroundColor`),'rgba(0, 0, 0, 0)','modal backdrop leaves the page transparent');
  await evaluate(`document.querySelector('.glass-modal-backdrop').remove()`);
  await delay(350);
  assert.equal(await evaluate(`document.getElementById('toast')?.classList.contains('is-visible')`),false,'compact representative screenshot has no route-picker toast');
  await screenshot('compact-glass-nl');
  await evaluate(`window.deyttSetMapImmersive(false);`); checks.push('flags: unique SVG IDs and NL route; compact protocol tag; immersive glass and transparent modal scrim');
  const visibleLabels = () => evaluate(`[...window.deyttMapAtlas.domLabels.entries()].filter(([key,element])=>element.style.display!=='none').map(([key])=>key).sort()`);
  for(const [route,active,expected] of [['nl',null,['nl','user']],['ru-de',null,['ru','de','user']],['auto','fi',['fi','user']],['de',null,['de','user']],['it','nl',['nl','user']]]){
    await evaluate(`window.deyttSwitchStage('home');window.deyttSetMapRoute(${JSON.stringify(route)});window.deyttSetMapActiveAutoRoute(${JSON.stringify(active || route)});window.deyttSetMapTrafficActive(true);window.deyttMapAtlas.setHovered('it');`);
    await delay(500);
    const labels=await visibleLabels();
    assert.ok(labels.length>0,route+' has visible path labels');
    assert.ok(labels.every(key=>expected.includes(key)),`${route}: unexpected label ${labels}`);
    checks.push(`connected ${route}/${active||''}: ${labels.join(',')}`);
  }
  await evaluate(`window.deyttSetMapRoute('auto');window.deyttSetMapActiveAutoRoute(null);window.deyttSetMapEgressCountry(null);`);
  await delay(300);
  assert.ok((await visibleLabels()).every(key=>key==='user'),'unconfirmed automatic exit must not show candidate labels');
  await evaluate(`window.deyttSetMapTrafficActive(false);window.deyttSetMapRoute('nl');window.deyttMapAtlas.targetZoom=1;window.deyttMapAtlas.targetLon=30;window.deyttMapAtlas.targetLat=54;window.deyttMapAtlas.markMapDirty();window.deyttMapAtlas.start();`);
  await delay(500);
  const all=await visibleLabels();
  assert.ok(all.includes('nl') && all.includes('fi') && all.includes('ru'), 'disconnection restores other labels');
  const placement=await evaluate(`(()=>{const boxes=window.deyttMapAtlas.labelBoxes;return {ru:boxes.find(box=>box.key==='ru'),user:boxes.find(box=>box.key==='user')};})()`);
  assert.ok(placement.ru && placement.user, 'Saint Petersburg and Ufa labels are visible together');
  assert.ok(placement.ru.top+placement.ru.height<=placement.user.top, 'Saint Petersburg must be above Ufa');
  await screenshot('map-disconnected-order'); checks.push('disconnected labels restored; Saint Petersburg above Ufa');
  const GiB=1024**3;
  const fixture={isSignedIn:true,username:'qa',displayName:'проверка интерфейса',telegramId:1,subscriptionActive:true,tariffName:'deytt./ultra',subscriptionKey:'https://example.invalid/qa',
    devices:[{id:'device-1',name:'a001',detail:'android 16 · проверка',isActive:true}],sessions:[{id:'Session-AbC',label:'Windows',detail:'тестовый сеанс',current:false}],
    mainQuota:{state:'ready',usedBytes:25*GiB,limitBytes:100*GiB,expiresAt:new Date(Date.now()+20*86400000).toISOString()},
    awgQuota:{state:'ready',usedBytes:75*GiB,limitBytes:100*GiB,expiresAt:new Date(Date.now()+7*86400000).toISOString()}};
  const send = data => evaluate(`window.deyttSetProfileData(${JSON.stringify(data)});`);
  await send(fixture);await evaluate(`window.deyttSwitchStage('profile')`);
  await delay(350);
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('[data-quota-fill]')].map(element=>element.style.width)`),['25%','75%']);
  const dates=await evaluate(`[...document.querySelectorAll('[data-quota-expiry]')].map(element=>element.textContent)`);
  assert.notEqual(dates[0],dates[1],'plans must have independent expiry');
  assert.equal(await evaluate(`document.querySelectorAll('.device-group').length`),2);
  assert.equal(await evaluate(`document.querySelectorAll('.device-group[open]').length`),0);
  await cdp('Page.bringToFront');await cdp('Emulation.setFocusEmulationEnabled',{enabled:true});
  await evaluate(`document.querySelector('.device-group summary').focus()`);
  assert.equal(await evaluate(`document.activeElement.tagName`),'SUMMARY','accordion receives keyboard focus');
  await cdp('Input.dispatchKeyEvent',{type:'rawKeyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
  await cdp('Input.dispatchKeyEvent',{type:'char',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,text:'\r',unmodifiedText:'\r'});
  await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
  await waitFor(`document.querySelector('.device-group').open`);
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('.device-group')].map(element=>element.open)`),[true,false]);
  await send(fixture);
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('.device-group')].map(element=>element.open)`),[true,false],'refresh preserves expansion');
  await evaluate(`document.querySelector('[data-profile-action="device"]').click();document.querySelectorAll('.device-group')[1].open=true;document.querySelector('[data-profile-action="session"]').click()`);
  assert.ok((await evaluate('window.qaMessages')).some(value=>value.includes('Session-AbC')),'session action keeps exact ID');
  await evaluate(`window.deyttSwitchStage('settings');window.deyttSwitchStage('profile')`);
  assert.equal(await evaluate(`document.querySelectorAll('.device-group[open]').length`),0,'profile re-entry starts collapsed');
  checks.push('independent quotas/dates; keyboard accordion; refresh/re-entry; device/session action');
  await send({...fixture,mainQuota:{state:'ready',usedBytes:5*GiB,limitBytes:0,unlimitedTraffic:true,unlimitedTime:true},awgQuota:{...fixture.awgQuota,usedBytes:120*GiB,expiresAt:'2020-01-01T00:00:00Z'}});
  assert.equal(await evaluate(`document.querySelector('#profile-main-quota [data-quota-track]').getAttribute('role')`),null,'unlimited quota has no fake percentage');
  assert.equal(await evaluate(`document.querySelector('#profile-awg-quota [data-quota-fill]').style.width`),'100%');
  assert.ok(await evaluate(`document.querySelector('#profile-awg-quota').classList.contains('is-exhausted')`));
  for(const state of ['absent','loading','error']){
    await send({...fixture,awgQuota:{state}});
    assert.ok(await evaluate(`document.querySelector('#profile-awg-quota [data-quota-track]').hidden`));
    assert.equal(await evaluate(`document.querySelector('#profile-awg-quota').dataset.state`),state);
  }
  await send({...fixture,mainQuota:{state:'ready',usedBytes:null,limitBytes:null},awgQuota:{state:'error'}});
  assert.ok(await evaluate(`document.querySelector('#profile-main-quota [data-quota-amount]').textContent.includes('недоступен')`),'unknown usage is not zero');
  await send({isSignedIn:false});
  assert.equal(await evaluate(`document.querySelectorAll('.device-group').length`),0,'sign-out removes account devices');
  checks.push('unlimited/exhausted/expired/missing/loading/error/sign-out states');
  for(const language of ['ru','en']) for(const theme of ['dark','light']) for(const [width,height,scale] of [[1280,820,1],[760,720,1.5],[520,720,2]]){
    await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:scale,mobile:false});
    await evaluate(`window.deyttSetMapLanguage(${JSON.stringify(language)});window.deyttSetMapTheme(${JSON.stringify(theme)});`);
    await send(fixture);await evaluate(`window.deyttSwitchStage('profile')`);await delay(350);
    assert.equal(await evaluate(`document.getElementById('profile-main-quota').querySelector('[data-quota-title]').textContent`), language==='en'?'main subscription':'основная подписка');
    assert.notEqual(await evaluate(`getComputedStyle(document.getElementById('btn-renew-tg')).display`),'none','renew action remains visible');
    const overflow=await evaluate(`(()=>{const card=document.getElementById('profile-main-quota');const r=card.getBoundingClientRect();return r.right>innerWidth+1 || r.left<0 || document.body.scrollWidth>innerWidth+1 || document.getElementById('stage-profile').scrollWidth>document.getElementById('stage-profile').clientWidth+1;})()`);
    if (overflow) {
      await screenshot(`overflow-${language}-${theme}-${width}`);
      fs.writeFileSync(path.join(output,'overflow.json'),JSON.stringify(await evaluate(`({width:innerWidth,body:document.body.scrollWidth,card:document.getElementById('profile-main-quota').getBoundingClientRect().toJSON(),grid:document.querySelector('.profile-grid-2').getBoundingClientRect().toJSON()})`),null,2));
    }
    assert.equal(overflow,false,`profile overflow ${language}/${theme}/${width}`);
    await screenshot(`profile-${language}-${theme}-${width}-${scale}`);
  }
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:820,deviceScaleFactor:1.25,mobile:false});
  await evaluate(`window.deyttSwitchStage('routes')`);await delay(100);await screenshot('routes-flags-125');
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:820,deviceScaleFactor:2,mobile:false});
  await evaluate(`window.deyttSwitchStage('home');window.deyttSetMapRoute('ru-de');window.deyttSetMapActiveAutoRoute('ru-de');window.deyttSetMapTrafficActive(true)`);await delay(300);await screenshot('map-connected-200');
  assert.equal(exceptions.length,0,'no browser runtime exceptions: '+JSON.stringify(exceptions));
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({checks,exceptions,passed:true},null,2));
  console.log(JSON.stringify({passed:true,checks,output},null,2));
} finally {
  socket?.close(); edge.kill(); server.close();
}
