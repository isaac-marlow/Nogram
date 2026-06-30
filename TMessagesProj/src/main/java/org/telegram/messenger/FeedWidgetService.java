package org.telegram.messenger;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import androidx.core.content.FileProvider;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FeedWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new FeedRemoteViewsFactory(getApplicationContext(), intent);
    }
}

class FeedRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory, NotificationCenter.NotificationCenterDelegate {

    private static final int CACHE_TIMEOUT_SECONDS = 5;
    private static final int NETWORK_TIMEOUT_SECONDS = 15;

    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final Set<String> requestedThumbnails = new HashSet<>();
    private final Context context;
    private final int appWidgetId;
    private final long dialogId;
    private final int anchorId;
    private final AccountInstance accountInstance;

    private volatile CountDownLatch loadLatch;
    private volatile int currentGuid;
    private volatile ArrayList<MessageObject> loadedMessages;
    private volatile boolean loadedToEnd;
    private volatile boolean loadSucceeded;
    private boolean observersAdded;

    FeedRemoteViewsFactory(Context context, Intent intent) {
        this.context = context;
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        int account = FeedWidgetProvider.getAccount(context, appWidgetId);
        dialogId = FeedWidgetProvider.getDialogId(context, appWidgetId);
        anchorId = FeedWidgetProvider.getAnchor(context, appWidgetId);
        accountInstance = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                ? AccountInstance.getInstance(account)
                : null;
    }

    @Override
    public void onCreate() {
        ApplicationLoader.postInitApplication();
    }

