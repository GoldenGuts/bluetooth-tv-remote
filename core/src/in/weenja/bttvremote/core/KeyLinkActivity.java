package in.weenja.bttvremote.core;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;

import in.weenja.bttvremote.common.Protocol;

/**
 * No UI. Deep link bttvremote://key/<action>, for example bttvremote://key/home, or bttvremote://type/<text>.
 * For Good Lock RegiStar back tap, side key shortcuts, or any launcher shortcut.
 */
public class KeyLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        CoreService core = CoreService.instance();
        Vibrator vibrator = getSystemService(Vibrator.class);
        // bttvremote://type/<text> types the text (for tests and automation apps).
        if (uri != null && "type".equals(uri.getHost()) && core != null && uri.getPath() != null) {
            core.type(uri.getPath().substring(1), null);
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        int action = uri != null ? Protocol.action(uri.getLastPathSegment()) : 0;
        if (action != 0 && core != null) {
            core.action(action, ok -> vibrator.vibrate(ok
                    ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    : VibrationEffect.createOneShot(300, 120)));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(300, 120));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
