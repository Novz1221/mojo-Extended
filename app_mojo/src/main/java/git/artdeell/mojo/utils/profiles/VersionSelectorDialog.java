package git.artdeell.mojo.utils.profiles;

import static git.artdeell.mojo.extra.ExtraCore.getValue;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.ViewGroup;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.TextView;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.R;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.utils.M3Dialog;

public class VersionSelectorDialog {
    public static void open(Context context, boolean hideCustomVersions, VersionSelectorListener listener) {
        JVersionList jVersionList = (JVersionList) getValue(ExtraConstants.RELEASE_TABLE);
        JVersionList.Version[] versionArray;
        if(jVersionList == null || jVersionList.versions == null) versionArray = new JVersionList.Version[0];
        else versionArray = jVersionList.versions;
        VersionListAdapter adapter = new VersionListAdapter(versionArray, hideCustomVersions, context);

        LinearLayout card = M3Dialog.card(context);
        TextView title = M3Dialog.label(context, context.getString(R.string.m3_select_version), 19, R.color.m3_text);
        title.setPadding(0, 0, 0, M3Dialog.dp(context, 10));
        card.addView(title);

        ExpandableListView listView = new ExpandableListView(context);
        listView.setGroupIndicator(null);
        listView.setDivider(null);
        listView.setChildDivider(null);
        listView.setDividerHeight(M3Dialog.dp(context, 4));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        int height = Math.min(M3Dialog.dp(context, 280),
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.6f));
        card.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        Dialog dialog = M3Dialog.show(context, card, 0.6f);

        listView.setOnChildClickListener((parent, v1, groupPosition, childPosition, id) -> {
            String version = adapter.getChild(groupPosition, childPosition);
            listener.onVersionSelected(version, adapter.isSnapshotSelected(groupPosition));
            dialog.dismiss();
            return true;
        });
    }
}
