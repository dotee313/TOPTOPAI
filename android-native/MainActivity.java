package com.toptopai.app;

import android.content.Intent;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ShareReceivePlugin.onIntent(this, intent);
    }

    @Override
    protected void load() {
        registerPlugin(ProxyRouterPlugin.class);
        registerPlugin(VisionPlugin.class);
        registerPlugin(InAppBrowserPlugin.class);
        registerPlugin(ShareReceivePlugin.class);
        super.load();
        WebView webView = getBridge().getWebView();
        webView.setWebViewClient(new ProxyWebViewClient(getBridge()));
    }
}
