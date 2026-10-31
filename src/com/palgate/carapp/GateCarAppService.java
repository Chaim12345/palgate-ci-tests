package com.palgate.carapp;

import android.content.Intent;

import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** Android Auto entry point (template host binds this, NAVIGATION category). */
public class GateCarAppService extends CarAppService {

    @Override
    public Session onCreateSession() {
        return new GateSession();
    }

    /**
     * Accept any car-app host. For a personal sideloaded tool this is the
     * robust choice (AA host signature keys rotate); a store-distributed app
     * should instead use an allowlist of host signatures.
     */
    @Override
    public HostValidator createHostValidator() {
        // Personal sideloaded tool: accept any host (AA host signing keys rotate).
        // For distribution, use new HostValidator.Builder(this)
        //     .addAllowedHosts(R.array.hosts_allowlist).build();
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    /** One session = one car connection. Its lifecycle gates the monitor FGS. */
    public static class GateSession extends Session {

        @Override
        public Screen onCreateScreen(Intent intent) {
            armStopWatcher();
            State.Prefs.load(getCarContext());
            State.get().post(new Runnable() {
                @Override public void run() { State.get().aaConnected = true; }
            });
            State.get().log("Android Auto session started");
            try {
                Intent start = new Intent(getCarContext(), GateMonitorService.class);
                start.setAction(GateMonitorService.ACTION_START);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    getCarContext().startForegroundService(start);
                } else {
                    getCarContext().startService(start);
                }
            } catch (Exception e) {
                State.get().log("could not start monitor from car UI (" + e.getClass().getSimpleName()
                        + ") — start it from the phone app");
            }
            return new MapScreen(getCarContext());
        }

        @Override
        public void onNewIntent(Intent intent) {
            // no-op
        }

        private void armStopWatcher() {
            getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onDestroy(LifecycleOwner owner) {
                    State.get().post(new Runnable() {
                        @Override public void run() { State.get().aaConnected = false; }
                    });
                    State.get().log("Android Auto session ended");
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        State.Prefs.load(this);
    }
}
