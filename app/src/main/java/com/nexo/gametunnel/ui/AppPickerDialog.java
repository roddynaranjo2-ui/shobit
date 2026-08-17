package com.nexo.gametunnel.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.nexo.gametunnel.R;
import com.nexo.gametunnel.model.InstalledApp;
import com.nexo.gametunnel.util.InstalledApps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Visual app-selection question with launchable apps as tappable answers. */
public final class AppPickerDialog {
    private AppPickerDialog() {
    }

    public static void show(final Context context, final Consumer<InstalledApp> onSelected) {
        final Dialog dialog = new Dialog(context, R.style.NexoDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_picker);

        final List<InstalledApp> apps = InstalledApps.launchable(context);
        final AppAdapter adapter = new AppAdapter(context, apps);
        final ListView list = dialog.findViewById(R.id.app_list);
        final TextView empty = dialog.findViewById(R.id.empty_apps);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            onSelected.accept(adapter.getItem(position));
            dialog.dismiss();
        });

        final EditText search = dialog.findViewById(R.id.app_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        dialog.findViewById(R.id.cancel_picker).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.94f),
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private static final class AppAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final List<InstalledApp> all;
        private final List<InstalledApp> visible;

        private AppAdapter(final Context context, final List<InstalledApp> apps) {
            inflater = LayoutInflater.from(context);
            all = new ArrayList<>(apps);
            visible = new ArrayList<>(apps);
        }

        @Override public int getCount() { return visible.size(); }
        @Override public InstalledApp getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return getItem(position).packageName().hashCode(); }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final View row = convertView == null
                    ? inflater.inflate(R.layout.row_application, parent, false)
                    : convertView;
            final InstalledApp app = getItem(position);
            ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(app.icon());
            final TextView title = row.findViewById(R.id.app_title);
            title.setText(app.label());
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            ((TextView) row.findViewById(R.id.app_package)).setText(app.packageName());
            return row;
        }

        private void filter(final String query) {
            final String needle = query.trim().toLowerCase(Locale.getDefault());
            visible.clear();
            if (needle.isEmpty()) {
                visible.addAll(all);
            } else {
                for (final InstalledApp app : all) {
                    if (app.label().toLowerCase(Locale.getDefault()).contains(needle)
                            || app.packageName().toLowerCase(Locale.ROOT).contains(needle)) {
                        visible.add(app);
                    }
                }
            }
            notifyDataSetChanged();
        }
    }
}
