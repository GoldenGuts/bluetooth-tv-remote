package io.github.goldenguts.bttvremote.core;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * No UI. TV Remote opens it to start the core service in the foreground (only a visible app may do that).
 * Without permissions it opens CoreActivity instead.
 */
public class StartActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (CoreActivity.hasPermissions(this)) {
            startForegroundService(new Intent(this, CoreService.class));
        } else {
            startActivity(new Intent(this, CoreActivity.class));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
