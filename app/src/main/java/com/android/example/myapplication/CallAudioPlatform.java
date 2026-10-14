package com.android.example.myapplication;

import android.media.AudioManager;
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Locale;

/** Vendor parameters are not Android APIs and must never be applied to an unrelated HAL. */
final class CallAudioPlatform {
    private static final String TAG = "CallAudioPlatform";
    private final AudioManager audio;
    private Method setParameters;
    private boolean gainsEnabled;
    final boolean unisoc;
    final boolean supportsVoiceGains;

    CallAudioPlatform(AudioManager audio) {
        this.audio = audio;
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER : "";
        String identity = (Build.MODEL + " " + Build.HARDWARE + " " + soc).toLowerCase(Locale.ROOT);
        unisoc = identity.contains("m2518") || identity.contains("unisoc")
                || identity.contains("spreadtrum") || identity.contains("ums9621");
        boolean supports = false;
        if (unisoc) {
            // This is a vendor contract selected by SoC/model, not an Android capability query.
            supports = true;
            try {
                setParameters = AudioManager.class.getMethod("setParameters", String.class);
                Method getParameters = AudioManager.class.getMethod("getParameters", String.class);
                String reply = (String) getParameters.invoke(audio,
                        "voice_ul_gain_on;voice_dl_gain_on;voice_ul_gain_l;voice_dl_gain_l");
                // UNISOC's parameter parser does not echo these keys on all M2518 builds.
                // The documented protocol is still the only working call-audio path on this
                // platform, so an empty getParameters() response cannot select MODE_CALL_REDIRECT.
                Log.i(TAG, "UNISOC voice gain query: " + reply + "; using documented protocol");
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "Voice gain protocol unavailable", e);
            }
        }
        supportsVoiceGains = supports;
    }

    int captureSampleRate() { return PcmFormat.TARGET_SAMPLE_RATE_HZ; }

    void muteOriginal(boolean tx, boolean rx) {
        if (!supportsVoiceGains) throw new UnsupportedOperationException("HAL 未确认支持文档中的增益协议");
        if (!gainsEnabled) {
            // Mark first, so a partial failure still restores the HAL on the error path.
            gainsEnabled = true;
            send("voice_ul_gain_on=true");
            send("voice_dl_gain_on=true");
        }
        send("voice_ul_gain_l=" + (tx ? 0 : 1) + ";voice_ul_gain_r=" + (tx ? 0 : 1));
        send("voice_dl_gain_l=" + (rx ? 0 : 1) + ";voice_dl_gain_r=" + (rx ? 0 : 1));
    }

    void restoreOriginal() {
        if (!gainsEnabled) return;
        // Restore both directions before disabling either gain switch.
        try {
            send("voice_ul_gain_l=1;voice_ul_gain_r=1");
            send("voice_dl_gain_l=1;voice_dl_gain_r=1");
            send("voice_ul_gain_on=false");
            send("voice_dl_gain_on=false");
            gainsEnabled = false;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to restore vendor voice gains", e);
            throw e;
        }
    }

    private void send(String parameters) {
        try {
            setParameters.invoke(audio, parameters);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("HAL 参数下发失败：" + parameters, e);
        }
    }
}
