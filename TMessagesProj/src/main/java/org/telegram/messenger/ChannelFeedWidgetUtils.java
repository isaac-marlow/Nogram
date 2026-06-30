package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.List;
import java.util.Locale;

public final class ChannelFeedWidgetUtils {

    private static final String PREFERENCES_NAME = "channel_feed_widget";
    private static final String RESOLVED_URL = "resolved_url";
    private static final String RESOLVED_ACCOUNT = "resolved_account";
    private static final String RESOLVED_DIALOG_ID = "resolved_dialog_id";
    private static final String RESOLVED_TITLE = "resolved_title";

    public static final int ERROR_INVALID_LINK = 1;
    public static final int ERROR_NOT_CHANNEL = 2;
    public static final int ERROR_NOT_JOINED = 3;
    public static final int ERROR_UNAVAILABLE = 4;

    private ChannelFeedWidgetUtils() {
    }

    public interface ResolveCallback {
        void onResolved(ResolvedChannel channel, int error);
    }

    public static final class ResolvedChannel {
        public final String url;
        public final int account;
        public final long dialogId;
        public final String title;

        private ResolvedChannel(String url, int account, long dialogId, String title) {
            this.url = url;
            this.account = account;
            this.dialogId = dialogId;
            this.title = title;
        }
    }

    private static final class ParsedLink {
        String normalizedUrl;
        String username;
        String inviteHash;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static void saveResolvedChannel(Context context, ResolvedChannel channel) {
        preferences(context).edit()
                .putString(RESOLVED_URL, channel.url)
                .putInt(RESOLVED_ACCOUNT, channel.account)
                .putLong(RESOLVED_DIALOG_ID, channel.dialogId)
                .putString(RESOLVED_TITLE, channel.title)
                .apply();
    }

    public static void clearResolvedChannel(Context context) {
        preferences(context).edit()
                .remove(RESOLVED_URL)
                .remove(RESOLVED_ACCOUNT)
                .remove(RESOLVED_DIALOG_ID)
                .remove(RESOLVED_TITLE)
                .apply();
    }

    public static ResolvedChannel getResolvedChannel(Context context, String currentUrl) {
        SharedPreferences preferences = preferences(context);
        String resolvedUrl = preferences.getString(RESOLVED_URL, null);
        int account = preferences.getInt(RESOLVED_ACCOUNT, -1);
        long dialogId = preferences.getLong(RESOLVED_DIALOG_ID, 0);
        String title = preferences.getString(RESOLVED_TITLE, "");
        if (!TextUtils.equals(resolvedUrl, currentUrl)
                || account < 0
                || account >= UserConfig.MAX_ACCOUNT_COUNT
                || dialogId >= 0
                || TextUtils.isEmpty(title)
                || !UserConfig.getInstance(account).isClientActivated()) {
            return null;
        }
        return new ResolvedChannel(resolvedUrl, account, dialogId, title);
    }

    public static String normalizeTelegramChannelUrl(String value) {
        ParsedLink parsedLink = parseLink(value);
        return parsedLink == null ? null : parsedLink.normalizedUrl;
    }

    public static Runnable resolveChannel(int account, String value, ResolveCallback callback) {
        ParsedLink parsedLink = parseLink(value);
        if (parsedLink == null
                || account < 0
                || account >= UserConfig.MAX_ACCOUNT_COUNT
                || !UserConfig.getInstance(account).isClientActivated()) {
            AndroidUtilities.runOnUIThread(() -> callback.onResolved(null, ERROR_INVALID_LINK));
            return null;
        }

        if (parsedLink.username != null) {
            return MessagesController.getInstance(account).getUserNameResolver().resolve(parsedLink.username, peerId -> {
                if (peerId == null || peerId >= 0) {
                    callback.onResolved(null, peerId == null ? ERROR_UNAVAILABLE : ERROR_NOT_CHANNEL);
                    return;
                }
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-peerId);
                if (!ChatObject.isChannelAndNotMegaGroup(chat)) {
                    callback.onResolved(null, ERROR_NOT_CHANNEL);
                    return;
                }
                callback.onResolved(new ResolvedChannel(parsedLink.normalizedUrl, account, peerId, chat.title), 0);
            });
        }

        TLRPC.TL_messages_checkChatInvite request = new TLRPC.TL_messages_checkChatInvite();
        request.hash = parsedLink.inviteHash;
        int requestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (error != null || !(response instanceof TLRPC.ChatInvite)) {
                        callback.onResolved(null, ERROR_UNAVAILABLE);
                        return;
                    }
                    TLRPC.ChatInvite invite = (TLRPC.ChatInvite) response;
                    TLRPC.Chat chat = invite.chat;
                    if (chat == null) {
                        callback.onResolved(null, invite.channel && invite.broadcast ? ERROR_NOT_JOINED : ERROR_NOT_CHANNEL);
                        return;
                    }
                    if (!ChatObject.isChannelAndNotMegaGroup(chat)) {
                        callback.onResolved(null, ERROR_NOT_CHANNEL);
                        return;
                    }
                    if (!(invite instanceof TLRPC.TL_chatInviteAlready) || !ChatObject.isInChat(chat)) {
                        callback.onResolved(null, ERROR_NOT_JOINED);
                        return;
                    }
                    MessagesController.getInstance(account).putChat(chat, false);
                    callback.onResolved(new ResolvedChannel(parsedLink.normalizedUrl, account, -chat.id, chat.title), 0);
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
        return () -> ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
    }

    private static ParsedLink parseLink(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String url = value.trim();
        if (!url.contains("://")) {
            url = "https://" + url;
        }

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }
        host = host.toLowerCase(Locale.US);
        if (!"t.me".equals(host) && !"telegram.me".equals(host) && !"telegram.dog".equals(host)) {
            return null;
        }

        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        int index = 0;
        if ("s".equalsIgnoreCase(segments.get(0))) {
            index++;
        }
        if (index >= segments.size()) {
            return null;
        }

        String first = segments.get(index);
        ParsedLink result = new ParsedLink();
        if ("joinchat".equalsIgnoreCase(first)) {
            if (++index >= segments.size() || TextUtils.isEmpty(segments.get(index))) {
                return null;
            }
            result.inviteHash = segments.get(index);
            result.normalizedUrl = "https://t.me/+" + result.inviteHash;
        } else if (first.startsWith("+")) {
            result.inviteHash = first.substring(1);
            if (TextUtils.isEmpty(result.inviteHash)) {
                return null;
            }
            result.normalizedUrl = "https://t.me/+" + result.inviteHash;
        } else {
            if (!first.matches("[A-Za-z0-9_]{5,32}")) {
                return null;
            }
            result.username = first;
            result.normalizedUrl = "https://t.me/" + first;
        }
        return result;
    }
}
