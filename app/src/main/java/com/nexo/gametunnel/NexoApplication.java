package com.nexo.gametunnel;

import android.app.Application;

import com.nexo.gametunnel.core.TunnelController;

public final class NexoApplication extends Application {
    private TunnelController tunnelController;

    @Override
    public void onCreate() {
        super.onCreate();
        tunnelController = new TunnelController(this);
    }

    public TunnelController tunnelController() {
        return tunnelController;
    }
}
