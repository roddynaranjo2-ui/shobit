package com.nexo.gametunnel.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.nexo.gametunnel.core.TunnelController;
import com.nexo.gametunnel.model.InstalledApp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InstalledApps {
    private InstalledApps() {
    }

    public static List<InstalledApp> launchable(final Context context) {
        final PackageManager packageManager = context.getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final Map<String, InstalledApp> unique = new LinkedHashMap<>();
        for (final ResolveInfo info : packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
            final String packageName = info.activityInfo.packageName;
            if (packageName.equals(context.getPackageName())) {
                continue;
            }
            final String label = String.valueOf(info.loadLabel(packageManager));
            unique.putIfAbsent(packageName,
                    new InstalledApp(label, packageName, info.loadIcon(packageManager)));
        }
        final List<InstalledApp> apps = new ArrayList<>(unique.values());
        apps.sort(Comparator
                .comparing((InstalledApp app) -> !app.packageName().equals(TunnelController.ARENA_BREAKOUT_PACKAGE))
                .thenComparing(app -> app.label().toLowerCase(Locale.getDefault())));
        return apps;
    }

    public static String labelFor(final Context context, final String packageName) {
        if (TunnelController.ARENA_BREAKOUT_PACKAGE.equals(packageName)) {
            try {
                return String.valueOf(context.getPackageManager()
                        .getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0)));
            } catch (final PackageManager.NameNotFoundException ignored) {
                return "Arena Breakout Global (no instalado)";
            }
        }
        try {
            return String.valueOf(context.getPackageManager()
                    .getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0)));
        } catch (final PackageManager.NameNotFoundException ignored) {
            return packageName + " (no instalada)";
        }
    }
}
