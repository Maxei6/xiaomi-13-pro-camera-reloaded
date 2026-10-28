#!/usr/bin/env python3
"""Analyze real frame curves and labeled EV transitions; never infer optical PWM from screenrecord."""
import argparse,csv,json,math,pathlib,re,statistics,subprocess
F=pathlib.Path(__import__('shutil').which('ffmpeg')).parent
def curve(video):
    meta=json.loads(subprocess.check_output([str(F/'ffprobe'),'-v','error','-select_streams','v:0','-show_entries','frame=best_effort_timestamp_time','-of','json',str(video)]))
    ts=[float(x['best_effort_timestamp_time']) for x in meta['frames']]
    raw=subprocess.check_output([str(F/'ffmpeg'),'-v','error','-i',str(video),'-vf','crop=iw*0.8:ih*0.4:iw*0.1:ih*0.2,scale=64:64,format=gray','-fps_mode','passthrough','-enc_time_base','1:1000000','-f','rawvideo','-'])
    frames=[raw[i:i+4096] for i in range(0,len(raw)-4095,4096)]
    assert len(frames)==len(ts)
    rows=[]
    for i,(t,f) in enumerate(zip(ts,frames)):
        y=sum(f)/4096;bands=[sum(f[k*1024:(k+1)*1024])/1024 for k in range(4)]
        rows.append(dict(frame=i,t=t,luma=y,delta=0 if i==0 else y-rows[-1]['luma'],sad=0 if i==0 else sum(abs(a-b) for a,b in zip(f,frames[i-1]))/4096,**{f'band{k}':v for k,v in enumerate(bands)}))
    with video.with_suffix('.ae-curve.csv').open('w') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    return rows
def transitions(rows,events):
    results=[]
    for event in events:
        t=event['video_t_s'];end=event.get('end_video_t_s',t+3)
        pre=[r['luma'] for r in rows if t-1.0<=r['t']<t-.5]
        post=[r['luma'] for r in rows if end-.6<=r['t']<end-.1]
        if not pre or not post:continue
        a,b=statistics.median(pre),statistics.median(post);span=b-a
        # Encoder PTS zero is not the shell command's wall-clock zero. Include a
        # 0.5s guard before the command timestamp; compare transition SHAPE, not latency.
        segment=[r for r in rows if t-.5<=r['t']<end]
        if abs(span)<1:
            results.append({'event':event,'valid':False,'reason':'less than 1 luma measured change','pre':a,'post':b});continue
        normalized=[(r['t']-t,(r['luma']-a)/span,r['delta']/span) for r in segment]
        t10=next((x[0] for x in normalized if x[1]>=.1),None);t90=next((x[0] for x in normalized if x[1]>=.9),None)
        results.append({'event':event,'valid':True,'pre':a,'post':b,'span_luma':span,'t10_s':t10,'t90_s':t90,'rise10_90_s':None if t10 is None or t90 is None else t90-t10,'largest_frame_fraction':max((abs(x[2]) for x in normalized),default=0),'steps_gt10pct':sum(abs(x[2])>.1 for x in normalized),'overshoot_fraction':max(0,max(x[1] for x in normalized)-1),'reversals_gt5pct':sum(x[2]<-.05 for x in normalized)})
    return results
def sensor(log):
    rows=[];submissions=[]
    for line in log.read_text(errors='replace').splitlines():
        if 'AESUB' in line or 'AELAB' in line:submissions.append(line)
        if 'FRM' not in line or ' exp_us=' not in line:continue
        kv=dict(re.findall(r'(\w+)=([^ ]+)',line))
        try:
            kv['gain_ev']=math.log2(float(kv['exp_us'])*float(kv['iso']));rows.append(kv)
        except (ValueError,KeyError):pass
    ev=[r['gain_ev'] for r in rows];de=[abs(b-a) for a,b in zip(ev,ev[1:])]
    return {'frames':len(rows),'ev_range':max(ev)-min(ev) if ev else None,'max_delta_ev':max(de,default=None),'jumps_gt0_1ev':sum(x>.1 for x in de),'request_hashes':len(set(r.get('req_hash') for r in rows)),'submission_lines':submissions,'states':{k:sorted(set(r.get(k,'?') for r in rows)) for k in ('ae','af','awb','comp','ae_mode','ae_lock','req_ae_mode','req_ae_lock','req_fps','req_precapture')}}
def analyze(video,events=None,log=None):
    rows=curve(video);d=[abs(r['delta']) for r in rows[1:]]
    result={'video':str(video),'frames':len(rows),'duration_s':rows[-1]['t']-rows[0]['t'],'jumps_gt1_5':sum(x>1.5 for x in d),'largest_delta':max(d,default=None),'transitions':transitions(rows,events or [])}
    if log and log.exists():result['sensor']=sensor(log)
    video.with_suffix('.ae-analysis.json').write_text(json.dumps(result,indent=2));return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('video',type=pathlib.Path);p.add_argument('--events',type=pathlib.Path);p.add_argument('--log',type=pathlib.Path);a=p.parse_args()
    print(json.dumps(analyze(a.video,json.loads(a.events.read_text()) if a.events else None,a.log),indent=2))
