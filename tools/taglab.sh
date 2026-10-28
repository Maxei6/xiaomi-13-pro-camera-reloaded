#!/bin/bash
# usage: taglab.sh "<label>" "<rule1,rule2,...>" [runs]   (rules empty = baseline)
A=adb
S=${ADB_SERIAL:?set ADB_SERIAL=ip:port}; F=$(dirname $(command -v ffmpeg))
D=${LAB_DIR:-./lab}; mkdir -p "$D"
P=com.hermes.xmcam.oemderived; BF=/sdcard/Android/data/$P/files/block.txt
a(){ timeout 60 $A -s $S "$@"; }
LABEL="$1"; RULES="$2"; RUNS=${3:-2}
a shell "mkdir -p /sdcard/Android/data/$P/files; printf '%s\n' $(echo "$RULES" | tr ',' ' ') > $BF; [ -z '$RULES' ] && : > $BF; true" >/dev/null 2>&1
for r in $(seq 1 $RUNS); do
  a shell "am force-stop $P; am start -n $P/com.android.camera.Camera" >/dev/null; sleep 13
  a shell "input tap 489 1648; sleep 2; screenrecord --time-limit 8 --size 540x1200 --bit-rate 6000000 /data/local/tmp/lab.mp4" >/dev/null 2>&1
  a pull /data/local/tmp/lab.mp4 $D/lab.mp4 >/dev/null 2>&1
  $F/ffmpeg -v error -y -i $D/lab.mp4 -vf "crop=iw:ih*0.6:0:ih*0.1,scale=64:64,format=gray" -f rawvideo $D/lab.raw
  LAB_DIR_X="$D" python3 - "$LABEL" "$r" <<'PY'
import sys,statistics
d=open(__import__('os').environ['LAB_DIR_X']+'/lab.raw','rb').read(); n=len(d)//4096
m=[sum(d[i*4096:(i+1)*4096])/4096 for i in range(n)]
dif=[abs(b-a) for a,b in zip(m,m[1:])]
print('%-28s run%s frames %3d med %.0f jumps>1.5: %d'%(sys.argv[1],sys.argv[2],n,statistics.median(m) if m else 0,sum(x>1.5 for x in dif)),flush=True)
PY
done
