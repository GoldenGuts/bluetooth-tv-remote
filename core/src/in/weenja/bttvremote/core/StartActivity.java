package in.weenja.bttvremote.core;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * No UI. TV Remote opens it to start the core service in the foreground (only a visible app may do that).
 * The core then advertises for a while, so the TV can connect. Without permissions it opens CoreActivity instead.
 */
public class StartActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (CoreActivity.hasPermissions(this)) {
            startForegroundService(new Intent(this, CoreService.class).putExtra(CoreService.EXTRA_WAKE, true));
        } else {
            startActivity(new Intent(this, CoreActivity.class));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
