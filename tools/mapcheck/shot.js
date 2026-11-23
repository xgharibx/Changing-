const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const [car, foot, out] = process.argv.slice(2);
  const r = [JSON.parse(fs.readFileSync(car)), JSON.parse(fs.readFileSync(foot))].map(x => ({ mode: x.mode, path: x.path, entrance: x.entrance, distanceM: x.distanceM, durationS: x.durationS, trafficStatus: x.trafficStatus }));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] }); const p = await b.newPage({ viewport: { width: 900, height: 700 } });
  p.on('console', m => { if (m.type() === 'error') console.log('console', m.text()); });
  await p.goto('file://' + __dirname + '/route.html?r=' + encodeURIComponent(JSON.stringify(r)));
  await p.waitForFunction(() => document.title === 'ready', null, { timeout: 90000 }).catch(e => console.log('timeout', e.message));
  await p.waitForTimeout(1500); await p.screenshot({ path: out }); await b.close();
})();
