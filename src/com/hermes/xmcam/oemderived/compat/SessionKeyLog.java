package com.hermes.xmcam.oemderived.compat;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Range;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic: logs, for every submitted CaptureRequest, which camera session-parameter keys changed versus the previous
 * request (HAL reconfigures the stream when a session key changes). Public API only (CaptureRequest.getKeys/get).
 */
public final class SessionKeyLog {
    private static final Map<String, String> LAST = new HashMap<>();
    private static final TreeMap<String, String> LAST_FRAME_REQUEST = new TreeMap<>();
    private static long lastSensorTimestampNs = 0;

    private SessionKeyLog() {
    }

    private static boolean isSessionKey(String n) {
        return n.contains("sessionParameters") || n.contains("sessionparams") || n.equals("xiaomi.app.module")
                || n.equals("android.control.aeTargetFpsRange") || n.equals("android.control.extendedSceneMode")
                || n.equals("android.control.videoStabilizationMode") || n.equals("android.sensor.testPatternMode")
                || n.startsWith("xiaomi.ai.misd") || n.startsWith("xiaomi.pro.video")
                || n.startsWith("com.xiaomi.objectTrackingConfig");
    }

    private static String str(Object v) {
        if (v == null) {
            return "null";
        }
        if (v.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int n = Array.getLength(v);
            for (int i = 0; i < n && i < 40; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.valueOf(Array.get(v, i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    private static final String LAB_FILE = "/sdcard/Android/data/com.hermes.xmcam.oemderived/files/block.txt";
    private static long labChecked = 0;
    private static long labStamp = -1;
    private static java.util.List<String> labRules = new java.util.ArrayList<>();

    private static synchronized void labReload() {
        long now = System.currentTimeMillis();
        if (now - labChecked < 700) {
            return;
        }
        labChecked = now;
        try {
            java.io.File f = new java.io.File(LAB_FILE);
            long st = f.exists() ? f.lastModified() : 0;
            if (st == labStamp) {
                return;
            }
            labStamp = st;
            java.util.List<String> r = new java.util.ArrayList<>();
            if (st != 0) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0 && line.charAt(0) != '#') {
                        r.add(line);
                    }
                }
                br.close();
            }
            labRules = r;
            Log.i("TAGLAB", "rules=" + r);
        } catch (Throwable t) {
            Log.i("TAGLAB", "reload failed " + t);
        }
    }

    /** Test lab: vendor request tags listed in the app's external files block.txt are not written (trailing * = prefix). */
    public static boolean blocked(String n) {
        if (n == null) {
            return false;
        }
        labReload();
        for (String r : labRules) {
            if (r.endsWith("*") ? n.startsWith(r.substring(0, r.length() - 1)) : n.equals(r)) {
                return true;
            }
        }
        return false;
    }

    private static final String ROUND2_DIR = "/sdcard/Android/data/com.hermes.xmcam.oemderived/files/";

    private static String setting(String name) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(ROUND2_DIR + name + ".txt"))) {
            String s = br.readLine();
            return s == null ? "" : s.trim();
        } catch (java.io.IOException e) { return ""; }
    }

    private static volatile Context applicationContext;

    /** v29: public window vote, app-local and reversible on restart. No global display settings. */
    public static void displayExperiment(android.app.Activity activity) {
        // v30: cache only the app Context, even when the display experiment is disabled.
        if (activity != null) applicationContext = activity.getApplicationContext();
        try {
            String s = setting("displayrate");
            if (!"60".equals(s) && !"120".equals(s) && !"30".equals(s)) {
                Log.i("XMCAM_ROUND2", "display vote unchanged");
                return;
            }
            float rate = Float.parseFloat(s);
            android.view.Window w = activity.getWindow();
            android.view.WindowManager.LayoutParams a = w.getAttributes();
            a.preferredRefreshRate = rate;
            a.preferredDisplayModeId = 0; // Do not retain a conflicting OEM/prior mode vote.
            android.view.Display d = activity.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode current = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == current.getPhysicalWidth() && m.getPhysicalHeight() == current.getPhysicalHeight()
                        && Math.abs(m.getRefreshRate() - rate) < 0.5f) {
                    a.preferredDisplayModeId = m.getModeId();
                    break;
                }
            }
            w.setAttributes(a);
            Log.i("XMCAM_ROUND2", "window vote=" + rate + " modeId=" + a.preferredDisplayModeId);
        } catch (RuntimeException e) { Log.w("XMCAM_ROUND2", "display vote failed", e); }
    }

    /** v31: only an explicitly selected photo experiment can replace SAT id5 with physical id2. */
    public static int photoCameraId(int resolved, int currentMode) {
        if (resolved == 5 && currentMode == 0xa3 && "2".equals(setting("photocamera"))) {
            Log.i("AELAB", "event=CAMERA_SELECT photo=163 from=5 to=2");
            return 2;
        }
        return resolved;
    }

    /** Align the upstream photo-dependent session parameters only in the explicit full experiment. */
    public static int upstreamPhotoMode(android.hardware.camera2.CameraDevice d, int requested, int template) {
        String id = d == null ? "?" : d.getId();
        if (requested == 0x8001 && template == android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
                && ("2".equals(id) || "5".equals(id)) && ("custom" + id).equals(setting("photomode"))
                && "stock".equals(setting("photoparams"))) {
            Log.i("AELAB", "event=UPSTREAM_MODE camera=" + id + " mode=36866 template=" + template);
            return 0x9002;
        }
        return requested;
    }

    /** v29: only called from still-preview session creation, never recording/high-speed helpers. */
    public static int photoSessionMode(android.hardware.camera2.CameraDevice d, int requested,
            CaptureRequest parameters) {
        String id = d == null ? "?" : d.getId();
        String s = setting("photomode");
        // Explicit selection per id; other ids retain the exact old resolver.
        boolean photo = parameters != null && Integer.valueOf(CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW).equals(parameters.get(CaptureRequest.CONTROL_CAPTURE_INTENT));
        if ("2".equals(id) && "normal2".equals(s) && photo) {
            Log.i("AELAB", "event=SESSION_MODE camera=2 final=0");
            return android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR;
        }
        if (("2".equals(id) || "5".equals(id)) && ("custom" + id).equals(s)
                && (requested == 0x8001 || requested == 0x9002) && photo) {
            Log.i("AELAB", "event=SESSION_MODE camera=" + id + " requested=" + requested + " final=36866");
            return 0x9002;
        }
        int resolved = SessionModeCompat.resolveSessionType(d, requested);
        Log.i("XMCAM_ROUND2", "photo camera=" + id + " requested=0x" + Integer.toHexString(requested) + " final=0x" + Integer.toHexString(resolved));
        return resolved;
    }

    private static Boolean directOes;
    public static boolean directOes(boolean original) {
        if (directOes == null) {
            directOes = "oes".equals(setting("renderpath"));
            Log.i("XMCAM_ROUND2", "direct OES bypass=" + directOes);
        }
        return directOes || original;
    }

    /** v29 GL diagnostics: actual incoming OES texture and pre-swap rendered pixels.
     * Disabled unless gldiag.txt contains 'on'. The app's own GL context is used.
     * GLES3 no-attribute triangle + private VAO avoids touching Xiaomi vertex-array pointers.
     */
    private static Boolean glDiagnostic;
    private static boolean glEnabled() {
        if (glDiagnostic == null) glDiagnostic = "on".equals(setting("gldiag"));
        return glDiagnostic;
    }
    private static int glProgram, glFbo, glTexture, glVao;
    private static long lastTextureTimestamp;
    private static final java.nio.ByteBuffer GL_PIXELS = java.nio.ByteBuffer.allocateDirect(64*64*4);
    private static int getGl(int key) {
        int[] v = new int[1]; android.opengl.GLES30.glGetIntegerv(key, v, 0); return v[0];
    }
    private static int shader(int type, String text) {
        int s=android.opengl.GLES30.glCreateShader(type);
        android.opengl.GLES30.glShaderSource(s,text); android.opengl.GLES30.glCompileShader(s);
        int[] ok=new int[1]; android.opengl.GLES30.glGetShaderiv(s,android.opengl.GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) throw new IllegalStateException(android.opengl.GLES30.glGetShaderInfoLog(s));
        return s;
    }
    private static void initGl() {
        if(glProgram!=0 && android.opengl.GLES30.glIsProgram(glProgram)) return;
        String vs="#version 300 es\nuniform mat4 tx; out vec2 uv; void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2)); uv=(tx*vec4(p,0.,1.)).xy; gl_Position=vec4(p*2.-1.,0.,1.); }";
        String fs="#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float; uniform samplerExternalOES tex; in vec2 uv; out vec4 color; void main(){color=texture(tex,uv);}";
        int v=shader(android.opengl.GLES30.GL_VERTEX_SHADER,vs),f=shader(android.opengl.GLES30.GL_FRAGMENT_SHADER,fs);
        glProgram=android.opengl.GLES30.glCreateProgram(); android.opengl.GLES30.glAttachShader(glProgram,v); android.opengl.GLES30.glAttachShader(glProgram,f); android.opengl.GLES30.glLinkProgram(glProgram);
        int[] ok=new int[1]; android.opengl.GLES30.glGetProgramiv(glProgram,android.opengl.GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) throw new IllegalStateException(android.opengl.GLES30.glGetProgramInfoLog(glProgram));
        android.opengl.GLES30.glDeleteShader(v); android.opengl.GLES30.glDeleteShader(f);
        int[] n=new int[1];android.opengl.GLES30.glGenVertexArrays(1,n,0);glVao=n[0];
        android.opengl.GLES30.glGenTextures(1,n,0);glTexture=n[0];android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D,glTexture);
        android.opengl.GLES30.glTexImage2D(android.opengl.GLES30.GL_TEXTURE_2D,0,android.opengl.GLES30.GL_RGBA,64,64,0,android.opengl.GLES30.GL_RGBA,android.opengl.GLES30.GL_UNSIGNED_BYTE,null);
        android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D,android.opengl.GLES30.GL_TEXTURE_MIN_FILTER,android.opengl.GLES30.GL_NEAREST);
        android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D,android.opengl.GLES30.GL_TEXTURE_MAG_FILTER,android.opengl.GLES30.GL_NEAREST);
        android.opengl.GLES30.glGenFramebuffers(1,n,0);glFbo=n[0];android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_FRAMEBUFFER,glFbo);
        android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_FRAMEBUFFER,android.opengl.GLES30.GL_COLOR_ATTACHMENT0,android.opengl.GLES30.GL_TEXTURE_2D,glTexture,0);
        if(android.opengl.GLES30.glCheckFramebufferStatus(android.opengl.GLES30.GL_FRAMEBUFFER)!=android.opengl.GLES30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("diagnostic FBO incomplete");
        Log.i("XMCAM_ROUND2","GL incoming sampler ready "+android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_VERSION));
    }
    public static void rawTexture(android.graphics.SurfaceTexture st,int texture,float[] tx) {
        if(!glEnabled())return;
        long start=android.os.SystemClock.elapsedRealtimeNanos();
        int program=getGl(android.opengl.GLES30.GL_CURRENT_PROGRAM), vao=getGl(android.opengl.GLES30.GL_VERTEX_ARRAY_BINDING);
        int draw=getGl(android.opengl.GLES30.GL_DRAW_FRAMEBUFFER_BINDING),read=getGl(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING);
        int active=getGl(android.opengl.GLES30.GL_ACTIVE_TEXTURE), pack=getGl(android.opengl.GLES30.GL_PIXEL_PACK_BUFFER_BINDING);
        int align=getGl(android.opengl.GLES30.GL_PACK_ALIGNMENT);
        int rowLength=getGl(android.opengl.GLES30.GL_PACK_ROW_LENGTH),skipRows=getGl(android.opengl.GLES30.GL_PACK_SKIP_ROWS),skipPixels=getGl(android.opengl.GLES30.GL_PACK_SKIP_PIXELS);
        int unpack=getGl(android.opengl.GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] viewport=new int[4];android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_VIEWPORT,viewport,0);
        int[] caps={android.opengl.GLES30.GL_BLEND,android.opengl.GLES30.GL_DEPTH_TEST,android.opengl.GLES30.GL_SCISSOR_TEST,android.opengl.GLES30.GL_CULL_FACE,android.opengl.GLES30.GL_STENCIL_TEST,android.opengl.GLES30.GL_RASTERIZER_DISCARD};
        boolean[] enabled=new boolean[caps.length];for(int i=0;i<caps.length;i++){enabled[i]=android.opengl.GLES30.glIsEnabled(caps[i]);android.opengl.GLES30.glDisable(caps[i]);}
        boolean[] mask=new boolean[4];android.opengl.GLES30.glGetBooleanv(android.opengl.GLES30.GL_COLOR_WRITEMASK,mask,0);android.opengl.GLES30.glColorMask(true,true,true,true);
        android.opengl.GLES30.glActiveTexture(android.opengl.GLES30.GL_TEXTURE0);
        int old2d=getGl(android.opengl.GLES30.GL_TEXTURE_BINDING_2D),oldOes=getGl(android.opengl.GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES),oldSampler=getGl(android.opengl.GLES30.GL_SAMPLER_BINDING);
        try {
            android.opengl.GLES30.glBindSampler(0,0);
            android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_PIXEL_UNPACK_BUFFER,0);
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ROW_LENGTH,0);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_ROWS,0);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_PIXELS,0);
            initGl();
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_FRAMEBUFFER,glFbo);android.opengl.GLES30.glViewport(0,0,64,64);
            android.opengl.GLES30.glBindVertexArray(glVao);android.opengl.GLES30.glUseProgram(glProgram);
            android.opengl.GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
            android.opengl.GLES30.glUniform1i(android.opengl.GLES30.glGetUniformLocation(glProgram,"tex"),0);
            android.opengl.GLES30.glUniformMatrix4fv(android.opengl.GLES30.glGetUniformLocation(glProgram,"tx"),1,false,tx,0);
            android.opengl.GLES30.glDrawArrays(android.opengl.GLES30.GL_TRIANGLES,0,3);
            android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_PIXEL_PACK_BUFFER,0);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ALIGNMENT,1);
            GL_PIXELS.clear();android.opengl.GLES30.glReadPixels(0,0,64,64,android.opengl.GLES30.GL_RGBA,android.opengl.GLES30.GL_UNSIGNED_BYTE,GL_PIXELS);
            lastTextureTimestamp=st.getTimestamp();
            glLuma("OES",lastTextureTimestamp,GL_PIXELS,64,64,start);
        }catch(RuntimeException e){glDiagnostic=false;Log.w("XMCAM_ROUND2","GL diagnostic disabled",e);}
        finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_DRAW_FRAMEBUFFER,draw);android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER,read);
            android.opengl.GLES30.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            android.opengl.GLES30.glUseProgram(program);android.opengl.GLES30.glBindVertexArray(vao);
            android.opengl.GLES30.glBindSampler(0,oldSampler);android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_PIXEL_UNPACK_BUFFER,unpack);
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ROW_LENGTH,rowLength);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_ROWS,skipRows);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_PIXELS,skipPixels);
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D,old2d);android.opengl.GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oldOes);android.opengl.GLES30.glActiveTexture(active);
            android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_PIXEL_PACK_BUFFER,pack);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ALIGNMENT,align);
            android.opengl.GLES30.glColorMask(mask[0],mask[1],mask[2],mask[3]);for(int i=0;i<caps.length;i++)if(enabled[i])android.opengl.GLES30.glEnable(caps[i]);
        }
    }
    private static void glLuma(String stage,long ts,java.nio.ByteBuffer pixels,int width,int height,long start) {
        double[] band=new double[3];int[] count=new int[3];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int i=(y*width+x)*4;double value=.299*(pixels.get(i)&255)+.587*(pixels.get(i+1)&255)+.114*(pixels.get(i+2)&255);
            int b=Math.min(2,y*3/height);band[b]+=value;count[b]++;
        }
        double sum=0;int n=0;for(int b=0;b<3;b++){sum+=band[b];n+=count[b];band[b]/=count[b];}
        Log.i("XMCAM_GL","stage="+stage+" ts="+ts+" y="+(sum/n)+" bands="+band[0]+","+band[1]+","+band[2]+" cost_us="+((android.os.SystemClock.elapsedRealtimeNanos()-start)/1000));
    }
    /** Sparse 3x3 patch sampling of the final preview rectangle, before swapBuffers. */
    public static void renderedFrame(android.graphics.Rect area) {
        if(!glEnabled()||area==null||area.width()<64||area.height()<64)return;
        long start=android.os.SystemClock.elapsedRealtimeNanos();
        int pack=getGl(android.opengl.GLES30.GL_PIXEL_PACK_BUFFER_BINDING),align=getGl(android.opengl.GLES30.GL_PACK_ALIGNMENT);
        int read=getGl(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING),draw=getGl(android.opengl.GLES30.GL_DRAW_FRAMEBUFFER_BINDING);
        int rowLength=getGl(android.opengl.GLES30.GL_PACK_ROW_LENGTH),skipRows=getGl(android.opengl.GLES30.GL_PACK_SKIP_ROWS),skipPixels=getGl(android.opengl.GLES30.GL_PACK_SKIP_PIXELS);
        int[] viewport=new int[4];android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_VIEWPORT,viewport,0);
        try {
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ROW_LENGTH,0);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_ROWS,0);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_PIXELS,0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER,draw);
            android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_PIXEL_PACK_BUFFER,0);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ALIGNMENT,1);
            // Pack 9 16x16 patches into a 48x48 pseudo-image; preserve row grouping.
            java.nio.ByteBuffer samples=java.nio.ByteBuffer.allocateDirect(48*48*4);
            for(int by=0;by<3;by++)for(int bx=0;bx<3;bx++){
                GL_PIXELS.clear();int x=area.left+area.width()*(bx+1)/4-8,y=viewport[3]-area.bottom+area.height()*(by+1)/4-8;
                android.opengl.GLES30.glReadPixels(x,y,16,16,android.opengl.GLES30.GL_RGBA,android.opengl.GLES30.GL_UNSIGNED_BYTE,GL_PIXELS);
                for(int py=0;py<16;py++)for(int px=0;px<16*4;px++)samples.put(((by*16+py)*48+bx*16)*4+px,GL_PIXELS.get(py*16*4+px));
            }
            glLuma("OUT",lastTextureTimestamp,samples,48,48,start);
        }finally{
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ROW_LENGTH,rowLength);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_ROWS,skipRows);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_SKIP_PIXELS,skipPixels);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER,read);android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_PIXEL_PACK_BUFFER,pack);android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_PACK_ALIGNMENT,align);
        }
    }

    private static final String READER_USAGE_FILE = "/sdcard/Android/data/com.hermes.xmcam.oemderived/files/readerusage.txt";

    /** Runtime A/B hook for the two YUV readers: "cpu" selects the public CPU_READ_OFTEN usage (0x3). */
    public static ImageReader newImageReader(int width, int height, int format, int maxImages) {
        boolean cpuOnly = false;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(READER_USAGE_FILE))) {
            String setting = br.readLine();
            cpuOnly = setting != null && "cpu".equalsIgnoreCase(setting.trim());
        } catch (java.io.IOException ignored) {
            // Missing/unreadable switch file preserves ImageReader.newInstance() defaults.
        }
        if (cpuOnly && format == 0x23) {
            Log.i("XMCAM_READER", "usage=0x3 size=" + width + "x" + height + " format=0x" + Integer.toHexString(format));
            return ImageReader.newInstance(width, height, format, maxImages, 0x3L);
        }
        Log.i("XMCAM_READER", "usage=default size=" + width + "x" + height + " format=0x" + Integer.toHexString(format));
        return ImageReader.newInstance(width, height, format, maxImages);
    }

    // v30: count actual public submission invocations, including failures, never frame hashes.
    private static final long[][] SUB_COUNTS = new long[2][4];
    private static long submissionCount;
    private static synchronized long submission(String source, int op, CameraCaptureSession s, int size) {
        int row = "LAB".equals(source) ? 1 : 0;
        long id = ++submissionCount;
        long count = ++SUB_COUNTS[row][op];
        Log.i("AESUB", "event=CALL t_ns=" + SystemClock.elapsedRealtimeNanos() + " id=" + id
                + " source=" + source + " op=" + new String[]{"setRepeatingRequest", "capture", "setRepeatingBurst", "captureBurst"}[op]
                + " count=" + count + " total=" + submissionCount + " session=" + System.identityHashCode(s) + " requests=" + size);
        return id;
    }
    private static void submitted(long id, int sequence) {
        Log.i("AESUB", "event=RETURN t_ns=" + SystemClock.elapsedRealtimeNanos() + " id=" + id + " sequence=" + sequence);
    }
    private static void submissionError(long id, CameraCaptureSession s, Exception e) {
        Log.w("AESUB", "event=ERROR t_ns=" + SystemClock.elapsedRealtimeNanos() + " id=" + id, e);
        synchronized (SessionKeyLog.class) {
            final AeLab old = aeLab;
            if (old != null && old.session == s) {
                boolean restore = old.running;
                stopLab("submission-error", false);
                // Best-effort EV0 on the original handler; never retry recursively or revive polling.
                if (restore) old.scheduler.post(new Runnable() { public void run() {
                    synchronized (SessionKeyLog.class) {
                        if (registeredSession != old.session || aeLab != null) return;
                        try { repeat(old.session, old.builder.build(), old.callback, old.originalHandler, "LAB"); }
                        catch (CameraAccessException | RuntimeException failure) { Log.w("AELAB", "error restore failed", failure); }
                    }
                }});
            }
            PENDING_PREVIEW.remove();
        }
    }
    private static int repeat(CameraCaptureSession s, CaptureRequest r, CameraCaptureSession.CaptureCallback c,
            Handler h, String source) throws CameraAccessException {
        long id = submission(source, 0, s, 1);
        try {
            int seq = s.setRepeatingRequest(r, c, h);
            submitted(id, seq);
            return seq;
        } catch (CameraAccessException | RuntimeException e) { submissionError(id, s, e); throw e; }
    }
    public static int setRepeatingRequest(CameraCaptureSession s, CaptureRequest r,
            CameraCaptureSession.CaptureCallback c, Handler h) throws CameraAccessException {
        int seq = repeat(s, r, c, h, "APP");
        // Registration must not change the successful APP result or callback/handler identity.
        try { registerSubmission(s, c, h); }
        catch (RuntimeException e) { Log.w("AELAB", "registration failed", e); onSessionClosed(s); }
        return seq;
    }
    /** v33: count non-preview submission sites without arming a preview experiment from a still builder. */
    public static int setRepeatingRequestExternal(CameraCaptureSession s, CaptureRequest r,
            CameraCaptureSession.CaptureCallback c, Handler h) throws CameraAccessException {
        return repeat(s, r, c, h, "APP");
    }
    public static int capture(CameraCaptureSession s, CaptureRequest r,
            CameraCaptureSession.CaptureCallback c, Handler h) throws CameraAccessException {
        long id = submission("APP", 1, s, 1);
        try { int seq = s.capture(r, c, h); submitted(id, seq); return seq; }
        catch (CameraAccessException | RuntimeException e) { submissionError(id, s, e); throw e; }
    }
    public static int setRepeatingBurst(CameraCaptureSession s, java.util.List<CaptureRequest> r,
            CameraCaptureSession.CaptureCallback c, Handler h) throws CameraAccessException {
        onSessionConfigured(s); // A high-speed replacement cannot inherit an ordinary preview lab.
        synchronized (SessionKeyLog.class) { if (aeLab != null) stopLab("burst", false); }
        long id = submission("APP", 2, s, r == null ? -1 : r.size());
        try { int seq = s.setRepeatingBurst(r, c, h); submitted(id, seq); return seq; }
        catch (CameraAccessException | RuntimeException e) { submissionError(id, s, e); throw e; }
    }
    public static int captureBurst(CameraCaptureSession s, java.util.List<CaptureRequest> r,
            CameraCaptureSession.CaptureCallback c, Handler h) throws CameraAccessException {
        long id = submission("APP", 3, s, r == null ? -1 : r.size());
        try { int seq = s.captureBurst(r, c, h); submitted(id, seq); return seq; }
        catch (CameraAccessException | RuntimeException e) { submissionError(id, s, e); throw e; }
    }

    private static final ThreadLocal<CaptureRequest.Builder> PENDING_PREVIEW = new ThreadLocal<>();
    private static CaptureRequest.Builder previewBuilder;
    private static CameraCaptureSession registeredSession;
    private static final java.util.WeakHashMap<CameraCaptureSession, Boolean> SEEN_SESSIONS = new java.util.WeakHashMap<>();
    private static AeLab aeLab;

    /** Called ONLY just before the ordinary resumePreview build, not high-speed or still builds. */
    public static synchronized void registerPreviewBuilder(CaptureRequest.Builder b) {
        previewBuilder = b;
        PENDING_PREVIEW.set(b);
        if (aeLab != null && aeLab.builder != b) stopLab("builder-replaced", false);
        if ("on".equals(setting("fps30"))) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(30, 30));
        }
        // v32: targeted public antibanding experiment, not a vendor-tag bisection.
        String banding = setting("antibanding");
        if ("50".equals(banding) || "60".equals(banding)) {
            int mode = "50".equals(banding) ? CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ : CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ;
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, mode);
            Log.i("AELAB", "event=ANTIBANDING mode=" + mode);
        }
        if (aeLab != null && aeLab.running) {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeLab.currentEV);
        }
    }

    private static synchronized void registerSubmission(CameraCaptureSession s,
            CameraCaptureSession.CaptureCallback c, Handler originalHandler) {
        CaptureRequest.Builder b = PENDING_PREVIEW.get();
        PENDING_PREVIEW.remove();
        if (registeredSession == s) {
            return; // Keep the initial original callback/handler; enable sampled ONLY once per session.
        }
        stopLab("session-replaced", false);
        registeredSession = s;
        if (SEEN_SESSIONS.put(s, Boolean.TRUE) != null) return;
        Context ctx = applicationContext;
        if (b == null || ctx == null || !"com.hermes.xmcam.oemderived".equals(ctx.getPackageName())
                || !"on".equals(setting("aelab-enabled"))) return;
        Handler scheduler = originalHandler;
        if (scheduler == null && android.os.Looper.myLooper() != null) scheduler = new Handler(android.os.Looper.myLooper());
        if (scheduler == null) { Log.w("AELAB", "not armed: original handler/looper unavailable"); return; }
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(s.getDevice().getId());
            Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            android.util.Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (range == null || !range.contains(0) || step == null || step.floatValue() <= 0) {
                Log.w("AELAB", "not armed: invalid range/step " + range + "/" + step); return;
            }
            aeLab = new AeLab(s, b, c, originalHandler, scheduler, range, step);
            aeLab.schedulePoll();
        } catch (CameraAccessException | RuntimeException e) { Log.w("AELAB", "not armed: characteristics unavailable", e); }
    }
    public static synchronized void onSessionConfigured(CameraCaptureSession s) {
        if (registeredSession != s) { stopLab("configured-replacement", false); registeredSession = null; }
    }
    public static synchronized void onSessionClosed(CameraCaptureSession s) {
        if (registeredSession == s) { stopLab("closed-or-error", false); registeredSession = null; }
        PENDING_PREVIEW.remove();
    }
    public static void closeSession(CameraCaptureSession s) {
        onSessionClosed(s);
        s.close();
    }
    private static void stopLab(String reason, boolean restoreLive) {
        AeLab old = aeLab;
        aeLab = null;
        if (old == null) return;
        old.scheduler.removeCallbacks(old.poll);
        old.scheduler.removeCallbacks(old.stepTask);
        boolean wasRunning = old.running;
        old.running = false;
        // Do not leak an experimental EV into a reused builder or a subsequent app build.
        if (old.touched) {
            try { old.builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0); }
            catch (RuntimeException e) { Log.w("AELAB", "builder restore failed", e); }
        }
        Log.i("AELAB", "event=STOP reason=" + reason + " t_ns=" + SystemClock.elapsedRealtimeNanos() + " restoreEV0=" + old.touched);
        if (restoreLive && wasRunning) {
            // Caller is the original handler's poll runnable. Never submit to a closed/replaced session.
            try { repeat(old.session, old.builder.build(), old.callback, old.originalHandler, "LAB"); }
            catch (CameraAccessException | RuntimeException e) { Log.w("AELAB", "restore failed", e); }
        }
    }
    private static final class AeLab {
        final CameraCaptureSession session;
        final CaptureRequest.Builder builder;
        final Handler scheduler;
        final Range<Integer> range;
        final android.util.Rational compensationStep;
        CameraCaptureSession.CaptureCallback callback;
        Handler originalHandler;
        String lastToken, token;
        boolean running, touched;
        int currentEV, nextStep;
        long startNs, startUptime;
        final int[] indices = {0, -6, 6, -6, 6, 0};
        final Runnable poll = new Runnable() { public void run() {
            synchronized (SessionKeyLog.class) {
                if (aeLab != AeLab.this) return;
                try {
                if (!"on".equals(setting("aelab-enabled"))) { stopLab("disabled", true); return; }
                String value = setting("aelab");
                if (!value.isEmpty() && !value.equals(lastToken)) {
                    lastToken = value;
                    if (!running) {
                        token = value; running = true; nextStep = 0;
                        startNs = SystemClock.elapsedRealtimeNanos(); startUptime = SystemClock.uptimeMillis();
                        Log.i("AELAB", "event=START token=" + token + " t_ns=" + startNs + " camera=" + session.getDevice().getId()
                                + " range=" + range + " step=" + compensationStep + " offsets_ms=0,3000,6000,9000,12000,15000");
                        stepTask.run();
                    } else Log.i("AELAB", "event=BUSY ignored_token=" + value);
                }
                if (aeLab == AeLab.this) schedulePoll();
                } catch (RuntimeException e) { Log.w("AELAB", "poll failed", e); stopLab("poll-error", true); }
            }
        }};
        final Runnable stepTask = new Runnable() { public void run() {
            synchronized (SessionKeyLog.class) {
                if (aeLab != AeLab.this || !running) return;
                try {
                int requested = indices[nextStep];
                currentEV = range.clamp(requested);
                touched = true;
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEV);
                long now = SystemClock.elapsedRealtimeNanos();
                Log.i("AELAB", "event=STEP token=" + token + " index=" + nextStep + " offset_ms=" + (nextStep * 3000)
                        + " t_ns=" + now + " elapsed_ns=" + (now-startNs) + " requested=" + requested + " clamped=" + currentEV
                        + " ev=" + (currentEV * compensationStep.doubleValue()));
                    repeat(session, builder.build(), callback, originalHandler, "LAB");
                    if (++nextStep == indices.length) {
                        running = false;
                        Log.i("AELAB", "event=END token=" + token + " t_ns=" + SystemClock.elapsedRealtimeNanos() + " restoredEV=0");
                    } else if (!scheduler.postAtTime(stepTask, startUptime + nextStep * 3000L)) stopLab("handler-stopped", false);
                } catch (CameraAccessException | RuntimeException e) {
                    Log.w("AELAB", "step failed", e);
                    stopLab("step-error", true);
                }
            }
        }};
        AeLab(CameraCaptureSession s, CaptureRequest.Builder b, CameraCaptureSession.CaptureCallback c, Handler h,
                Handler scheduler, Range<Integer> range, android.util.Rational step) {
            session=s; builder=b; callback=c; originalHandler=h; this.scheduler=scheduler; this.range=range; compensationStep=step;
            lastToken=setting("aelab");
            Log.i("AELAB", "event=ARMED t_ns=" + SystemClock.elapsedRealtimeNanos() + " initial_token=" + lastToken
                    + " range=" + range + " step=" + step + " poll_ms=250");
        }
        void schedulePoll() { if (!scheduler.postDelayed(poll, 250L)) stopLab("handler-stopped", false); }
    }

    private static long lastAeVendorNs;
    private static String aeResult(CaptureResult r) {
        return " ae_mode=" + str(r.get(CaptureResult.CONTROL_AE_MODE))
                + " ae_lock=" + str(r.get(CaptureResult.CONTROL_AE_LOCK))
                + " fps=" + str(r.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE))
                + " precapture=" + str(r.get(CaptureResult.CONTROL_AE_PRECAPTURE_TRIGGER))
                + " antibanding=" + str(r.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE))
                + " af_regions=" + str(r.get(CaptureResult.CONTROL_AF_REGIONS))
                + " ae_regions=" + str(r.get(CaptureResult.CONTROL_AE_REGIONS))
                + " control=" + str(r.get(CaptureResult.CONTROL_MODE));
    }
    private static String aeRequest(CaptureRequest r) {
        if (r == null) return " request=unavailable";
        return " req_ae_mode=" + str(r.get(CaptureRequest.CONTROL_AE_MODE))
                + " req_ae_lock=" + str(r.get(CaptureRequest.CONTROL_AE_LOCK))
                + " req_fps=" + str(r.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
                + " req_precapture=" + str(r.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER))
                + " req_antibanding=" + str(r.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE))
                + " req_af_regions=" + str(r.get(CaptureRequest.CONTROL_AF_REGIONS))
                + " req_ae_regions=" + str(r.get(CaptureRequest.CONTROL_AE_REGIONS))
                + " req_control=" + str(r.get(CaptureRequest.CONTROL_MODE))
                + " req_comp=" + str(r.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
                + " req_exp_ns=" + str(r.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
                + " req_iso=" + str(r.get(CaptureRequest.SENSOR_SENSITIVITY));
    }
    private static void aeVendor(CaptureResult r) {
        long now = SystemClock.elapsedRealtimeNanos();
        if (now-lastAeVendorNs < 1000000000L) return;
        lastAeVendorNs = now;
        for (CaptureResult.Key<?> key : r.getKeys()) {
            String name = key.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("xiaomi") && (name.contains("ae") || name.contains("lux") || name.contains("exposure")
                    || name.contains("convergence") || name.contains("light_event"))) {
                try {
                    Object value = r.get(key);
                    if (value != null) Log.i("AERES", "t_ns=" + now + " n=" + r.getFrameNumber() + " " + key.getName() + "=" + str(value));
                } catch (RuntimeException ignored) { /* unreadable vendor keys are optional */ }
            }
        }
    }

    /** Diagnostic: public sensor results plus a stable full-request hash for every completed preview frame. */
    public static synchronized void frame(TotalCaptureResult r) {
        try {
            Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Integer ae = r.get(CaptureResult.CONTROL_AE_STATE);
            Integer af = r.get(CaptureResult.CONTROL_AF_STATE);
            Integer awb = r.get(CaptureResult.CONTROL_AWB_STATE);
            Long fd = r.get(CaptureResult.SENSOR_FRAME_DURATION);
            Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
            Integer comp = r.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
            Integer tm = r.get(CaptureResult.TONEMAP_MODE);
            Integer nr = r.get(CaptureResult.NOISE_REDUCTION_MODE);

            String reqHash = "unavailable";
            int reqKeys = 0;
            String reqDiff = "unavailable";
            CaptureRequest request = r.getRequest();
            if (request != null) {
                TreeMap<String, String> current = new TreeMap<>();
                for (CaptureRequest.Key<?> k : request.getKeys()) {
                    try {
                        current.put(k.getName(), str(request.get(k)));
                    } catch (Throwable ignored) {
                        // A malformed vendor key must not suppress the public result diagnostics.
                    }
                }
                reqKeys = current.size();
                reqHash = Long.toHexString(requestHash(current));
                StringBuilder changes = new StringBuilder();
                int changeCount = 0;
                for (Map.Entry<String, String> item : current.entrySet()) {
                    String name = item.getKey();
                    String value = item.getValue();
                    if (!LAST_FRAME_REQUEST.containsKey(name) || !value.equals(LAST_FRAME_REQUEST.get(name))) {
                        if (changeCount < 12 && changes.length() < 2200) {
                            if (changes.length() > 0) {
                                changes.append("; ");
                            }
                            changes.append(name).append('=')
                                    .append(LAST_FRAME_REQUEST.containsKey(name) ? brief(LAST_FRAME_REQUEST.get(name)) : "<absent>")
                                    .append("->").append(brief(value));
                        }
                        changeCount++;
                    }
                }
                for (String name : LAST_FRAME_REQUEST.keySet()) {
                    if (!current.containsKey(name)) {
                        if (changeCount < 12 && changes.length() < 2200) {
                            if (changes.length() > 0) {
                                changes.append("; ");
                            }
                            changes.append(name).append("=<removed>");
                        }
                        changeCount++;
                    }
                }
                LAST_FRAME_REQUEST.clear();
                LAST_FRAME_REQUEST.putAll(current);
                reqDiff = changeCount == 0 ? "none" : "count=" + changeCount + " " + changes;
            }

            long deltaMs = ts == null || lastSensorTimestampNs == 0 ? -1 : (ts - lastSensorTimestampNs) / 1000000L;
            if (ts != null) {
                lastSensorTimestampNs = ts;
            }
            Log.i("FRM", "n=" + r.getFrameNumber() + " ts_ns=" + ts + " dt_ms=" + deltaMs
                    + " exp_us=" + (e == null ? -1 : e / 1000) + " iso=" + iso + " ae=" + ae + " af=" + af
                    + " awb=" + awb + " fd_ms=" + (fd == null ? -1 : fd / 1000000) + " comp=" + comp + " tm=" + tm
                    + " nr=" + nr + " req_hash=" + reqHash + " req_keys=" + reqKeys + aeResult(r) + aeRequest(request));
            aeVendor(r);
            if (!"none".equals(reqDiff) && !"unavailable".equals(reqDiff)) {
                Log.i("REQDIFF", "n=" + r.getFrameNumber() + " " + reqDiff);
            }
        } catch (Throwable t) {
            Log.i("FRM", "failed " + t);
        }
    }

    private static String brief(String value) {
        if (value == null || value.length() <= 96) {
            return String.valueOf(value);
        }
        return value.substring(0, 96) + "...";
    }

    private static long requestHash(TreeMap<String, String> values) {
        long hash = 0xcbf29ce484222325L;
        for (Map.Entry<String, String> item : values.entrySet()) {
            String text = item.getKey() + "=" + item.getValue() + "\\n";
            for (int i = 0; i < text.length(); i++) {
                hash ^= text.charAt(i);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    public static synchronized void log(String title, CaptureRequest r) {
        try {
            if (r == null) {
                return;
            }
            TreeMap<String, String> cur = new TreeMap<>();
            for (CaptureRequest.Key<?> k : r.getKeys()) {
                String n = k.getName();
                if (isSessionKey(n)) {
                    cur.put(n, str(r.get(k)));
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cur.entrySet()) {
                String o = LAST.get(e.getKey());
                if (!e.getValue().equals(o)) {
                    sb.append(' ').append(e.getKey()).append('=').append(o == null ? "(absent)" : o).append("->").append(e.getValue());
                }
            }
            for (String n : LAST.keySet()) {
                if (!cur.containsKey(n)) {
                    sb.append(' ').append(n).append("=REMOVED");
                }
            }
            LAST.clear();
            LAST.putAll(cur);
            Log.i("SESSKEY", title + " n=" + cur.size() + " changed:" + (sb.length() == 0 ? " none" : sb));
        } catch (Throwable t) {
            Log.i("SESSKEY", "log failed " + t);
        }
    }

    /** Copies session-parameter keys present on the preview builder but missing on a one-shot builder (public API only). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sync(CaptureRequest.Builder from, CaptureRequest.Builder to) {
        try {
            if (from == null || to == null) {
                return;
            }
            CaptureRequest pr = from.build();
            int n = 0;
            for (CaptureRequest.Key k : pr.getKeys()) {
                if (isSessionKey(k.getName()) && to.get(k) == null) {
                    Object v = pr.get(k);
                    if (v != null) {
                        to.set(k, v);
                        n++;
                    }
                }
            }
            Log.i("SESSKEY", "sync copied " + n + " session keys to one-shot request");
        } catch (Throwable t) {
            Log.i("SESSKEY", "sync failed " + t);
        }
    }
}
