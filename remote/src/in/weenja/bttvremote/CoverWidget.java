package in.weenja.bttvremote;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Flex Window (cover screen) widget: Samsung's documented way for any app to be on the cover screen.
 * A tap opens the remote on the cover screen (display 1).
 */
public class CoverWidget extends AppWidgetProvider {

    private static final int COVER_DISPLAY = 1;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Intent open = new Intent(context, RemoteActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, options.toBundle());
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.cover_widget);
            views.setOnClickPendingIntent(R.id.widget_root, tap);
            manager.updateAppWidget(id, views);
        }
    }
}