    @Override
    public void onDestroy() {
        if (accountInstance != null) {
            int guid = currentGuid;
            if (guid != 0) {
                accountInstance.getConnectionsManager().cancelRequestsForGuid(guid);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (observersAdded) {
                    accountInstance.getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
                    accountInstance.getNotificationCenter().removeObserver(this, NotificationCenter.loadingMessagesFailed);
                    accountInstance.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
                    accountInstance.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
                    observersAdded = false;
                }
            });
        }
    }

    @Override
    public int getCount() {
        return messages.size();
    }

    @Override
    public RemoteViews getViewAt(int position) {
        if (position < 0 || position >= messages.size()) {
            return null;
        }
        MessageObject messageObject = messages.get(position);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.feed_widget_item);

        CharSequence text = !TextUtils.isEmpty(messageObject.caption)
                ? messageObject.caption
                : messageObject.messageText;
        if (TextUtils.isEmpty(text)) {
            text = LocaleController.getString(R.string.UnsupportedMedia2);
        }
        views.setTextViewText(R.id.feed_widget_item_text, text.toString());
        views.setTextViewText(
                R.id.feed_widget_item_date,
                LocaleController.formatShortDateTime(messageObject.messageOwner.date));

        bindThumbnail(views, messageObject);
        bindLinks(views, messageObject);
        return views;
    }

    @Override
    public RemoteViews getLoadingView() {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.feed_widget_loading_item);
        views.setTextViewText(R.id.feed_widget_loading_text, LocaleController.getString(R.string.Loading));
        return views;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public long getItemId(int position) {
        return position >= 0 && position < messages.size() ? messages.get(position).getId() : position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public void onDataSetChanged() {
        messages.clear();
        if (accountInstance == null
                || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                || dialogId >= 0
                || !accountInstance.getUserConfig().isClientActivated()) {
            FeedWidgetProvider.savePageResult(context, appWidgetId, 0, false, true);
            return;
        }

        boolean forceNetwork = FeedWidgetProvider.consumeForceNetwork(context, appWidgetId);
        LoadResult cacheResult = requestPage(true, CACHE_TIMEOUT_SECONDS);
        LoadResult result = cacheResult;
        if (forceNetwork || cacheResult == null || cacheResult.messages.isEmpty()) {
            LoadResult networkResult = requestPage(false, NETWORK_TIMEOUT_SECONDS);
            if (networkResult != null) {
                result = networkResult;
            }
        }

        if (result == null) {
            FeedWidgetProvider.savePageResult(context, appWidgetId, 0, false, true);
            return;
        }

        Set<Integer> ids = new HashSet<>();
        int oldestId = 0;
        boolean hasExtraItem = false;
        for (MessageObject message : result.messages) {
            int messageId = message.getId();
            if (messageId <= 0 || messageId == anchorId || !ids.add(messageId)) {
                continue;
            }
            if (messages.size() >= FeedWidgetProvider.PAGE_SIZE) {
                hasExtraItem = true;
                break;
            }
            messages.add(message);
            oldestId = oldestId == 0 ? messageId : Math.min(oldestId, messageId);
        }
        boolean hasOlder = hasExtraItem || !result.endReached;
        FeedWidgetProvider.savePageResult(context, appWidgetId, oldestId, hasOlder, false);
    }

    private LoadResult requestPage(boolean fromCache, int timeoutSeconds) {
        loadedMessages = null;
        loadedToEnd = false;
        loadSucceeded = false;
        loadLatch = new CountDownLatch(1);
        currentGuid = ConnectionsManager.generateClassGuid();
        int guid = currentGuid;

        AndroidUtilities.runOnUIThread(() -> {
            addObserversIfNeeded();
            accountInstance.getMessagesController().loadMessages(
                    dialogId,
                    0,
                    false,
                    FeedWidgetProvider.PAGE_SIZE + 2,
                    anchorId,
                    0,
                    fromCache,
                    0,
                    guid,
                    MessagesController.LOAD_BACKWARD,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false);
        });

        try {
            if (!loadLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                accountInstance.getConnectionsManager().cancelRequestsForGuid(guid);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            accountInstance.getConnectionsManager().cancelRequestsForGuid(guid);
            return null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        if (!loadSucceeded || loadedMessages == null) {
            return null;
        }
        return new LoadResult(new ArrayList<>(loadedMessages), loadedToEnd);
    }

    private void addObserversIfNeeded() {
        if (observersAdded) {
            return;
        }
        observersAdded = true;
        accountInstance.getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        accountInstance.getNotificationCenter().addObserver(this, NotificationCenter.loadingMessagesFailed);
        accountInstance.getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
        accountInstance.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
    }

    private void bindThumbnail(RemoteViews views, MessageObject messageObject) {
        views.setViewVisibility(R.id.feed_widget_item_image, View.GONE);
        if (messageObject.photoThumbs == null || messageObject.photoThumbs.isEmpty()) {
            return;
        }

        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 320);
        if (size == null || size instanceof TLRPC.TL_photoStrippedSize || size instanceof TLRPC.TL_photoPathSize) {
            return;
        }
        FileLoader fileLoader = accountInstance.getFileLoader();
        File file = fileLoader.getPathToAttach(size, true);
        if (file.exists() && file.length() > 0) {
            try {
                Uri uri = FileProvider.getUriForFile(
                        context,
                        ApplicationLoader.getApplicationId() + ".provider",
                        file);
                grantUriAccessToWidget(uri);
                views.setViewVisibility(R.id.feed_widget_item_image, View.VISIBLE);
                views.setImageViewUri(R.id.feed_widget_item_image, uri);
                views.setContentDescription(R.id.feed_widget_item_image, LocaleController.getString(R.string.AttachPhoto));
            } catch (Exception e) {
                FileLog.e(e);
            }
            return;
        }

        String fileName = FileLoader.getAttachFileName(size);
        synchronized (requestedThumbnails) {
            if (!requestedThumbnails.add(fileName)) {
                return;
            }
        }
        ImageLocation location = ImageLocation.getForObject(size, messageObject.photoThumbsObject);
        fileLoader.loadFile(location, messageObject, "jpg", FileLoader.PRIORITY_LOW, 1);
    }

    private void bindLinks(RemoteViews views, MessageObject messageObject) {
        ArrayList<LinkAction> links = extractLinks(messageObject);
        bindLink(views, R.id.feed_widget_link_1, links.size() > 0 ? links.get(0) : null);
        bindLink(views, R.id.feed_widget_link_2, links.size() > 1 ? links.get(1) : null);
    }

    private void bindLink(RemoteViews views, int viewId, LinkAction link) {
        if (link == null) {
            views.setViewVisibility(viewId, View.GONE);
            return;
        }
        views.setViewVisibility(viewId, View.VISIBLE);
        views.setTextViewText(viewId, link.label);
        views.setContentDescription(viewId, link.label);
        Intent fillInIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link.url));
        views.setOnClickFillInIntent(viewId, fillInIntent);
    }

    private ArrayList<LinkAction> extractLinks(MessageObject messageObject) {
        ArrayList<LinkAction> result = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        String rawText = messageObject.messageOwner.message == null ? "" : messageObject.messageOwner.message;
        ArrayList<TLRPC.MessageEntity> entities = messageObject.messageOwner.entities;
        if (entities != null) {
            for (TLRPC.MessageEntity entity : entities) {
                String url = null;
                String label = null;
                if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    url = entity.url;
                    label = safeSubstring(rawText, entity.offset, entity.length);
                } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                    url = safeSubstring(rawText, entity.offset, entity.length);
                    label = url;
                    if (!TextUtils.isEmpty(url) && !url.contains("://")) {
                        url = "https://" + url;
                    }
                }
                addLink(result, urls, url, label);
            }
        }

        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (media != null && media.webpage != null) {
            addLink(result, urls, media.webpage.url, media.webpage.site_name);
        }

        if (messageObject.messageOwner.reply_markup != null) {
            for (TLRPC.TL_keyboardButtonRow row : messageObject.messageOwner.reply_markup.rows) {
                for (TLRPC.KeyboardButton button : row.buttons) {
                    if (button instanceof TLRPC.TL_keyboardButtonUrl) {
                        addLink(result, urls, button.url, button.text);
                    }
                }
            }
        }
        return result;
    }

    private void addLink(ArrayList<LinkAction> result, Set<String> urls, String url, String label) {
        if (result.size() >= 2 || TextUtils.isEmpty(url)) {
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)
                && !"tg".equalsIgnoreCase(scheme))) {
            return;
        }
        if (!urls.add(url)) {
            return;
        }
        String display = TextUtils.isEmpty(label) ? uri.getHost() : label.trim();
        if (TextUtils.isEmpty(display)) {
            display = LocaleController.getString(R.string.OpenLink);
        }
        if (display.length() > 34) {
            display = display.substring(0, 31) + "\u2026";
        }
        result.add(new LinkAction(url, display));
    }

    private static String safeSubstring(String text, int offset, int length) {
        if (text == null || offset < 0 || length <= 0 || offset >= text.length()) {
            return null;
        }
        int end = Math.min(text.length(), offset + length);
        return text.substring(offset, end);
    }

    private void grantUriAccessToWidget(Uri uri) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homeActivities = context.getPackageManager()
                .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo resolveInfo : homeActivities) {
            context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            int guid = (Integer) args[10];
            if (guid != currentGuid) {
                return;
            }
            loadedMessages = new ArrayList<>((ArrayList<MessageObject>) args[2]);
            loadedToEnd = (Boolean) args[9];
            loadSucceeded = true;
            CountDownLatch latch = loadLatch;
            if (latch != null) {
                latch.countDown();
            }
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            int guid = (Integer) args[0];
            if (guid == currentGuid) {
                CountDownLatch latch = loadLatch;
                if (latch != null) {
                    latch.countDown();
                }
            }
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            String fileName = (String) args[0];
            boolean requested;
            synchronized (requestedThumbnails) {
                requested = requestedThumbnails.remove(fileName);
            }
            if (requested && id == NotificationCenter.fileLoaded) {
                AppWidgetManager.getInstance(context)
                        .notifyAppWidgetViewDataChanged(appWidgetId, R.id.list_view);
            }
        }
    }

    private static final class LoadResult {
        final ArrayList<MessageObject> messages;
        final boolean endReached;

        LoadResult(ArrayList<MessageObject> messages, boolean endReached) {
            this.messages = messages;
            this.endReached = endReached;
        }
    }

    private static final class LinkAction {
        final String url;
        final String label;

        LinkAction(String url, String label) {
            this.url = url;
            this.label = label;
        }
    }
}
