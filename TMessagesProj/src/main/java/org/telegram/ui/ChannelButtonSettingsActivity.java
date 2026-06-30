package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChannelFeedWidgetUtils;
import org.telegram.messenger.FeedWidgetProvider;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.ChannelFloatingButton;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Locale;

public class ChannelButtonSettingsActivity extends BaseFragment {

    private final EditTextBoldCursor[] linkFields = new EditTextBoldCursor[ChannelFloatingButton.MAX_LINKS];
    private TextView screenInfoView;
    private TextView fieldInfoView;
    private TextView okButton;
    private TextCheckCell systemOverlayCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.FloatingChannelButton));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = scrollView;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        screenInfoView = new TextView(context);
        screenInfoView.setText(LocaleController.getString(R.string.FloatingChannelButtonScreenInfo));
        screenInfoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText8));
        screenInfoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        screenInfoView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        content.addView(screenInfoView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                24, 24, 24, 12
        ));

        HeaderCell headerCell = new HeaderCell(context);
        headerCell.setText(LocaleController.getString(R.string.FloatingChannelButtonLinks));
        content.addView(headerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        systemOverlayCell = new TextCheckCell(context);
        systemOverlayCell.setTextAndCheck(
                LocaleController.getString(R.string.FloatingShortcutSystemOverlay),
                ChannelFloatingButton.isSystemOverlayEnabled(context),
                false);
        systemOverlayCell.setOnClickListener(view -> {
            boolean enabled = !ChannelFloatingButton.isSystemOverlayEnabled(context);
            ChannelFloatingButton.setSystemOverlayEnabled(context, enabled);
            systemOverlayCell.setChecked(enabled);
            if (enabled && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
                startActivityForResult(intent, 500);
            }
        });
        content.addView(systemOverlayCell, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ArrayList<String> savedLinks = ChannelFloatingButton.getChannelUrls(context);
        for (int i = 0; i < linkFields.length; i++) {
            final int index = i;
            EditTextBoldCursor linkField = linkFields[i] = new EditTextBoldCursor(context);
            if (i < savedLinks.size()) {
                linkField.setText(ChannelFloatingButton.getDisplayLink(savedLinks.get(i)));
                linkField.setSelection(linkField.length());
            }
            linkField.setHint(context.getString(R.string.FloatingChannelButtonLinkNumber, i + 1));
            linkField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            linkField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            linkField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            linkField.setSingleLine(true);
            linkField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            linkField.setImeOptions(i == linkFields.length - 1 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
            linkField.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            linkField.setBackgroundDrawable(null);
            linkField.setLineColors(
                    Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                    Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                    Theme.getColor(Theme.key_text_RedRegular)
            );
            linkField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            linkField.setCursorSize(AndroidUtilities.dp(20));
            linkField.setCursorWidth(1.5f);
            linkField.setOnEditorActionListener((textView, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_NEXT && index + 1 < linkFields.length) {
                    linkFields[index + 1].requestFocus();
                    return true;
                } else if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveLinks();
                    return true;
                }
                return false;
            });
            content.addView(linkField, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT,
                    52,
                    24, 0, 24, 0
            ));
        }

        fieldInfoView = new TextView(context);
        fieldInfoView.setText(LocaleController.getString(R.string.FloatingChannelButtonInfo));
        fieldInfoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText8));
        fieldInfoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        fieldInfoView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        content.addView(fieldInfoView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                24, 10, 24, 28
        ));

        okButton = new TextView(context);
        okButton.setText(LocaleController.getString(R.string.OK));
        okButton.setTextColor(Color.WHITE);
        okButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        okButton.setTypeface(AndroidUtilities.bold());
        okButton.setGravity(Gravity.CENTER);
        okButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)
        ));
        okButton.setOnClickListener(view -> saveLinks());
        content.addView(okButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                48,
                24, 0, 24, 24
        ));

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = getContext();
        if (context != null && ChannelFloatingButton.isSystemOverlayEnabled(context)) {
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)) {
                org.telegram.ui.Components.FloatingShortcutOverlayService.start(context);
            } else {
                ChannelFloatingButton.setSystemOverlayEnabled(context, false);
            }
        }
        if (systemOverlayCell != null && context != null) {
            systemOverlayCell.setChecked(ChannelFloatingButton.isSystemOverlayEnabled(context));
        }
    }

    private void saveLinks() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        ArrayList<String> links = new ArrayList<>();
        for (EditTextBoldCursor linkField : linkFields) {
            String value = linkField.getText().toString().trim();
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            String url = normalizeUrl(value);
            if (url == null) {
                linkField.requestFocus();
                linkField.setLineColors(
                        Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                        Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                        Theme.getColor(Theme.key_text_RedRegular)
                );
                Toast.makeText(context, R.string.FloatingChannelButtonInvalidLink, Toast.LENGTH_SHORT).show();
                return;
            }
            links.add(url);
        }
        ChannelFloatingButton.setChannelUrls(context, links);
        updateFeedWidgetFromLinks(context.getApplicationContext(), links, 0);
        AndroidUtilities.hideKeyboard(linkFields[0]);
        Toast.makeText(context, R.string.FloatingChannelButtonSaved, Toast.LENGTH_SHORT).show();
        finishFragment();
    }

    private String normalizeUrl(String value) {
        if (value.matches("[+-]?\\d+")) {
            return ChannelFloatingButton.createChatIdLink(value);
        }
        String url = value.contains("://") ? value : "https://" + value;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.US);
            if ("tg".equals(scheme)) {
                return TextUtils.isEmpty(uri.getSchemeSpecificPart()) ? null : url;
            }
            if (("http".equals(scheme) || "https".equals(scheme)) && !TextUtils.isEmpty(uri.getHost())) {
                return url;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void updateFeedWidgetFromLinks(Context context, ArrayList<String> links, int index) {
        if (index >= links.size()) {
            return;
        }
        String channelUrl = ChannelFeedWidgetUtils.normalizeTelegramChannelUrl(links.get(index));
        if (channelUrl == null) {
            updateFeedWidgetFromLinks(context, links, index + 1);
            return;
        }
        ChannelFeedWidgetUtils.resolveChannel(currentAccount, channelUrl, (channel, error) -> {
            if (channel != null) {
                ChannelFeedWidgetUtils.saveResolvedChannel(context, channel);
                FeedWidgetProvider.rebindAllWidgets(context, channel);
            } else {
                updateFeedWidgetFromLinks(context, links, index + 1);
            }
        });
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        for (EditTextBoldCursor linkField : linkFields) {
            descriptions.add(new ThemeDescription(linkField, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            descriptions.add(new ThemeDescription(linkField, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
            descriptions.add(new ThemeDescription(linkField, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_windowBackgroundWhiteInputField));
            descriptions.add(new ThemeDescription(linkField, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null, null, Theme.key_windowBackgroundWhiteInputFieldActivated));
        }
        descriptions.add(new ThemeDescription(screenInfoView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText8));
        descriptions.add(new ThemeDescription(fieldInfoView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText8));
        descriptions.add(new ThemeDescription(systemOverlayCell, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        return descriptions;
    }
}
