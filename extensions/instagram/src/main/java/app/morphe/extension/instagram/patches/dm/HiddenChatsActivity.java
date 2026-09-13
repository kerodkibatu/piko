/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.ui.Dim;

/**
 * Lists chats hidden via the "Hide chat" long-press option. Each row offers
 * an Unhide action that restores the chat to the inbox on next refresh.
 */
public class HiddenChatsActivity extends Activity {

    private final List<String> threadIds = new ArrayList<String>();
    private ChatAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);

        reload();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        InstagramPreferenceStyle.applySystemBarStyle(this);

        // Toolbar
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        toolbar.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);

        ImageView back = new ImageView(this);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
        backParams.gravity = Gravity.CENTER_VERTICAL;
        back.setLayoutParams(backParams);
        UI.setThemedIcon(back, "material_ic_keyboard_arrow_left_black_24dp");
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText(str("piko_hidden_chats"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, app.morphe.extension.crimera.PikoUtils.spToPixels(20));
        title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.gravity = Gravity.CENTER_VERTICAL;
        titleParams.leftMargin = Dim.dp8 / 2;
        title.setLayoutParams(titleParams);

        toolbar.addView(back);
        toolbar.addView(title);
        root.addView(toolbar);

        if (threadIds.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(str("piko_no_hidden_chats"));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Dim.dp8 * 2, Dim.dp8 * 4, Dim.dp8 * 2, Dim.dp8 * 4);
            empty.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            root.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        } else {
            ListView listView = new ListView(this);
            adapter = new ChatAdapter();
            listView.setAdapter(adapter);
            listView.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            listView.setDivider(new android.graphics.drawable.ColorDrawable(
                    UI.getThemedColour("igds_color_separator")));
            listView.setDividerHeight(1);
            root.addView(listView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });

        setContentView(root);
    }

    private void reload() {
        threadIds.clear();
        threadIds.addAll(HideChat.getHiddenThreadIds());
    }

    private class ChatAdapter extends BaseAdapter {

        @Override public int getCount() { return threadIds.size(); }
        @Override public Object getItem(int pos) { return threadIds.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView nameView;
            TextView unhideView;

            if (convertView == null) {
                row = new LinearLayout(HiddenChatsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                int pad = Dim.dp8;
                row.setPadding(pad * 2, pad, pad * 2, pad);

                nameView = new TextView(HiddenChatsActivity.this);
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                nameView.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                nameParams.gravity = Gravity.CENTER_VERTICAL;
                nameView.setLayoutParams(nameParams);
                nameView.setTag("n");

                unhideView = new TextView(HiddenChatsActivity.this);
                unhideView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                unhideView.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                unhideView.setPadding(pad * 2, pad, pad * 2, pad);
                unhideView.setTag("u");

                row.addView(nameView);
                row.addView(unhideView);
            } else {
                row = (LinearLayout) convertView;
                nameView = row.findViewWithTag("n");
                unhideView = row.findViewWithTag("u");
            }

            final String threadId = threadIds.get(position);
            String name = HideChat.getStoredName(threadId);
            if (name == null || name.isEmpty()) {
                String tail = threadId.length() > 8 ? threadId.substring(threadId.length() - 8) : threadId;
                name = str("piko_hidden_chat_fallback") + " ••••" + tail;
            }
            nameView.setText(name);

            unhideView.setText(str("piko_unhide"));
            unhideView.setOnClickListener(v ->
                    new android.app.AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(HiddenChatsActivity.this))
                            .setMessage(str("piko_unhide_chat_confirm"))
                            .setPositiveButton(str("piko_unhide"), (d, w) -> {
                                HideChat.setHidden(threadId, false);
                                threadIds.remove(position);
                                notifyDataSetChanged();
                            })
                            .setNegativeButton(str("piko_cancel"), null)
                            .show());

            return row;
        }
    }
}
