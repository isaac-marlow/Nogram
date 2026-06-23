package org.telegram.ui.Components;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;

public class UsageWarningView extends FrameLayout {

    public UsageWarningView(Context context) {
        super(context);

        TextView textView = new TextView(context);
        textView.setText("You've been using Nogram for 5 minutes. Consider taking a break.");
        addView(textView);
    }
}