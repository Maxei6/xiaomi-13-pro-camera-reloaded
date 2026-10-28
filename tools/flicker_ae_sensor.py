#!/usr/bin/env python3
"""Sensor-domain step shapes: metadata exposure*ISO, not an estimate of emitted display brightness."""
import json,math,pathlib,re,statistics,sys

def kvs(line):return dict(re.findall(r'(\w+)=(.*?)(?=\s+\w+=|$)',line))
def analyze(meta):
    m=json.loads(meta.read_text());log=meta.with_suffix('.logs.txt')
    # with_suffix would be run1.meta.logs.txt; strip the compound meta suffix.
    log=meta.with_name(meta.name.removesuffix('.meta.json')+'.logs.txt')
    start=m['record_start_device_epoch'];end=start+22;frames=[];subs=[];vendor=[]
    for line in log.read_text().splitlines():
        try:epoch=float(line.split()[0])
        except (ValueError,IndexError):continue
        if not start<=epoch<=end:continue
        if 'AESUB' in line and 'event=CALL ' in line:subs.append(kvs(line))
        if 'AERES' in line:vendor.append(line)
        if 'FRM' not in line or ' exp_us=' not in line:continue
        k=kvs(line)
        try:
            frames.append({'epoch':epoch,'ts':int(k['ts_ns'])/1e9,'gain_ev':math.log2(float(k['exp_us'])*float(k['iso'])),'comp':int(k['req_comp']),'fields':k})
        except (KeyError,ValueError):continue
    groups=[]
    for f in frames:
        if not groups or f['comp']!=groups[-1][0]['comp']:groups.append([])
        groups[-1].append(f)
    transitions=[]
    for old,new in zip(groups,groups[1:]):
        a=statistics.median(x['gain_ev'] for x in old[-12:]);b=statistics.median(x['gain_ev'] for x in new[-12:]);span=b-a
        if abs(span)<.05:
            transitions.append({'from_comp':old[-1]['comp'],'to_comp':new[0]['comp'],'valid':False,'ev_span':span});continue
        prior=old[-1]['gain_ev'];deltas=[];progress=[]
        for i,f in enumerate(new):
            deltas.append(abs(f['gain_ev']-prior));prior=f['gain_ev'];progress.append((f['gain_ev']-a)/span)
        i10=next((i for i,x in enumerate(progress) if x>=.1),None);i90=next((i for i,x in enumerate(progress) if x>=.9),None)
        transitions.append({'from_comp':old[-1]['comp'],'to_comp':new[0]['comp'],'valid':True,'frames':len(new),'ev_span':span,'first_result_epoch':new[0]['epoch'],'frames_to10':i10,'frames_to90':i90,'rise10_90_frames':None if i10 is None or i90 is None else i90-i10,'rise10_90_s':None if i10 is None or i90 is None else new[i90]['ts']-new[i10]['ts'],'largest_frame_ev':max(deltas),'largest_frame_fraction':max(deltas)/abs(span),'overshoot_fraction':max(0,max(progress)-1),'plateau_last12_ev_range':max(x['gain_ev'] for x in new[-12:])-min(x['gain_ev'] for x in new[-12:])})
    counts={}
    for s in subs:
        key=s['source']+':'+s['op'];counts[key]=counts.get(key,0)+1
    fields=('ae','ae_mode','ae_lock','fps','precapture','antibanding','ae_regions','af_regions','req_ae_mode','req_ae_lock','req_fps','req_precapture','req_antibanding','req_ae_regions','req_af_regions')
    r={'file':str(meta),'frames_in_record_interval':len(frames),'submission_calls_22s':counts,'app_calls_per_s':sum(v for k,v in counts.items() if k.startswith('APP:'))/22,'states':{k:sorted(set(f['fields'].get(k,'?') for f in frames)) for k in fields},'transitions':transitions,'vendor_result_lines':len(vendor)}
    meta.with_name(meta.name.removesuffix('.meta.json')+'.sensor-analysis.json').write_text(json.dumps(r,indent=2));return r
if __name__=='__main__':
    for arg in sys.argv[1:]:print(json.dumps(analyze(pathlib.Path(arg)),indent=2))
