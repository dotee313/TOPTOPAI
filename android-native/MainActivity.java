package com.toptopai.app;

import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void load() {
        registerPlugin(ProxyRouterPlugin.class);
        super.load();
        WebView webView = getBridge().getWebView();
        webView.setWebViewClient(new ProxyWebViewClient(getBridge()));
    }
}
