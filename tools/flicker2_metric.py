#!/usr/bin/env python3
"""Luma curve + exposure-gain metric; no invented reference and retain each frame."""
import argparse,csv,json,math,pathlib,re,statistics,subprocess
F=pathlib.Path(__import__('shutil').which('ffmpeg')).parent
def quant(v,q):
    s=sorted(v);return s[min(len(s)-1,int((len(s)-1)*q))] if s else None
def summary(v):
    return dict(n=len(v),median=statistics.median(v) if v else None,p95=quant(v,.95),max=max(v) if v else None)
def analyze(p):
    probe=json.loads(subprocess.check_output([str(F/'ffprobe'),'-v','error','-select_streams','v:0','-show_entries','frame=best_effort_timestamp_time','-of','json',str(p)]))
    times=[float(x['best_effort_timestamp_time']) for x in probe['frames']]
    raw=subprocess.check_output([str(F/'ffmpeg'),'-v','error','-i',str(p),'-vf','crop=iw*0.8:ih*0.4:iw*0.1:ih*0.2,scale=64:64,format=gray','-fps_mode','passthrough','-enc_time_base','1:1000000','-f','rawvideo','-'])
    frames=[raw[i:i+4096] for i in range(0,len(raw)-4095,4096)]
    if len(frames)!=len(times):raise ValueError((len(frames),len(times)))
    rows=[]
    for i,f in enumerate(frames):
        mean=sum(f)/4096
        bands=[sum(f[j*1024:(j+1)*1024])/1024 for j in range(4)]
        delta=0 if not rows else mean-rows[-1]['luma']
        sad=0 if i==0 else sum(abs(a-b) for a,b in zip(f,frames[i-1]))/4096
        rows.append(dict(frame=i,t=times[i],luma=mean,delta=delta,sad=sad,**{f'band{j}':v for j,v in enumerate(bands)}))
    with p.with_suffix('.curve.csv').open('w') as out:
        w=csv.DictWriter(out,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
    d=[abs(r['delta']) for r in rows[1:]]
    # Burst and motion/scene changes are reported, never silently counted as AE glitches.
    result={'file':p.name,'frames':len(rows),'duration_s':times[-1]-times[0], 'luma':summary([r['luma'] for r in rows]),'abs_delta':summary(d),'jumps_gt1_5':sum(v>1.5 for v in d),'max_sad':max(r['sad'] for r in rows),'crop':'central 80% width, y=20..60% full display, 64x64'}
    log=p.with_name(p.stem+'-logcat.txt')
    if log.exists():
        fields=[]
        for line in log.read_text(errors='replace').splitlines():
            if 'FRM' not in line or ' exp_us=' not in line:continue
            kv=dict(re.findall(r'(\w+)=([^ ]+)',line))
            try:
                ev=math.log2(float(kv['exp_us'])*float(kv['iso']))
                fields.append({**kv,'gain_ev':ev})
            except (ValueError,KeyError):continue
        if fields:
            ev=[r['gain_ev'] for r in fields];de=[abs(b-a) for a,b in zip(ev,ev[1:])]
            result['sensor']={'frames':len(ev),'gain_ev_range':max(ev)-min(ev),'abs_delta_ev':summary(de),'jumps_gt0_1EV':sum(v>.1 for v in de),'ae_states':sorted(set(r.get('ae','?') for r in fields)),'req_hashes':len(set(r.get('req_hash') for r in fields)),'dt_ms':summary([int(r['dt_ms']) for r in fields if r.get('dt_ms','-1').isdigit()])}
    p.with_suffix('.metric.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
    return result
if __name__=='__main__':
    pa=argparse.ArgumentParser();pa.add_argument('files',nargs='+');args=pa.parse_args()
    for filename in args.files:analyze(pathlib.Path(filename))
