#!/usr/bin/env python3
import json,pathlib,re,statistics,sys
from flicker_ae_sensor import kvs

def analyze(meta):
    m=json.loads(meta.read_text());stem=meta.name.removesuffix('.meta.json');log=meta.with_name(stem+'.logs.txt');start=m['record_start_device_epoch'];end=start+22
    sensor={};exposure={};stages={'OES':{},'OUT':{}};cost={'OES':[],'OUT':[]}
    for line in log.read_text().splitlines():
        try:epoch=float(line.split()[0])
        except (IndexError,ValueError):continue
        if not start<=epoch<=end:continue
        if 'FRM' in line and ' req_comp=' in line:
            k=kvs(line)
            try:
                ts=int(k['ts_ns']);sensor[ts]=int(k['req_comp']);exposure[ts]=(float(k['exp_us']),int(k['iso']),int(k['req_comp']))
            except (ValueError,KeyError):pass
        if 'XMCAM_GL' in line:
            k=kvs(line)
            try:
                stage=k['stage'];stages[stage][int(k['ts'])]=float(k['y']);cost[stage].append(int(k['cost_us']))
            except (ValueError,KeyError):pass
    result={'file':str(meta),'sensor_timestamps':len(sensor),'stages':{}}
    for stage,data in stages.items():
        values=[(ts,data[ts],sensor[ts]) for ts in sorted(data) if ts in sensor];groups=[]
        for row in values:
            if not groups or row[2]!=groups[-1][0][2]:groups.append([])
            groups[-1].append(row)
        transitions=[]
        for old,new in zip(groups,groups[1:]):
            if {old[-1][2],new[0][2]}!={-6,6}:continue
            a=statistics.median(x[1] for x in old[-12:]);b=statistics.median(x[1] for x in new[-12:]);span=b-a
            if abs(span)<1:continue
            diffs=[abs(new[0][1]-old[-1][1])]+[abs(y[1]-x[1]) for x,y in zip(new,new[1:])]
            transitions.append({'from':old[-1][2],'to':new[0][2],'span_luma':span,'largest_frame_fraction':max(diffs)/abs(span)})
        # Exclude edges around every comp transition when checking stable-scene flashes.
        candidates=[]
        for i in range(1,len(values)-1):
            ts,y,c=values[i];before=values[i-1];after=values[i+1]
            if before[2]==c==after[2] and max(ts-before[0],after[0]-ts)<70000000:
                if abs(y-(before[1]+after[1])/2)>1.5 and abs(before[1]-after[1])<.75:candidates.append(ts)
        result['stages'][stage]={'logged_frames':len(data),'joined_sensor_frames':len(values),'cost_us_median':statistics.median(cost[stage]) if cost[stage] else None,'cost_us_p95':sorted(cost[stage])[int(.95*(len(cost[stage])-1))] if cost[stage] else None,'transitions':transitions,'step_fraction_median':statistics.median(x['largest_frame_fraction'] for x in transitions) if transitions else None,'same_comp_isolated_flashes':len(candidates),'isolated_flash_timestamps':candidates}
    common=set(result['stages']['OES']['isolated_flash_timestamps']) & set(result['stages']['OUT']['isolated_flash_timestamps'])
    timeline=sorted(set(stages['OES']) & set(stages['OUT']) & set(sensor));ix={t:i for i,t in enumerate(timeline)};stable=0;excursions=[]
    for t in common:
        i=ix.get(t,-1)
        if 0<i<len(timeline)-1:
            a,b=timeline[i-1],timeline[i+1]
            if exposure.get(a)==exposure.get(t)==exposure.get(b):stable+=1
            excursions.append(abs(stages['OES'][t]-(stages['OES'][a]+stages['OES'][b])/2))
    result['common_flash_timestamps']=len(common);result['constant_sensor_common_flashes']=stable
    result['common_flash_abs_luma_median']=statistics.median(excursions) if excursions else None
    meta.with_name(stem+'.gl-analysis.json').write_text(json.dumps(result,indent=2));return result
if __name__=='__main__':
    for arg in sys.argv[1:]:print(json.dumps(analyze(pathlib.Path(arg)),indent=2))
