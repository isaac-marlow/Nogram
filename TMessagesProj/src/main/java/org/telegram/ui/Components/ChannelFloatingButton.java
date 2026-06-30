package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChannelFeedWidgetUtils;
import org.telegram.messenger.FeedWidgetProvider;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChannelFloatingButton extends ImageView implements NotificationCenter.NotificationCenterDelegate {

    public static final int MAX_LINKS = 5;
    private static final String PREFERENCES_NAME = "channel_floating_button";
    private static final String CHANNEL_URL = "channel_url";
    private static final String CHAT_ID_PREFIX = "chatid:";
    private static final String LINK_COUNT = "link_count";
    private static final String LINK_PREFIX = "link_";
    private static final String POSITION_X = "position_x";
    private static final String POSITION_Y = "position_y";
    private static final String SYSTEM_OVERLAY_ENABLED = "system_overlay_enabled";
    private static WeakReference<ChannelFloatingButton> activeButton;

    private final SharedPreferences preferences;
    private final int touchSlop;
    private final ArrayList<BackupImageView> linkButtons = new ArrayList<>();

    private float downRawX;
    private float downRawY;
    private float downViewX;
    private float downViewY;
    private boolean dragging;
    private boolean expanded;
    private int lastParentWidth;
    private int lastParentHeight;
    private int linksGeneration;
    private View stackIndicator;

    public ChannelFloatingButton(Context context) {
        super(context);

        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setImageResource(R.drawable.msg_channel);
        setScaleType(ScaleType.CENTER);
        setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        setContentDescription("Open channel");
        setClickable(true);
        setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setElevation(AndroidUtilities.dp(6));
        }
        updateColors();
    }

    private void updateColors() {
        setBackground(Theme.createSimpleSelectorCircleDrawable(
                AndroidUtilities.dp(56),
                Theme.getColor(Theme.key_chats_actionBackground),
                Theme.getColor(Theme.key_chats_actionPressedBackground)
        ));
        setColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.SRC_IN);
        if (stackIndicator != null) {
            stackIndicator.setBackground(Theme.createSimpleSelectorCircleDrawable(
                    AndroidUtilities.dp(52),
                    Theme.getColor(Theme.key_chats_actionBackground),
                    Theme.getColor(Theme.key_chats_actionPressedBackground)
            ));
        }
        for (BackupImageView button : linkButtons) {
            button.setBackground(Theme.createSimpleSelectorCircleDrawable(
                    AndroidUtilities.dp(48),
                    Theme.getColor(Theme.key_chats_actionBackground),
                    Theme.getColor(Theme.key_chats_actionPressedBackground)
            ));
        }
    }

    public static String getChannelUrl(Context context) {
        ArrayList<String> links = getChannelUrls(context);
        return links.isEmpty() ? null : links.get(0);
    }

    public static ArrayList<String> getChannelUrls(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        int count = preferences.getInt(LINK_COUNT, -1);
        ArrayList<String> links = new ArrayList<>();
        if (count < 0) {
            String legacyLink = preferences.getString(CHANNEL_URL, null);
            if (!TextUtils.isEmpty(legacyLink)) {
                links.add(legacyLink);
            }
            return links;
        }
        for (int i = 0; i < Math.min(count, MAX_LINKS); i++) {
            String link = preferences.getString(LINK_PREFIX + i, null);
            if (!TextUtils.isEmpty(link)) {
                links.add(link);
            }
        }
        return links;
    }

    public static void setChannelUrl(Context context, String url) {
        ArrayList<String> links = new ArrayList<>();
        links.add(url);
        setChannelUrls(context, links);
    }

    public static void setChannelUrls(Context context, List<String> links) {
        String oldPrimaryUrl = getChannelUrl(context);
        ArrayList<String> savedLinks = new ArrayList<>();
        for (String link : links) {
            if (!TextUtils.isEmpty(link) && savedLinks.size() < MAX_LINKS) {
                savedLinks.add(link);
            }
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putInt(LINK_COUNT, savedLinks.size());
        if (savedLinks.isEmpty()) {
            editor.remove(CHANNEL_URL);
        } else {
            editor.putString(CHANNEL_URL, savedLinks.get(0));
        }
        for (int i = 0; i < MAX_LINKS; i++) {
            if (i < savedLinks.size()) {
                editor.putString(LINK_PREFIX + i, savedLinks.get(i));
            } else {
                editor.remove(LINK_PREFIX + i);
            }
        }
        editor.apply();

        String newPrimaryUrl = savedLinks.isEmpty() ? null : savedLinks.get(0);
        if (!TextUtils.equals(oldPrimaryUrl, newPrimaryUrl)) {
            ChannelFeedWidgetUtils.clearResolvedChannel(context);
            FeedWidgetProvider.clearAllWidgetBindings(context);
        }
        ChannelFloatingButton button = activeButton == null ? null : activeButton.get();
        if (button != null) {
            button.post(button::refreshConfiguredLinks);
        }
        FloatingShortcutOverlayService.refresh(context);
    }

    public static boolean isSystemOverlayEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(SYSTEM_OVERLAY_ENABLED, false);
    }

    public static void setSystemOverlayEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(SYSTEM_OVERLAY_ENABLED, enabled)
                .apply();
        ChannelFloatingButton button = activeButton == null ? null : activeButton.get();
        if (button != null) {
            button.post(button::refreshConfiguredLinks);
        }
        if (enabled) {
            FloatingShortcutOverlayService.start(context);
        } else {
            context.stopService(new Intent(context, FloatingShortcutOverlayService.class));
        }
    }

    public static void openPrimaryLink(Context context) {
        ArrayList<String> links = getChannelUrls(context);
        if (!links.isEmpty()) {
            openConfiguredLink(context, links.get(0));
        }
    }

    public static void bindConfiguredAvatar(BackupImageView button, String link) {
        long peerId = extractPeerId(link);
        if (peerId != 0) {
            TLObject peer = getPeer(peerId);
            if (peer != null) {
                setConfiguredAvatar(button, peer);
            }
            return;
        }
        String username = extractTelegramUsername(link);
        if (TextUtils.isEmpty(username)) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
        TLObject cached = controller.getUserOrChat(username);
        if (cached != null) {
            setConfiguredAvatar(button, cached);
            return;
        }
        controller.getUserNameResolver().resolve(username, resolvedPeerId -> {
            if (!button.isAttachedToWindow() || resolvedPeerId == null) {
                return;
            }
            TLObject peer = controller.getUserOrChat(resolvedPeerId);
            if (peer != null) {
                setConfiguredAvatar(button, peer);
            }
        });
    }

    private static void setConfiguredAvatar(BackupImageView button, TLObject object) {
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(UserConfig.selectedAccount, object);
        button.setSize(-1, -1);
        button.setForUserOrChat(object, avatarDrawable);
    }

    public static String createChatIdLink(String value) {
        try {
            long chatId = Long.parseLong(value);
            if (chatId == 0 || chatId == Long.MIN_VALUE) {
                return null;
            }
            return CHAT_ID_PREFIX + chatId;
        } catch (Exception ignore) {
            return null;
        }
    }

    public static String getDisplayLink(String link) {
        return link != null && link.startsWith(CHAT_ID_PREFIX)
                ? link.substring(CHAT_ID_PREFIX.length())
                : link;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        activeButton = new WeakReference<>(this);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        post(() -> {
            restorePosition();
            refreshConfiguredLinks();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        if (activeButton != null && activeButton.get() == this) {
            activeButton.clear();
            activeButton = null;
        }
        linksGeneration++;
        linkButtons.clear();
        stackIndicator = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View parent = (View) getParent();
        if (parent != null && !dragging && (parent.getWidth() != lastParentWidth || parent.getHeight() != lastParentHeight)) {
            collapseLinks(false);
            restorePosition();
        }
    }

    private void restorePosition() {
        View parent = (View) getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0 || getWidth() == 0 || getHeight() == 0) {
            return;
        }

        lastParentWidth = parent.getWidth();
        lastParentHeight = parent.getHeight();

        float positionX = preferences.getFloat(POSITION_X, 1.0f);
        float positionY = preferences.getFloat(POSITION_Y, 0.72f);
        setX(minX() + availableWidth() * positionX);
        setY(minY() + availableHeight() * positionY);
        updateIndicatorPosition();
    }

    private void savePosition() {
        float positionX = availableWidth() == 0 ? 0 : (getX() - minX()) / availableWidth();
        float positionY = availableHeight() == 0 ? 0 : (getY() - minY()) / availableHeight();
        preferences.edit()
                .putFloat(POSITION_X, Math.max(0, Math.min(1, positionX)))
                .putFloat(POSITION_Y, Math.max(0, Math.min(1, positionY)))
                .apply();
    }

    private float minX() {
        return 0;
    }

    private float minY() {
        return 0;
    }

    private float availableWidth() {
        View parent = (View) getParent();
        return parent == null ? 0 : Math.max(0, parent.getWidth() - getWidth());
    }

    private float availableHeight() {
        View parent = (View) getParent();
        return parent == null ? 0 : Math.max(0, parent.getHeight() - getHeight());
    }

    private float clampX(float x) {
        return Math.max(minX(), Math.min(minX() + availableWidth(), x));
    }

    private float clampY(float y) {
        return Math.max(minY(), Math.min(minY() + availableHeight(), y));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downViewX = getX();
                downViewY = getY();
                dragging = false;
                setPressed(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - downRawX;
                float deltaY = event.getRawY() - downRawY;
                if (!dragging && (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop)) {
                    dragging = true;
                    collapseLinks(true);
                    setPressed(false);
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging) {
                    setX(clampX(downViewX + deltaX));
                    setY(clampY(downViewY + deltaY));
                    updateIndicatorPosition();
                }
                return true;

            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (dragging) {
                    savePosition();
                    dragging = false;
                } else {
                    performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (dragging) {
                    savePosition();
                    dragging = false;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        ArrayList<String> links = getChannelUrls(getContext());
        if (links.size() == 1) {
            openLink(links.get(0));
        } else if (expanded) {
            collapseLinks(true);
        } else {
            expandLinks(links);
        }
        return true;
    }

    private void refreshConfiguredLinks() {
        collapseLinks(false);
        ArrayList<String> links = getChannelUrls(getContext());
        boolean visible = !links.isEmpty() && !isSystemOverlayEnabled(getContext());
        setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            ensureStackIndicator();
        }
        if (stackIndicator != null) {
            stackIndicator.setVisibility(visible && links.size() > 1 ? VISIBLE : GONE);
            updateIndicatorPosition();
        }
    }

    private void ensureStackIndicator() {
        if (stackIndicator != null || !(getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) getParent();
        stackIndicator = new View(getContext());
        stackIndicator.setAlpha(0.65f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            stackIndicator.setElevation(AndroidUtilities.dp(4));
        }
        int index = parent.indexOfChild(this);
        parent.addView(stackIndicator, Math.max(0, index), new FrameLayout.LayoutParams(
                AndroidUtilities.dp(52),
                AndroidUtilities.dp(52),
                Gravity.TOP | Gravity.LEFT
        ));
        updateColors();
    }

    private void updateIndicatorPosition() {
        if (stackIndicator == null || !(getParent() instanceof View)) {
            return;
        }
        View parent = (View) getParent();
        float offsetX = getX() + getWidth() / 2f > parent.getWidth() / 2f
                ? -AndroidUtilities.dp(5) : AndroidUtilities.dp(9);
        float offsetY = getY() + getHeight() / 2f > parent.getHeight() / 2f
                ? -AndroidUtilities.dp(5) : AndroidUtilities.dp(9);
        stackIndicator.setX(getX() + offsetX);
        stackIndicator.setY(getY() + offsetY);
    }

    private void expandLinks(ArrayList<String> links) {
        if (!(getParent() instanceof ViewGroup) || expanded) {
            return;
        }
        expanded = true;
        linksGeneration++;
        int generation = linksGeneration;
        ViewGroup parent = (ViewGroup) getParent();
        float originX = getX() + (getWidth() - AndroidUtilities.dp(48)) / 2f;
        float originY = getY() + (getHeight() - AndroidUtilities.dp(48)) / 2f;
        float parentCenterX = parent.getWidth() / 2f;
        float parentCenterY = parent.getHeight() / 2f;
        float mainCenterX = getX() + getWidth() / 2f;
        float mainCenterY = getY() + getHeight() / 2f;
        double centerAngle = Math.atan2(parentCenterY - mainCenterY, parentCenterX - mainCenterX);
        double span = Math.toRadians(30 + links.size() * 18);
        float radius = AndroidUtilities.dp(108);

        for (int i = 0; i < links.size(); i++) {
            String link = links.get(i);
            BackupImageView button = createLinkButton(link);
            int index = parent.indexOfChild(this);
            parent.addView(button, Math.max(0, index), new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(48),
                    AndroidUtilities.dp(48),
                    Gravity.TOP | Gravity.LEFT
            ));
            linkButtons.add(button);
            resolveAvatar(button, link, generation);

            double angle = links.size() == 1
                    ? centerAngle
                    : centerAngle - span / 2d + span * i / (links.size() - 1d);
            float targetX = mainCenterX + (float) Math.cos(angle) * radius - AndroidUtilities.dp(24);
            float targetY = mainCenterY + (float) Math.sin(angle) * radius - AndroidUtilities.dp(24);
            targetX = Math.max(0, Math.min(parent.getWidth() - AndroidUtilities.dp(48), targetX));
            targetY = Math.max(0, Math.min(parent.getHeight() - AndroidUtilities.dp(48), targetY));

            button.setX(originX);
            button.setY(originY);
            button.setScaleX(0.35f);
            button.setScaleY(0.35f);
            button.setAlpha(0f);
            button.animate()
                    .x(targetX)
                    .y(targetY)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setStartDelay(i * 35L)
                    .setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                    .start();
        }
        animate().rotation(90f).setDuration(200).start();
    }

    private BackupImageView createLinkButton(String link) {
        BackupImageView button = new BackupImageView(getContext());
        button.setRoundRadius(AndroidUtilities.dp(24));
        button.setSize(AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        button.setImageResource(isTelegramLink(link) ? R.drawable.msg_channel : R.drawable.msg_link,
                Theme.getColor(Theme.key_chats_actionIcon));
        button.setContentDescription(getDisplayLink(link));
        button.setClickable(true);
        button.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setElevation(AndroidUtilities.dp(5));
        }
        button.setBackground(Theme.createSimpleSelectorCircleDrawable(
                AndroidUtilities.dp(48),
                Theme.getColor(Theme.key_chats_actionBackground),
                Theme.getColor(Theme.key_chats_actionPressedBackground)
        ));
        button.setOnClickListener(view -> {
            collapseLinks(true);
            openLink(link);
        });
        return button;
    }

    private boolean isTelegramLink(String link) {
        if (link.startsWith(CHAT_ID_PREFIX)) {
            return true;
        }
        try {
            Uri uri = Uri.parse(link);
            if ("tg".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.US);
            return "t.me".equals(host) || "www.t.me".equals(host)
                    || "telegram.me".equals(host) || "www.telegram.me".equals(host)
                    || "telegram.dog".equals(host) || "www.telegram.dog".equals(host);
        } catch (Exception ignore) {
            return false;
        }
    }

    private void resolveAvatar(BackupImageView button, String link, int generation) {
        long peerId = extractPeerId(link);
        if (peerId != 0) {
            TLObject peer = getPeer(peerId);
            if (peer != null) {
                setAvatar(button, peer);
            }
            return;
        }
        String username = extractTelegramUsername(link);
        if (TextUtils.isEmpty(username)) {
            String inviteHash = extractTelegramInviteHash(link);
            if (!TextUtils.isEmpty(inviteHash)) {
                resolveInviteAvatar(button, inviteHash, generation);
            }
            return;
        }
        int account = UserConfig.selectedAccount;
        MessagesController controller = MessagesController.getInstance(account);
        TLObject cached = controller.getUserOrChat(username);
        if (cached != null) {
            setAvatar(button, cached);
            return;
        }
        controller.getUserNameResolver().resolve(username, peerIdNested -> {
            if (generation != linksGeneration || peerIdNested == null || !linkButtons.contains(button)) {
                return;
            }
            TLObject object = controller.getUserOrChat(peerIdNested);
            if (object != null) {
                setAvatar(button, object);
            }
        });
    }

    private void setAvatar(BackupImageView button, TLObject object) {
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(UserConfig.selectedAccount, object);
        button.setSize(-1, -1);
        button.setForUserOrChat(object, avatarDrawable);
    }

    private static String extractTelegramUsername(String link) {
        try {
            Uri uri = Uri.parse(link);
            if ("tg".equalsIgnoreCase(uri.getScheme()) && "resolve".equalsIgnoreCase(uri.getHost())) {
                return uri.getQueryParameter("domain");
            }
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.US);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            if (!"t.me".equals(host) && !"telegram.me".equals(host) && !"telegram.dog".equals(host)) {
                return null;
            }
            List<String> segments = uri.getPathSegments();
            if (segments.isEmpty()) {
                return null;
            }
            int index = "s".equalsIgnoreCase(segments.get(0)) ? 1 : 0;
            if (index >= segments.size()) {
                return null;
            }
            String username = segments.get(index);
            if (username.startsWith("+") || "joinchat".equalsIgnoreCase(username)
                    || "c".equalsIgnoreCase(username) || "addstickers".equalsIgnoreCase(username)
                    || "share".equalsIgnoreCase(username) || "proxy".equalsIgnoreCase(username)
                    || "socks".equalsIgnoreCase(username) || "login".equalsIgnoreCase(username)) {
                return null;
            }
            return username;
        } catch (Exception ignore) {
            return null;
        }
    }

    private void openLink(String link) {
        openConfiguredLink(getContext(), link);
    }

    public static void openConfiguredLink(Context context, String link) {
        Browser.openUrl(context, getConfiguredOpenUrl(link));
    }

    public static String getConfiguredOpenUrl(String link) {
        long peerId = extractPeerId(link);
        if (peerId != 0) {
            TLObject peer = getPeer(peerId);
            if (peer instanceof TLRPC.User || peer == null && peerId > 0) {
                return "tg://openmessage?user_id=" + peerId;
            } else {
                long chatId = peer instanceof TLRPC.Chat ? ((TLRPC.Chat) peer).id : -peerId;
                return "tg://openmessage?chat_id=" + chatId;
            }
        }
        return link;
    }

    private static long extractPeerId(String link) {
        if (!link.startsWith(CHAT_ID_PREFIX)) {
            return 0;
        }
        try {
            long value = Long.parseLong(link.substring(CHAT_ID_PREFIX.length()));
            if (value == 0 || value == Long.MIN_VALUE) {
                return 0;
            }
            long absolute = Math.abs(value);
            String digits = Long.toString(absolute);
            if (digits.startsWith("100") && digits.length() > 3) {
                return -Long.parseLong(digits.substring(3));
            }
            return value;
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static TLObject getPeer(long peerId) {
        MessagesController controller = MessagesController.getInstance(UserConfig.selectedAccount);
        if (peerId > 0) {
            TLRPC.User user = controller.getUser(peerId);
            return user != null ? user : controller.getChat(peerId);
        }
        return controller.getChat(-peerId);
    }

    private String extractTelegramInviteHash(String link) {
        try {
            Uri uri = Uri.parse(link);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.US);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            if (!"t.me".equals(host) && !"telegram.me".equals(host) && !"telegram.dog".equals(host)) {
                return null;
            }
            List<String> segments = uri.getPathSegments();
            if (segments.isEmpty()) {
                return null;
            }
            String first = segments.get(0);
            if (first.startsWith("+") && first.length() > 1) {
                return first.substring(1);
            }
            if ("joinchat".equalsIgnoreCase(first) && segments.size() > 1) {
                return segments.get(1);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void resolveInviteAvatar(BackupImageView button, String inviteHash, int generation) {
        int account = UserConfig.selectedAccount;
        TLRPC.TL_messages_checkChatInvite request = new TLRPC.TL_messages_checkChatInvite();
        request.hash = inviteHash;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation != linksGeneration || !linkButtons.contains(button)
                            || error != null || !(response instanceof TLRPC.ChatInvite)) {
                        return;
                    }
                    TLRPC.ChatInvite invite = (TLRPC.ChatInvite) response;
                    if (invite.chat != null) {
                        MessagesController.getInstance(account).putChat(invite.chat, false);
                        setAvatar(button, invite.chat);
                    } else {
                        AvatarDrawable avatarDrawable = new AvatarDrawable();
                        avatarDrawable.setInfo(invite);
                        if (invite.photo != null) {
                            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(invite.photo.sizes, AndroidUtilities.dp(48));
                            button.setSize(-1, -1);
                            button.setImage(ImageLocation.getForPhoto(size, invite.photo), "48_48", avatarDrawable, invite);
                        } else {
                            button.setSize(-1, -1);
                            button.setImageDrawable(avatarDrawable);
                        }
                    }
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void collapseLinks(boolean animated) {
        if (!expanded && linkButtons.isEmpty()) {
            return;
        }
        expanded = false;
        linksGeneration++;
        float originX = getX() + (getWidth() - AndroidUtilities.dp(48)) / 2f;
        float originY = getY() + (getHeight() - AndroidUtilities.dp(48)) / 2f;
        ArrayList<BackupImageView> buttons = new ArrayList<>(linkButtons);
        linkButtons.clear();
        for (BackupImageView button : buttons) {
            button.animate().cancel();
            if (animated) {
                button.animate()
                        .x(originX)
                        .y(originY)
                        .scaleX(0.35f)
                        .scaleY(0.35f)
                        .alpha(0f)
                        .setDuration(160)
                        .setInterpolator(CubicBezierInterpolator.DEFAULT)
                        .withEndAction(() -> {
                            if (button.getParent() instanceof ViewGroup) {
                                ((ViewGroup) button.getParent()).removeView(button);
                            }
                        })
                        .start();
            } else if (button.getParent() instanceof ViewGroup) {
                ((ViewGroup) button.getParent()).removeView(button);
            }
        }
        animate().rotation(0f).setDuration(animated ? 180 : 0).start();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
        }
    }
}
