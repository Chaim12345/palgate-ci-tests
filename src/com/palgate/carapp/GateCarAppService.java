package com.palgate.carapp;

import android.content.Intent;

import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** Android Auto entry point (template host binds this, IoT category - gate/garage control). */
public class GateCarAppService extends CarAppService {

    @Override
    public Session onCreateSession() {
        return new GateSession();
    }

    /**
     * Host validation, scoped by build type:
     * <ul>
     *   <li>TEST builds (assets/test_build.marker, injected only by
     *       build.sh TEST_BUILD=1 - the same marker that disables actuation
     *       at the GateMonitorService choke) accept any host, so third-party
     *       test hosts (AARE in CI, DHU) can bind.</li>
     *   <li>Production builds validate against R.array.hosts_allowlist, the
     *       official Google host certs published inside androidx.car.app
     *       itself (hosts_allowlist_sample in app-1.4.0.aar). An arbitrary
     *       binding host can no longer reach the OPEN intent: without
     *       validation any installed app could bind CarAppService and tap
     *       grid items (cooldowns are rate limiting, not authorization).</li>
     * </ul>
     */
    @Override
    public HostValidator createHostValidator() {
        if (isTestBuild()) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        return new HostValidator.Builder(this)
                .addAllowedHosts(R.array.hosts_allowlist)
                .build();
    }

    /** The marker asset exists only in builds made with TEST_BUILD=1. */
    private boolean isTestBuild() {
        try {
            getAssets().open("test_build.marker").close();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
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
            return new GateGridScreen(getCarContext());
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
