package org.telegram.messenger;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.ChannelFloatingButton;

import java.util.ArrayList;

public class FeedWidgetProvider extends AppWidgetProvider {

    public static final int PAGE_SIZE = 8;

    private static final String ACTION_REFRESH = "org.telegram.messenger.CHANNEL_FEED_REFRESH";
    private static final String ACTION_OLDER = "org.telegram.messenger.CHANNEL_FEED_OLDER";
    private static final String ACTION_NEWER = "org.telegram.messenger.CHANNEL_FEED_NEWER";

    private static final String PREFERENCES_NAME = "channel_feed_widget_instances";
    private static final String ACCOUNT = "account_";
    private static final String DIALOG_ID = "dialog_id_";
    private static final String TITLE = "title_";
    private static final String ANCHOR = "anchor_";
    private static final String ANCHOR_STACK = "anchor_stack_";
    private static final String OLDEST_ID = "oldest_id_";
    private static final String HAS_OLDER = "has_older_";
    private static final String FORCE_NETWORK = "force_network_";
    private static final String LOAD_ERROR = "load_error_";

    static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Activity.MODE_PRIVATE);
    }

    static int getAccount(Context context, int appWidgetId) {
        return getPreferences(context).getInt(ACCOUNT + appWidgetId, -1);
    }

    static long getDialogId(Context context, int appWidgetId) {
        return getPreferences(context).getLong(DIALOG_ID + appWidgetId, 0);
    }

    static int getAnchor(Context context, int appWidgetId) {
        return getPreferences(context).getInt(ANCHOR + appWidgetId, 0);
    }

    static boolean consumeForceNetwork(Context context, int appWidgetId) {
        SharedPreferences preferences = getPreferences(context);
        boolean result = preferences.getBoolean(FORCE_NETWORK + appWidgetId, false);
        if (result) {
            preferences.edit().remove(FORCE_NETWORK + appWidgetId).apply();
        }
        return result;
    }

    static void savePageResult(Context context, int appWidgetId, int oldestId, boolean hasOlder, boolean loadError) {
        getPreferences(context).edit()
                .putInt(OLDEST_ID + appWidgetId, oldestId)
                .putBoolean(HAS_OLDER + appWidgetId, hasOlder)
                .putBoolean(LOAD_ERROR + appWidgetId, loadError)
                .apply();
        updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId, false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && (ACTION_REFRESH.equals(action) || ACTION_OLDER.equals(action) || ACTION_NEWER.equals(action))) {
            SharedPreferences preferences = getPreferences(context);
            if (ACTION_REFRESH.equals(action)) {
                preferences.edit().putBoolean(FORCE_NETWORK + appWidgetId, true).apply();
            } else if (ACTION_OLDER.equals(action)) {
                int oldestId = preferences.getInt(OLDEST_ID + appWidgetId, 0);
                if (oldestId > 0 && preferences.getBoolean(HAS_OLDER + appWidgetId, false)) {
                    ArrayList<Integer> stack = readAnchorStack(preferences, appWidgetId);
                    stack.add(preferences.getInt(ANCHOR + appWidgetId, 0));
                    preferences.edit()
                            .putInt(ANCHOR + appWidgetId, oldestId)
                            .putString(ANCHOR_STACK + appWidgetId, writeAnchorStack(stack))
                            .putBoolean(FORCE_NETWORK + appWidgetId, true)
                            .apply();
                }
            } else {
                ArrayList<Integer> stack = readAnchorStack(preferences, appWidgetId);
                if (!stack.isEmpty()) {
                    int anchor = stack.remove(stack.size() - 1);
                    preferences.edit()
                            .putInt(ANCHOR + appWidgetId, anchor)
                            .putString(ANCHOR_STACK + appWidgetId, writeAnchorStack(stack))
                            .putBoolean(FORCE_NETWORK + appWidgetId, true)
                            .apply();
                }
            }
            updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId, true);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        ApplicationLoader.postInitApplication();
        for (int appWidgetId : appWidgetIds) {
            if (getDialogId(context, appWidgetId) == 0) {
                ChannelFeedWidgetUtils.ResolvedChannel channel = ChannelFeedWidgetUtils.getResolvedChannel(
                        context, ChannelFloatingButton.getChannelUrl(context));
                if (channel != null) {
                    bindWidget(context, appWidgetId, channel);
                }
            }
            updateWidget(context, appWidgetManager, appWidgetId, true);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, android.os.Bundle newOptions) {
        updateWidget(context, appWidgetManager, appWidgetId, false);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        ApplicationLoader.postInitApplication();
        SharedPreferences preferences = getPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (int appWidgetId : appWidgetIds) {
            int account = preferences.getInt(ACCOUNT + appWidgetId, -1);
            if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
                AccountInstance.getInstance(account).getMessagesStorage().clearWidgetDialogs(appWidgetId);
            }
            removeWidgetKeys(editor, appWidgetId);
        }
        editor.apply();
    }

    public static void bindWidget(Context context, int appWidgetId, ChannelFeedWidgetUtils.ResolvedChannel channel) {
        ApplicationLoader.postInitApplication();
        SharedPreferences preferences = getPreferences(context);
        int oldAccount = preferences.getInt(ACCOUNT + appWidgetId, -1);
        if (oldAccount >= 0 && oldAccount < UserConfig.MAX_ACCOUNT_COUNT && oldAccount != channel.account) {
            AccountInstance.getInstance(oldAccount).getMessagesStorage().clearWidgetDialogs(appWidgetId);
        }
        preferences.edit()
                .putInt(ACCOUNT + appWidgetId, channel.account)
                .putLong(DIALOG_ID + appWidgetId, channel.dialogId)
                .putString(TITLE + appWidgetId, channel.title)
                .putInt(ANCHOR + appWidgetId, 0)
                .remove(ANCHOR_STACK + appWidgetId)
                .remove(OLDEST_ID + appWidgetId)
                .remove(HAS_OLDER + appWidgetId)
                .putBoolean(FORCE_NETWORK + appWidgetId, true)
                .remove(LOAD_ERROR + appWidgetId)
                .apply();

        ArrayList<MessagesStorage.TopicKey> dialogIds = new ArrayList<>();
        dialogIds.add(MessagesStorage.TopicKey.of(channel.dialogId, 0));
        AccountInstance.getInstance(channel.account).getMessagesStorage().putWidgetDialogs(appWidgetId, dialogIds);
    }

    public static void rebindAllWidgets(Context context, ChannelFeedWidgetUtils.ResolvedChannel channel) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, FeedWidgetProvider.class));
        for (int id : ids) {
            bindWidget(context, id, channel);
            updateWidget(context, manager, id, true);
        }
    }

    public static void clearAllWidgetBindings(Context context) {
        ApplicationLoader.postInitApplication();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, FeedWidgetProvider.class));
        SharedPreferences preferences = getPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (int id : ids) {
            int account = preferences.getInt(ACCOUNT + id, -1);
            if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
                AccountInstance.getInstance(account).getMessagesStorage().clearWidgetDialogs(id);
            }
            removeWidgetKeys(editor, id);
        }
        editor.apply();
        for (int id : ids) {
            updateWidget(context, manager, id, true);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        updateWidget(context, appWidgetManager, appWidgetId, true);
    }

    private static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId, boolean refreshList) {
        SharedPreferences preferences = getPreferences(context);
        long dialogId = preferences.getLong(DIALOG_ID + appWidgetId, 0);
        String title = preferences.getString(TITLE + appWidgetId, null);
        ArrayList<Integer> anchorStack = readAnchorStack(preferences, appWidgetId);
        boolean hasOlder = preferences.getBoolean(HAS_OLDER + appWidgetId, false);
        boolean loadError = preferences.getBoolean(LOAD_ERROR + appWidgetId, false);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.feed_widget_layout);
        views.setTextViewText(R.id.feed_widget_title,
                TextUtils.isEmpty(title) ? context.getString(R.string.ChannelFeedWidgetName) : title);
        views.setTextViewText(R.id.feed_widget_page,
                context.getString(R.string.ChannelFeedWidgetPage, anchorStack.size() + 1));
        views.setContentDescription(R.id.feed_widget_refresh, context.getString(R.string.Refresh));
        views.setContentDescription(R.id.feed_widget_newer, context.getString(R.string.ChannelFeedWidgetNewer));
        views.setContentDescription(R.id.feed_widget_older, context.getString(R.string.ChannelFeedWidgetOlder));

        boolean configured = dialogId < 0;
        views.setViewVisibility(R.id.list_view, configured ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.empty_view, configured ? View.GONE : View.VISIBLE);
        views.setTextViewText(R.id.empty_view, context.getString(R.string.ChannelFeedWidgetConfigure));
        views.setViewVisibility(R.id.feed_widget_navigation, configured ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.feed_widget_error, configured && loadError ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.feed_widget_error, context.getString(R.string.ChannelFeedWidgetLoadFailed));

        setAction(context, views, R.id.feed_widget_refresh, ACTION_REFRESH, appWidgetId);
        setAction(context, views, R.id.feed_widget_older, ACTION_OLDER, appWidgetId);
        setAction(context, views, R.id.feed_widget_newer, ACTION_NEWER, appWidgetId);
        views.setBoolean(R.id.feed_widget_newer, "setEnabled", !anchorStack.isEmpty());
        views.setFloat(R.id.feed_widget_newer, "setAlpha", anchorStack.isEmpty() ? 0.4f : 1.0f);
        views.setBoolean(R.id.feed_widget_older, "setEnabled", hasOlder);
        views.setFloat(R.id.feed_widget_older, "setAlpha", hasOlder ? 1.0f : 0.4f);

        if (configured) {
            Intent serviceIntent = new Intent(context, FeedWidgetService.class);
            serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(appWidgetId, R.id.list_view, serviceIntent);
            views.setEmptyView(R.id.list_view, R.id.empty_view);

            Intent openIntent = new Intent(context, LaunchActivity.class);
            openIntent.setAction(Intent.ACTION_VIEW);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openTemplate = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    openIntent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            views.setPendingIntentTemplate(R.id.list_view, openTemplate);
        }

        appWidgetManager.updateAppWidget(appWidgetId, views);
        if (configured && refreshList) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.list_view);
        }
    }

    private static void setAction(Context context, RemoteViews views, int viewId, String action, int appWidgetId) {
        Intent intent = new Intent(context, FeedWidgetProvider.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.setData(Uri.parse("nogram://channel-feed/" + appWidgetId + "/" + action));
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                (appWidgetId * 31) + viewId,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(viewId, pendingIntent);
    }

    private static ArrayList<Integer> readAnchorStack(SharedPreferences preferences, int appWidgetId) {
        ArrayList<Integer> result = new ArrayList<>();
        String value = preferences.getString(ANCHOR_STACK + appWidgetId, "");
        if (TextUtils.isEmpty(value)) {
            return result;
        }
        for (String item : value.split(",")) {
            try {
                result.add(Integer.parseInt(item));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private static String writeAnchorStack(ArrayList<Integer> stack) {
        return TextUtils.join(",", stack);
    }

    private static void removeWidgetKeys(SharedPreferences.Editor editor, int appWidgetId) {
        editor.remove(ACCOUNT + appWidgetId)
                .remove(DIALOG_ID + appWidgetId)
                .remove(TITLE + appWidgetId)
                .remove(ANCHOR + appWidgetId)
                .remove(ANCHOR_STACK + appWidgetId)
                .remove(OLDEST_ID + appWidgetId)
                .remove(HAS_OLDER + appWidgetId)
                .remove(FORCE_NETWORK + appWidgetId)
                .remove(LOAD_ERROR + appWidgetId);
    }
}
