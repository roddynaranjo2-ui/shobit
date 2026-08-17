package com.nexo.gametunnel.model;

import android.graphics.drawable.Drawable;

public final class InstalledApp {
    private final String label;
    private final String packageName;
    private final Drawable icon;

    public InstalledApp(final String label, final String packageName, final Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String packageName() {
        return packageName;
    }

    public Drawable icon() {
        return icon;
    }
}
