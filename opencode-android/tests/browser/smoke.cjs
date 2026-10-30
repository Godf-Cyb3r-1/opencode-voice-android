/* Legacy OpenCode 1.x browser smoke test (not used for the v2 build). The local asset adapter models Android asset interception;
   it does not mock OpenCode APIs. No LLM prompts or paid requests are sent. */
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const os = require('node:os');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const root = path.resolve(__dirname, '../..');
  const assets = path.join(root, 'app/src/main/assets/web-ui');
  const manifest = JSON.parse(fs.readFileSync(path.join(assets, 'manifest.json')));
  const names = new Set(manifest.files);
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'opencode-gui-e2e-'));
  const project = path.join(home, 'project'); fs.mkdirSync(project);
  const password = crypto.randomBytes(24).toString('hex');
  const authorization = 'Basic ' + Buffer.from('opencode:' + password).toString('base64');
  const token = Buffer.from('opencode:' + password).toString('base64');
  let browser, front, backend;
  let engineLog = '';
  const mime = file => ({'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.webmanifest':'application/json','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.woff2':'font/woff2','.woff':'font/woff','.wasm':'application/wasm','.ttf':'font/ttf'}[path.extname(file)] || 'application/octet-stream');
  const report = {backend:'OpenCode 1.19.4 native Linux binary', mockApi:false, tests:[], browserErrors:[], apiFailures:[]};
  try {
    // Resolve an available loopback port, just as the Android service does.
    const reservation = http.createServer();
    await new Promise(r => reservation.listen(0, '127.0.0.1', r));
    const backendPort = reservation.address().port;
    await new Promise(r => reservation.close(r));
    backend = spawn(path.resolve(process.env.OPENCODE_TEST_BINARY), ['serve','--hostname','127.0.0.1','--port',String(backendPort)], {
      env: {...process.env, HOME:home, XDG_CONFIG_HOME:path.join(home,'config'), XDG_DATA_HOME:path.join(home,'data'), XDG_CACHE_HOME:path.join(home,'cache'), XDG_STATE_HOME:path.join(home,'state'), OPENCODE_SERVER_PASSWORD:password, OPENCODE_SERVER_USERNAME:'opencode', OPENCODE_DISABLE_AUTOUPDATE:'true', OPENCODE_DISABLE_MODELS_FETCH:'true'},
      cwd:project, stdio:['ignore','pipe','pipe']
    });
    backend.stdout.on('data',x=>{engineLog+=x;fs.appendFileSync('/tmp/opencode-gui-backend-live.log',x)}); backend.stderr.on('data',x=>{engineLog+=x;fs.appendFileSync('/tmp/opencode-gui-backend-live.log',x)});
    const backendOrigin = `http://127.0.0.1:${backendPort}`;
    const deadline = Date.now()+120000;
    while (true) {
      try { const response=await fetch(backendOrigin+'/global/health',{headers:{Authorization:authorization},signal:AbortSignal.timeout(2000)}); if (response.ok && (await response.json()).healthy) break; } catch {}
      if (Date.now()>deadline || backend.exitCode!==null) throw new Error('Backend did not start: '+engineLog);
      await new Promise(r=>setTimeout(r,250));
    }
    assert.equal((await fetch(backendOrigin+'/global/health',{signal:AbortSignal.timeout(5000)})).status,401);
    report.tests.push('Engine rejects unauthenticated API calls');
    front = http.createServer((req,res)=>{
      const url = new URL(req.url,'http://localhost');
      let file = decodeURIComponent(url.pathname).slice(1);
      if (req.method==='GET' && ((req.headers.accept||'').includes('text/html') || file==='')) file='index.html';
      if (req.method==='GET' && names.has(file)) {
        res.writeHead(200,{'Content-Type':mime(file),'Cache-Control':'no-store'});
        fs.createReadStream(path.join(assets,file)).pipe(res); return;
      }
      const upstream=http.request(backendOrigin+req.url,{method:req.method,headers:{...req.headers,host:`127.0.0.1:${backendPort}`}},response=>{
        res.writeHead(response.statusCode,response.headers);response.pipe(res);
      });
      upstream.on('error',()=>{res.writeHead(502);res.end();});
      req.pipe(upstream);
    });
    await new Promise(r=>front.listen(0,'127.0.0.1',r));
    const origin=`http://127.0.0.1:${front.address().port}`;
    const headers={Authorization:authorization,'Content-Type':'application/json','x-opencode-directory':project};
    const sessionResponse=await fetch(backendOrigin+'/session',{method:'POST',headers,signal:AbortSignal.timeout(15000),body:JSON.stringify({title:'Android GUI smoke test'})});
    assert.equal(sessionResponse.status,200); const session=await sessionResponse.json(); assert.ok(session.id);
    report.tests.push('Actual engine creates a persistent local session');
    browser=await chromium.launch({executablePath:process.env.CHROME_EXECUTABLE,args:['--no-sandbox','--disable-dev-shm-usage'],headless:true});
    const context=await browser.newContext({viewport:{width:412,height:850},deviceScaleFactor:1,isMobile:true,hasTouch:true});
    const page=await context.newPage();
    page.on('pageerror',error=>report.browserErrors.push(error.message));
    page.on('response',response=>{if(response.url().startsWith(origin)&&response.status()>=400)report.apiFailures.push({url:new URL(response.url()).pathname,status:response.status()});});
    await page.goto(origin+'/?auth_token='+encodeURIComponent(token),{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>document.querySelector('#root')?.children.length>0,{timeout:45000});
    await page.waitForTimeout(4000);
    report.homeText=(await page.locator('body').innerText()).slice(0,4000);
    assert.ok(!report.homeText.includes('Something went wrong'),report.homeText);
    assert.equal(new URL(page.url()).searchParams.has('auth_token'),false);
    report.tests.push('Bundled upstream GUI renders at 412px touch viewport and clears bootstrap token');
    const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);
    assert.equal(overflow,false,'Page has horizontal viewport overflow');
    report.tests.push('Phone-sized home view has no horizontal viewport overflow');
    await page.screenshot({path:process.env.GUI_SCREENSHOT || '/tmp/opencode-gui-mobile.png',fullPage:true});
    const dir=Buffer.from(project).toString('base64url');
    await page.goto(origin+'/'+dir+'/session/'+session.id+'?auth_token='+encodeURIComponent(token),{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>document.querySelector('#root')?.children.length>0,{timeout:45000});
    await page.waitForTimeout(4000);
    report.sessionText=(await page.locator('body').innerText()).slice(0,4000);
    assert.ok(!report.sessionText.includes('Something went wrong'),report.sessionText);
    report.tests.push('GUI opens a real engine session through its upstream session route');
    await page.screenshot({path:'/tmp/opencode-gui-session.png',fullPage:true});
    await page.setViewportSize({width:850,height:412});
    await page.waitForTimeout(700);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    report.tests.push('Landscape viewport has no horizontal document overflow');
    assert.deepEqual(report.browserErrors,[]);
    assert.deepEqual(report.apiFailures,[]);
    console.log(JSON.stringify(report,null,2));
  } catch(error) {
    console.log(JSON.stringify({...report,error:String(error),engineLog:engineLog.slice(-3000)},null,2));
    process.exitCode=1;
  } finally {
    if(browser)await browser.close();
    if(front){front.closeAllConnections();await new Promise(r=>front.close(r));}
    if(backend)backend.kill('SIGTERM');
  }
})();
