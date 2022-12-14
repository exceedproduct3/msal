//  Copyright (c) Microsoft Corporation.
//  All rights reserved.
//
//  This code is licensed under the MIT License.
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files(the "Software"), to deal
//  in the Software without restriction, including without limitation the rights
//  to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
//  copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions :
//
//  The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//  THE SOFTWARE.

package com.microsoft.identity.client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.customtabs.CustomTabsClient;
import android.support.customtabs.CustomTabsIntent;
import android.support.customtabs.CustomTabsServiceConnection;
import android.support.customtabs.CustomTabsSession;

import com.microsoft.identity.client.exception.MsalClientException;
import com.microsoft.identity.client.internal.MsalUtils;
import com.microsoft.identity.client.internal.telemetry.UiEvent;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Custom tab requires the device to have a browser with custom tab support, chrome with version >= 45 comes with the
 * support and is available on all devices with API version >= 16 . The sdk use chrome custom tab, and before launching
 * chrome custom tab, we need to check if chrome package is in the device. If it is, it's safe to launch the chrome
 * custom tab; Otherwise the sdk will launch chrome.
 * AuthenticationActivity will be responsible for checking if it's safe to launch chrome custom tab, if not, will
 * go with chrome browser, if chrome is not installed, we throw error back.
 */
public final class AuthenticationActivity extends Activity {

    private static final String TAG = AuthenticationActivity.class.getSimpleName(); //NOPMD
    private static final long CUSTOMTABS_MAX_CONNECTION_TIMEOUT = 1L;

    private String mRequestUrl;
    private int mRequestId;
    private boolean mRestarted;
    private String mChromePackageWithCustomTabSupport;
    private CustomTabsIntent mCustomTabsIntent;
    private MsalCustomTabsServiceConnection mCustomTabsServiceConnection;
    private UiEvent.Builder mUiEventBuilder;
    private String mTelemetryRequestId;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mChromePackageWithCustomTabSupport = MsalUtils.getChromePackageWithCustomTabSupport(getApplicationContext());


        // If activity is killed by the os, savedInstance will be the saved bundle.
        if (savedInstanceState != null) {
            Logger.verbose(TAG, null, "AuthenticationActivity is re-created after killed by the os.");
            mRestarted = true;
            mTelemetryRequestId = savedInstanceState.getString(Constants.TELEMETRY_REQUEST_ID);
            mUiEventBuilder = new UiEvent.Builder();
            return;
        }

        final Intent data = getIntent();
        if (data == null) {
            sendError(MsalClientException.UNRESOLVABLE_INTENT, "Received null data intent from caller");
            return;
        }

        mRequestUrl = data.getStringExtra(Constants.REQUEST_URL_KEY);
        mRequestId = data.getIntExtra(Constants.REQUEST_ID, 0);
        if (MsalUtils.isEmpty(mRequestUrl)) {
            sendError(MsalClientException.UNRESOLVABLE_INTENT, "Request url is not set on the intent");
            return;
        }

        // We'll use custom tab if the chrome installed on the device comes with custom tab support(on 45 and above it
        // does). If the chrome package doesn't contain the support, we'll use chrome to launch the UI.
        if (MsalUtils.getChromePackage(this.getApplicationContext()) == null) {
            Logger.info(TAG, null, "Chrome is not installed on the device, cannot continue with auth.");
            sendError(MsalClientException.CHROME_NOT_INSTALLED, "Chrome is not installed on the device, cannot proceed with auth");
            return;
        }

