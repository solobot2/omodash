package win.solobot.omodash.projection.taskhost;

import android.content.*;
import android.hardware.display.*;
import android.os.*;
import android.view.*;
import java.util.ArrayList;

/** Privileged display owner, started only through the head unit's authorized loopback adbd. */
@android.annotation.TargetApi(29)
public final class TaskHostShell {
    private static final String D = "win.solobot.omodash.taskhost.v1";
    private static VirtualDisplay display;
    private static Surface surface;
    private static Context context;
    private static IBinder host;
    private static Object activityManager;
    private static long token;
    private static int hostUid;
    private static final Handler[] main = new Handler[1];

    private static void stop() {
        if (display != null) { display.release(); display = null; }
        if (surface != null) { surface.release(); surface = null; }
        System.exit(0);
    }
    private static final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != hostUid) throw new SecurityException("Host UID required");
            data.enforceInterface(D);
            if (code == 2) { main[0].post(TaskHostShell::stop); return true; }
            if (code != 1) return false;
            MotionEvent event = MotionEvent.CREATOR.createFromParcel(data);
            try {
                if (display == null) return false;
                MotionEvent.class.getMethod("setDisplayId", int.class).invoke(event, display.getDisplay().getDisplayId());
                Class<?> im = Class.forName("android.hardware.input.InputManager");
                Object instance = im.getMethod("getInstance").invoke(null);
                im.getMethod("injectInputEvent", InputEvent.class, int.class).invoke(instance, event, 0);
            } catch (Exception e) { e.printStackTrace(); }
            finally { event.recycle(); }
            return true;
        }
    };
    private static Parcel request() {
        Parcel p = Parcel.obtain(); p.writeInterfaceToken(D); p.writeLong(token); return p;
    }
    private static void message(String message) throws Exception {
        Parcel p = request(), r = Parcel.obtain();
        try { p.writeString(message); host.transact(3, p, r, 0); r.readException(); }
        finally { p.recycle(); r.recycle(); }
    }
    // 256 is the Android 10/11 hidden DESTROY_CONTENT_ON_REMOVAL flag.
    @android.annotation.SuppressLint("WrongConstant")
    private static void launch(IBinder service) {
        host = service;
        try {
            host.linkToDeath(() -> main[0].post(TaskHostShell::stop), 0);
            Parcel p = request(), r = Parcel.obtain();
            int width, height, density;
            Intent intent;
            try {
                host.transact(1, p, r, 0); r.readException();
                surface = Surface.CREATOR.createFromParcel(r);
                width = r.readInt(); height = r.readInt(); density = r.readInt();
                intent = Intent.CREATOR.createFromParcel(r);
            } finally { p.recycle(); r.recycle(); }
            if (intent.getComponent() == null) throw new IllegalArgumentException("Explicit receiver required");
            display = context.getSystemService(DisplayManager.class).createVirtualDisplay(
                "Omodash-AndroidAuto", width, height, density, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | 256);
            if (display == null) throw new IllegalStateException("Cannot create display");
            ArrayList<String> args = new ArrayList<>();
            java.util.Collections.addAll(args, "am", "start", "--display", Integer.toString(display.getDisplay().getDisplayId()),
                "--windowingMode", "1", "-f", "0x10000000", "-n", intent.getComponent().flattenToString());
            if (intent.getAction() != null) { args.add("-a"); args.add(intent.getAction()); }
            java.lang.Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Android Auto launch timed out");
            }
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                if (result.size() < 8192) result.write(buffer, 0, count);
            }
            String output = result.toString("UTF-8");
            if (process.waitFor() != 0 || output.contains("Error:") || output.contains("Exception"))
                throw new IllegalStateException(output);
            p = request(); r = Parcel.obtain();
            try { p.writeStrongBinder(callback); host.transact(2, p, r, 0); r.readException(); }
            finally { p.recycle(); r.recycle(); }
            System.out.println("Android Auto container ready");
        } catch (Throwable e) {
            e.printStackTrace();
            try { message("Cannot open Android Auto in the launcher. See diagnostic report for details."); }
            catch (Exception ignored) { }
            stop();
        }
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("ADB shell required");
        if (Build.VERSION.SDK_INT < 29 || args.length != 2 || !args[0].matches("[a-zA-Z0-9_.]+"))
            throw new IllegalArgumentException("Host package and session required on Android 10+");
        token = Long.parseLong(args[1]);
        Looper.prepareMainLooper();
        main[0] = new Handler(Looper.getMainLooper());
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        hostUid = context.getPackageManager().getApplicationInfo(args[0], 0).uid;
        activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        String authority = args[0] + ".taskhost";
        Object holder = null;
        for (java.lang.reflect.Method m : activityManager.getClass().getMethods()) {
            if (m.getName().equals("getContentProviderExternal") && m.getParameterTypes().length == 4) {
                holder = m.invoke(activityManager, authority, 0, new Binder(), "OmodashProjection"); break;
            }
        }
        if (holder == null) throw new IllegalStateException("Cannot acquire launcher provider");
        Object provider = holder.getClass().getField("provider").get(holder);
        Bundle result = null;
        for (java.lang.reflect.Method m : provider.getClass().getMethods()) {
            if (!m.getName().equals("call")) continue;
            if (m.getParameterTypes().length == 6)
                result = (Bundle) m.invoke(provider, "com.android.shell", null, authority, "bridge", args[1], null);
            else if (m.getParameterTypes().length == 5)
                result = (Bundle) m.invoke(provider, "com.android.shell", authority, "bridge", args[1], null);
        }
        if (result == null || result.getBinder("bridge") == null) throw new IllegalStateException("Projection closed");
        launch(result.getBinder("bridge"));
        Looper.loop();
    }
}
