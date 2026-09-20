package local.mio.op13hyperosfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

public final class RootBootstrapReceiver extends BroadcastReceiver {
    static final String ACTION_BOOTSTRAP =
            "local.mio.op13hyperosfix.action.LSPOSED_BOOTSTRAP";
    static final String ACTION_TOUCH_SAMPLING =
            "local.mio.op13hyperosfix.action.TOUCH_SAMPLING";
    private static final String EXTRA_SHOW_ROOT_ERROR = "show_root_error";
    static final String EXTRA_INTERACTIVE = "interactive";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_TOUCH_SAMPLING.equals(action)) {
            applyTouchSampling(context, intent);
            return;
        }
        if (GameOptimizationHooks.ACTION_APPLY_STATE.equals(action)) {
            applyGameState(context, intent);
            return;
        }
        if (!ACTION_BOOTSTRAP.equals(action)) return;
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        boolean showRootError = intent.getBooleanExtra(EXTRA_SHOW_ROOT_ERROR, false);
        Thread worker = new Thread(() -> {
            RootInstaller.StartResult result = RootInstaller.ensureStarted(application, false);
            if (showRootError && result.shouldShowRootToast()) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(application, R.string.root_missing_toast,
                            Toast.LENGTH_LONG).show();
                    pending.finish();
                });
            } else {
                pending.finish();
            }
        }, "OP13RootBootstrap");
        worker.start();
    }

    private void applyTouchSampling(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        boolean interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, true);
        Thread worker = new Thread(() -> {
            int rate = ModuleConfig.getInt(application,
                    ModuleConfig.TOUCH_SAMPLING_RATE, 120);
            RootInstaller.applyTouchSamplingRate(application, rate, interactive);
            pending.finish();
        }, "OP13TouchSampling");
        worker.start();
    }

    private void applyGameState(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        int rate = intent.getIntExtra(
                GameOptimizationHooks.EXTRA_TOUCH_RATE, 120);
        boolean bypass = intent.getBooleanExtra(
                GameOptimizationHooks.EXTRA_BYPASS, false);
        Thread worker = new Thread(() -> {
            ConfigWriter.Result result = RootInstaller.applyGameOptimizationState(
                    rate, bypass);
            Log.i("OP13GameState", "rate=" + rate + " bypass=" + bypass
                    + " success=" + result.success + " result="
                    + result.message.replace('\n', ';'));
            pending.finish();
        }, "OP13GameState");
        worker.start();
    }
}