        mTelemetryRequestId = data.getStringExtra(Constants.TELEMETRY_REQUEST_ID);
        mUiEventBuilder = new UiEvent.Builder();
        Telemetry.getInstance().startEvent(mTelemetryRequestId, mUiEventBuilder);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mChromePackageWithCustomTabSupport != null) {
            warmUpCustomTabs();

        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (null != mCustomTabsServiceConnection && mCustomTabsServiceConnection.getCustomTabsServiceIsBound()) {
            unbindService(mCustomTabsServiceConnection);
        }
    }

    private void warmUpCustomTabs() {
        final CountDownLatch latch = new CountDownLatch(1);
        mCustomTabsServiceConnection = new MsalCustomTabsServiceConnection(latch);

        // Initiate the service-bind action
        CustomTabsClient.bindCustomTabsService(
                this,
                mChromePackageWithCustomTabSupport,
                mCustomTabsServiceConnection
        );

        boolean initCustomTabsWithSession = true;
        try {
            // await returns true if count is 0, false if action times out
            // invert this boolean to indicate if we should skip warming up
            boolean timedOut = !latch.await(CUSTOMTABS_MAX_CONNECTION_TIMEOUT, TimeUnit.SECONDS);
            if (timedOut) {
                // if the request timed out, we don't actually know whether or not the service connected.
                // to be safe, we'll skip warmup and rely on mCustomTabsServiceIsBound
                // to unbind the Service when onStop() is called.
                initCustomTabsWithSession = false;
                Logger.warning(TAG, null, "Connection to CustomTabs timed out. Skipping warmup.");
            }
        } catch (InterruptedException e) {
            Logger.error(TAG, null, "Failed to connect to CustomTabs. Skipping warmup.", e);
            initCustomTabsWithSession = false;
        }

        final CustomTabsIntent.Builder builder = initCustomTabsWithSession
                ? new CustomTabsIntent.Builder(mCustomTabsServiceConnection.getCustomTabsSession()) : new CustomTabsIntent.Builder();

        // Create the Intent used to launch the Url
        mCustomTabsIntent = builder.setShowTitle(true).build();
        mCustomTabsIntent.intent.setPackage(mChromePackageWithCustomTabSupport);
    }

    private static class MsalCustomTabsServiceConnection extends CustomTabsServiceConnection {

        private static final String TAG = MsalCustomTabsServiceConnection.class.getSimpleName();

        private final WeakReference<CountDownLatch> mLatchWeakReference;
        private CustomTabsClient mCustomTabsClient;
        private CustomTabsSession mCustomTabsSession;
        private boolean mCustomTabsServiceIsBound;

        MsalCustomTabsServiceConnection(final CountDownLatch latch) {
            mLatchWeakReference = new WeakReference<>(latch);
        }

        @Override
        public void onCustomTabsServiceConnected(ComponentName name, CustomTabsClient client) {
            final String methodName = ":onCustomTabsServiceConnected";
            com.microsoft.identity.common.internal.logging.Logger.info(
                    TAG + methodName,
                    "Connected."
            );

            final CountDownLatch latch = mLatchWeakReference.get();

            mCustomTabsServiceIsBound = true;
            mCustomTabsClient = client;
            mCustomTabsClient.warmup(0L);
            mCustomTabsSession = mCustomTabsClient.newSession(null);

            if (null != latch) {
                latch.countDown();
                com.microsoft.identity.common.internal.logging.Logger.verbose(
                        TAG + methodName,
                        "Decrementing latch"
                );
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            final String methodName = ":onServiceDisconnected";
            mCustomTabsServiceIsBound = false;
            com.microsoft.identity.common.internal.logging.Logger.info(
                    TAG + methodName,
                    "Disconnected."
            );
        }

        /**
         * Gets the {@link CustomTabsSession} associated to this CustomTabs connection.
         *
         * @return the session.
         */
        CustomTabsSession getCustomTabsSession() {
            return mCustomTabsSession;
        }

        boolean getCustomTabsServiceIsBound() {
            return mCustomTabsServiceIsBound;
        }
    }

    /**
     * OnNewIntent will be called before onResume.
     *
     * @param intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Logger.info(TAG, null, "onNewIntent is called, received redirect from system webview.");
        final String url = intent.getStringExtra(Constants.CUSTOM_TAB_REDIRECT);

        final Intent resultIntent = new Intent();
        resultIntent.putExtra(Constants.AUTHORIZATION_FINAL_URL, url);
        returnToCaller(Constants.UIResponse.AUTH_CODE_COMPLETE,
                resultIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mRestarted) {
            cancelRequest();
            return;
        }

        mRestarted = true;

        mRequestUrl = this.getIntent().getStringExtra(Constants.REQUEST_URL_KEY);

        Logger.infoPII(TAG, null, "Request to launch is: " + mRequestUrl);
        if (mChromePackageWithCustomTabSupport != null) {
            Logger.info(TAG, null, "ChromeCustomTab support is available, launching chrome tab.");
            mCustomTabsIntent.launchUrl(this, Uri.parse(mRequestUrl));
        } else {
            Logger.info(TAG, null, "Chrome tab support is not available, launching chrome browser.");
            final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mRequestUrl));
            browserIntent.setPackage(MsalUtils.getChromePackage(this.getApplicationContext()));
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            this.startActivity(browserIntent);
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(Constants.REQUEST_URL_KEY, mRequestUrl);
        outState.putString(Constants.TELEMETRY_REQUEST_ID, mTelemetryRequestId);
    }

    /**
     * Cancels the auth request.
     */
    void cancelRequest() {
        Logger.verbose(TAG, null, "Cancel the authentication request.");
        mUiEventBuilder.setUserDidCancel();
        returnToCaller(Constants.UIResponse.CANCEL, new Intent());
    }

    /**
     * Return the error back to caller.
     *
     * @param resultCode The result code to return back.
     * @param data       {@link Intent} contains the detailed result.
     */
    private void returnToCaller(final int resultCode, final Intent data) {
        Logger.info(TAG, null, "Return to caller with resultCode: " + resultCode + "; requestId: " + mRequestId);
        data.putExtra(Constants.REQUEST_ID, mRequestId);

        if (null != mUiEventBuilder) {
            Telemetry.getInstance().stopEvent(mTelemetryRequestId, mUiEventBuilder);
        }

        setResult(resultCode, data);
        this.finish();
    }

    /**
     * Send error back to caller with the error description.
     *
     * @param errorCode        The error code to send back.
     * @param errorDescription The error description to send back.
     */
    private void sendError(final String errorCode, final String errorDescription) {
        Logger.info(TAG, null, "Sending error back to the caller, errorCode: " + errorCode + "; errorDescription"
                + errorDescription);
        final Intent errorIntent = new Intent();
        errorIntent.putExtra(Constants.UIResponse.ERROR_CODE, errorCode);
        errorIntent.putExtra(Constants.UIResponse.ERROR_DESCRIPTION, errorDescription);
        returnToCaller(Constants.UIResponse.AUTH_CODE_ERROR, errorIntent);
    }

}