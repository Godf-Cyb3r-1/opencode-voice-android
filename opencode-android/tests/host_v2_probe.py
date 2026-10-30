import sys,json,os,pathlib,socket,subprocess,tempfile,time,urllib.request,urllib.error
base=pathlib.Path(os.environ.get('OPENCODE_TEST_WORKSPACE',pathlib.Path(__file__).resolve().parents[2]));home=pathlib.Path(tempfile.mkdtemp(prefix='opencode-v2-host-')); project=home/'project';project.mkdir()
s=socket.socket();s.bind(('127.0.0.1',0));port=s.getsockname()[1];s.close()
env=dict(os.environ,HOME=str(home),XDG_DATA_HOME=str(home/'data'),XDG_CONFIG_HOME=str(home/'config'),XDG_CACHE_HOME=str(home/'cache'),XDG_STATE_HOME=str(home/'state'),OPENCODE_PASSWORD='local-host-probe-password',OPENCODE_DISABLE_MODELS_FETCH='1',OPENCODE_DISABLE_FFF='1',OPENCODE_DISABLE_CHANNEL_DB='1')
source=pathlib.Path(os.environ.get('OPENCODE_UPSTREAM_SOURCE',base/'source-v2'))
log=open('/tmp/opencode-v2-backend.log','w')
p=subprocess.Popen([os.environ.get('BUN_EXECUTABLE',str(base/'host-bun/bun-linux-x64/bun')),str(source/'packages/cli/src/index.ts'),'serve','--hostname','127.0.0.1','--port',str(port)],cwd=source/"packages/cli",env=env,stdout=log,stderr=log)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
import base64
headers={'Authorization':'Basic '+base64.b64encode(b'opencode:local-host-probe-password').decode(),'Content-Type':'application/json'}
def request(path,data=None,auth=True):
 r=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if data is None else json.dumps(data).encode(),headers=headers if auth else {})
 with opener.open(r,timeout=10) as response:
  body=response.read()
  return response.status,json.loads(body) if body else None
report={'source_commit':'4b83402a293c3d018216bbf75815175e32f3a16c','runtime':'Bun source on Linux; not Android execution','passed':[]}
try:
 deadline=time.monotonic()+90
 while True:
  try:
   status,info=request('/api/info');break
  except Exception:
   if p.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Source backend startup failed; see backend log')
   time.sleep(.3)
 report['info']=info
 try:request('/api/info',auth=False);raise RuntimeError('Unauthenticated request was accepted')
 except urllib.error.HTTPError as error:assert error.code==401
 report['passed'].append('V2 authenticated info and unauthorized rejection')
 status,created=request('/api/session',{'title':'Android 2.x host probe','location':{'directory':str(project)}})
 session=created['data']['id'];report['passed'].append('V2 session creation at a real temporary project')
 status,context=request('/api/session/'+session+'/context');assert context['data']==[]
 report['passed'].append('V2 active-context endpoint returns the actual empty session context')
 status,_=request('/api/session/'+session+'/shell',{'command':'printf opencode-context-proof > context-proof.txt; cat context-proof.txt'});assert status==204
 assert (project/'context-proof.txt').read_text()=='opencode-context-proof'
 report['passed'].append('V2 shell endpoint executes a real command and writes the project file')
 status,context=request('/api/session/'+session+'/context');assert 'opencode-context-proof' in json.dumps(context)
 report['passed'].append('V2 context endpoint includes the real shell interaction')
 status,active=request('/api/session/active');assert isinstance(active['data'],dict)
 report['passed'].append('V2 active-session monitoring endpoint')
except Exception as error:report['error']=str(error)
finally:
 p.terminate()
 try:p.wait(timeout=8)
 except subprocess.TimeoutExpired:p.kill();p.wait()
 log.close()
 print(json.dumps(report,indent=2))

sys.exit(1 if "error" in report else 0)
