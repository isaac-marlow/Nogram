package org.telegram.ui;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.telegram.messenger.ChannelFeedWidgetUtils;
import org.telegram.messenger.FeedWidgetProvider;
import org.telegram.messenger.R;
import org.telegram.ui.Components.ChannelFloatingButton;

public class FeedWidgetConfigActivity extends ExternalActionActivity {

    private int creatingAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Runnable cancelResolve;
    private boolean configuring;

    @Override
    protected boolean handleIntent(Intent intent, boolean isNew, boolean restore, boolean fromPassword, int intentAccount, int state) {
        if (!checkPasscode(intent, isNew, restore, fromPassword, intentAccount, state)) {
            return false;
        }
        if (configuring) {
            return true;
        }

        Bundle extras = intent.getExtras();
        if (extras != null) {
            creatingAppWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (creatingAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return true;
        }

        setResult(RESULT_CANCELED);
        configuring = true;
        String url = ChannelFloatingButton.getChannelUrl(this);
        ChannelFeedWidgetUtils.ResolvedChannel savedChannel =
                ChannelFeedWidgetUtils.getResolvedChannel(this, url);
        if (savedChannel != null) {
            finishConfiguration(savedChannel);
            return true;
        }

        cancelResolve = ChannelFeedWidgetUtils.resolveChannel(intentAccount, url, (channel, error) -> {
            cancelResolve = null;
            if (isFinishing()) {
                return;
            }
            if (channel == null) {
                int message = error == ChannelFeedWidgetUtils.ERROR_NOT_JOINED
                        ? R.string.ChannelFeedWidgetJoinFirst
                        : error == ChannelFeedWidgetUtils.ERROR_NOT_CHANNEL
                        ? R.string.ChannelFeedWidgetNotChannel
                        : R.string.ChannelFeedWidgetResolveFailed;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            ChannelFeedWidgetUtils.saveResolvedChannel(this, channel);
            finishConfiguration(channel);
        });
        return true;
    }

    private void finishConfiguration(ChannelFeedWidgetUtils.ResolvedChannel channel) {
        FeedWidgetProvider.bindWidget(this, creatingAppWidgetId, channel);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        FeedWidgetProvider.updateWidget(this, appWidgetManager, creatingAppWidgetId);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, creatingAppWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (cancelResolve != null) {
            cancelResolve.run();
            cancelResolve = null;
        }
        super.onDestroy();
    }
}
