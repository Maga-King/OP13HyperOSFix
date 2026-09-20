package local.mio.op13hyperosfix.deviceparams;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.animation.DecelerateInterpolator;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

final class MusicController {
    private static final String TAG = "COSOS4Music: ";
    private static final Map<Object, Session> SESSIONS = new WeakHashMap<>();

    private MusicController() {
    }

    static synchronized void onResume(Object fragment, Context settingsContext) {
        HookConfig config = HookConfig.load(settingsContext);
        if (!config.musicEnabled) {
            stopNow(fragment);
            return;
        }
        stopNow(fragment);
        Session session = new Session(settingsContext);
        if (session.start()) SESSIONS.put(fragment, session);
    }

    static synchronized void onPause(Object fragment) {
        Session session = SESSIONS.remove(fragment);
        if (session != null) session.fadeAndRelease();
    }

    static synchronized void stopNow(Object fragment) {
        Session session = SESSIONS.remove(fragment);
        if (session != null) session.release();
    }

    private static final class Session {
        private final Context context;
        private MediaPlayer player;
        private AudioManager audioManager;
        private AssetFileDescriptor asset;
        private ValueAnimator animator;
        private int previousVolume;
        private boolean adjustedVolume;
        private boolean released;

        Session(Context context) {
            this.context = context.getApplicationContext();
        }

        boolean start() {
            try {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (previousVolume <= 0 || audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    XposedBridge.log(TAG + "silent media stream; playback skipped");
                    return false;
                }
                int target = Math.min(6,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                if (target > 0 && target != previousVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                    adjustedVolume = true;
                }

                Context module = context.createPackageContext(
                        ConfigContract.PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY);
                asset = module.getAssets().openFd("cos_about_music.flac");
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                player.setDataSource(asset.getFileDescriptor(),
                        asset.getStartOffset(), asset.getLength());
                player.setLooping(true);
                player.setVolume(0f, 0f);
                player.prepare();
                player.start();
                animateVolume(0f, 1f, 1800L, false);
                return true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "start failed safely: " + t);
                release();
                return false;
            }
        }

        void fadeAndRelease() {
            if (released) return;
            try {
                if (player != null && player.isPlaying()) {
                    animateVolume(1f, 0f, 1500L, true);
                    return;
                }
            } catch (Throwable ignored) {
            }
            release();
        }

        private void animateVolume(float from, float to, long duration,
                                   final boolean releaseAtEnd) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(duration);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    try {
                        float value = (Float) animation.getAnimatedValue();
                        if (player != null) player.setVolume(value, value);
                        if (releaseAtEnd && animation.getAnimatedFraction() >= 1f) release();
                    } catch (Throwable ignored) {
                        release();
                    }
                }
            });
            animator.start();
        }

        void release() {
            if (released) return;
            released = true;
            try {
                if (animator != null) animator.cancel();
            } catch (Throwable ignored) {
            }
            animator = null;
            try {
                if (player != null) player.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (player != null) player.release();
            } catch (Throwable ignored) {
            }
            player = null;
            try {
                if (asset != null) asset.close();
            } catch (Throwable ignored) {
            }
            asset = null;
            try {
                if (adjustedVolume && audioManager != null) {
                    audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC, previousVolume, 0);
                }
            } catch (Throwable ignored) {
            }
            adjustedVolume = false;
        }
    }
}
