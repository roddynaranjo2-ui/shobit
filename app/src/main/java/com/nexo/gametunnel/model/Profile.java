package com.nexo.gametunnel.model;

import java.util.Objects;

/** The encrypted-at-rest settings required to establish a per-application tunnel. */
public final class Profile {
    private final String configText;
    private final String applicationPackage;

    public Profile(final String configText, final String applicationPackage) {
        this.configText = Objects.requireNonNull(configText);
        this.applicationPackage = Objects.requireNonNull(applicationPackage);
    }

    public String configText() {
        return configText;
    }

    public String applicationPackage() {
        return applicationPackage;
    }
}
