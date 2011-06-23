/*
 * Copyright (C) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.sip;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.sip.ISipService;
import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipErrorCode;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.sip.SipSessionAdapter;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import javax.sip.SipException;

/**
 * @hide
 */
public final class SipService extends ISipService.Stub {
    static final String TAG = "SipService";
    static final boolean DEBUGV = false;
    static final boolean DEBUG = true;
    private static final int EXPIRY_TIME = 3600;
    private static final int SHORT_EXPIRY_TIME = 10;
    private static final int MIN_EXPIRY_TIME = 60;
    private static final int DEFAULT_KEEPALIVE_INTERVAL = 10; // in seconds

    private Context mContext;
    private String mLocalIp;
    private String mNetworkType;
    private boolean mConnected;
    private SipWakeupTimer mTimer;
    private WifiScanProcess mWifiScanProcess;
    private WifiManager.WifiLock mWifiLock;
    private boolean mWifiOnly;
    private IntervalMeasurementProcess mIntervalMeasurementProcess;

    private MyExecutor mExecutor = new MyExecutor();

    // SipProfile URI --> group
    private Map<String, SipSessionGroupExt> mSipGroups =
            new HashMap<String, SipSessionGroupExt>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;
    private boolean mWifiEnabled;
    private SipWakeLock mMyWakeLock;
    private int mKeepAliveInterval;

    /**
     * Starts the SIP service. Do nothing if the SIP API is not supported on the
     * device.
     */
    public static void start(Context context) {
        if (SipManager.isApiSupported(context)) {
            ServiceManager.addService("sip", new SipService(context));
            context.sendBroadcast(new Intent(SipManager.ACTION_SIP_SERVICE_UP));
            if (DEBUG) Log.d(TAG, "SIP service started");
        }
    }

    private SipService(Context context) {
        if (DEBUG) Log.d(TAG, " service started!");
        mContext = context;
        mConnectivityReceiver = new ConnectivityReceiver();
        mMyWakeLock = new SipWakeLock((PowerManager)
                context.getSystemService(Context.POWER_SERVICE));

        mTimer = new SipWakeupTimer(context, mExecutor);
        mWifiOnly = SipManager.isSipWifiOnly(context);
    }

    private BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                synchronized (SipService.this) {
                    switch (state) {
                        case WifiManager.WIFI_STATE_ENABLED:
                            mWifiEnabled = true;
                            if (anyOpenedToReceiveCalls()) grabWifiLock();
                            break;
                        case WifiManager.WIFI_STATE_DISABLED:
                            mWifiEnabled = false;
                            releaseWifiLock();
                            break;
                    }
                }
            }
        }
    };

    private void registerReceivers() {
        mContext.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mContext.registerReceiver(mWifiStateReceiver,
                new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        if (DEBUG) Log.d(TAG, " +++ register receivers");
    }

    private void unregisterReceivers() {
        mContext.unregisterReceiver(mConnectivityReceiver);
        mContext.unregisterReceiver(mWifiStateReceiver);
        if (DEBUG) Log.d(TAG, " --- unregister receivers");
    }

    public synchronized SipProfile[] getListOfProfiles() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        boolean isCallerRadio = isCallerRadio();
        ArrayList<SipProfile> profiles = new ArrayList<SipProfile>();
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if (isCallerRadio || isCallerCreator(group)) {
                profiles.add(group.getLocalProfile());
            }
        }
        return profiles.toArray(new SipProfile[profiles.size()]);
    }

    public synchronized void open(SipProfile localProfile) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        try {
            boolean addingFirstProfile = mSipGroups.isEmpty();
            createGroup(localProfile);
            if (addingFirstProfile && !mSipGroups.isEmpty()) registerReceivers();
        } catch (SipException e) {
            Log.e(TAG, "openToMakeCalls()", e);
            // TODO: how to send the exception back
        }
    }

    public synchronized void open3(SipProfile localProfile,
            PendingIntent incomingCallPendingIntent,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        if (incomingCallPendingIntent == null) {
            Log.w(TAG, "incomingCallPendingIntent cannot be null; "
                    + "the profile is not opened");
            return;
        }
        if (DEBUG) Log.d(TAG, "open3: " + localProfile.getUriString() + ": "
                + incomingCallPendingIntent + ": " + listener);
        try {
            boolean addingFirstProfile = mSipGroups.isEmpty();
            SipSessionGroupExt group = createGroup(localProfile,
                    incomingCallPendingIntent, listener);
            if (addingFirstProfile && !mSipGroups.isEmpty()) registerReceivers();
            if (localProfile.getAutoRegistration()) {
                group.openToReceiveCalls();
                if (mWifiEnabled) grabWifiLock();
            }
        } catch (SipException e) {
            Log.e(TAG, "openToReceiveCalls()", e);
            // TODO: how to send the exception back
        }
    }

    private boolean isCallerCreator(SipSessionGroupExt group) {
        SipProfile profile = group.getLocalProfile();
        return (profile.getCallingUid() == Binder.getCallingUid());
    }

    private boolean isCallerCreatorOrRadio(SipSessionGroupExt group) {
        return (isCallerRadio() || isCallerCreator(group));
    }

    private boolean isCallerRadio() {
        return (Binder.getCallingUid() == Process.PHONE_UID);
    }

    public synchronized void close(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return;
        if (!isCallerCreatorOrRadio(group)) {
            Log.w(TAG, "only creator or radio can close this profile");
            return;
        }

        group = mSipGroups.remove(localProfileUri);
        notifyProfileRemoved(group.getLocalProfile());
        group.close();

        if (!anyOpenedToReceiveCalls()) {
            releaseWifiLock();
            mMyWakeLock.reset(); // in case there's leak
        }
        if (mSipGroups.isEmpty()) unregisterReceivers();
    }

    public synchronized boolean isOpened(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return false;
        if (isCallerCreatorOrRadio(group)) {
            return true;
        } else {
            Log.w(TAG, "only creator or radio can query on the profile");
            return false;
        }
    }

    public synchronized boolean isRegistered(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return false;
        if (isCallerCreatorOrRadio(group)) {
            return group.isRegistered();
        } else {
            Log.w(TAG, "only creator or radio can query on the profile");
            return false;
        }
    }

    public synchronized void setRegistrationListener(String localProfileUri,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return;
        if (isCallerCreator(group)) {
            group.setListener(listener);
        } else {
            Log.w(TAG, "only creator can set listener on the profile");
        }
    }

    public synchronized ISipSession createSession(SipProfile localProfile,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        if (!mConnected) return null;
        try {
            SipSessionGroupExt group = createGroup(localProfile);
            return group.createSession(listener);
        } catch (SipException e) {
            if (DEBUG) Log.d(TAG, "createSession()", e);
            return null;
        }
    }

    public synchronized ISipSession getPendingSession(String callId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        if (callId == null) return null;
        return mPendingSessions.get(callId);
    }

    private String determineLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.1.1"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            if (DEBUG) Log.d(TAG, "determineLocalIp()", e);
            // dont do anything; there should be a connectivity change going
            return null;
        }
    }

    private SipSessionGroupExt createGroup(SipProfile localProfile)
            throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group == null) {
            group = new SipSessionGroupExt(localProfile, null, null);
            mSipGroups.put(key, group);
            notifyProfileAdded(localProfile);
        } else if (!isCallerCreator(group)) {
            throw new SipException("only creator can access the profile");
        }
        return group;
    }

    private SipSessionGroupExt createGroup(SipProfile localProfile,
            PendingIntent incomingCallPendingIntent,
            ISipSessionListener listener) throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group != null) {
            if (!isCallerCreator(group)) {
                throw new SipException("only creator can access the profile");
            }
            group.setIncomingCallPendingIntent(incomingCallPendingIntent);
            group.setListener(listener);
        } else {
            group = new SipSessionGroupExt(localProfile,
                    incomingCallPendingIntent, listener);
            mSipGroups.put(key, group);
            notifyProfileAdded(localProfile);
        }
        return group;
    }

    private void notifyProfileAdded(SipProfile localProfile) {
        if (DEBUG) Log.d(TAG, "notify: profile added: " + localProfile);
        Intent intent = new Intent(SipManager.ACTION_SIP_ADD_PHONE);
        intent.putExtra(SipManager.EXTRA_LOCAL_URI, localProfile.getUriString());
        mContext.sendBroadcast(intent);
    }

    private void notifyProfileRemoved(SipProfile localProfile) {
        if (DEBUG) Log.d(TAG, "notify: profile removed: " + localProfile);
        Intent intent = new Intent(SipManager.ACTION_SIP_REMOVE_PHONE);
        intent.putExtra(SipManager.EXTRA_LOCAL_URI, localProfile.getUriString());
        mContext.sendBroadcast(intent);
    }

    private boolean anyOpenedToReceiveCalls() {
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if (group.isOpenedToReceiveCalls()) return true;
        }
        return false;
    }

    private void grabWifiLock() {
        if (mWifiLock == null) {
            if (DEBUG) Log.d(TAG, "acquire wifi lock");
            mWifiLock = ((WifiManager)
                    mContext.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);
            mWifiLock.acquire();
            if (!mConnected) startWifiScanner();
        }
    }

    private void releaseWifiLock() {
        if (mWifiLock != null) {
            if (DEBUG) Log.d(TAG, "release wifi lock");
            mWifiLock.release();
            mWifiLock = null;
            stopWifiScanner();
        }
    }

    private synchronized void startWifiScanner() {
        if (mWifiScanProcess == null) {
            mWifiScanProcess = new WifiScanProcess();
        }
        mWifiScanProcess.start();
    }

    private synchronized void stopWifiScanner() {
        if (mWifiScanProcess != null) {
            mWifiScanProcess.stop();
        }
    }

    private synchronized void onConnectivityChanged(
            String type, boolean connected) {
        if (DEBUG) Log.d(TAG, "onConnectivityChanged(): "
                + mNetworkType + (mConnected? " CONNECTED" : " DISCONNECTED")
                + " --> " + type + (connected? " CONNECTED" : " DISCONNECTED"));

        boolean sameType = type.equals(mNetworkType);
        if (!sameType && !connected) return;

        boolean wasWifi = "WIFI".equalsIgnoreCase(mNetworkType);
        boolean isWifi = "WIFI".equalsIgnoreCase(type);
        boolean wifiOff = (isWifi && !connected) || (wasWifi && !sameType);
        boolean wifiOn = isWifi && connected;

        try {
            boolean wasConnected = mConnected;
            mNetworkType = type;
            mConnected = connected;

            if (wasConnected) {
                mLocalIp = null;
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(false);
                }
            }

            if (connected) {
                mLocalIp = determineLocalIp();
                mKeepAliveInterval = -1;
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(true);
                }
                if (isWifi && (mWifiLock != null)) stopWifiScanner();
            } else {
                mMyWakeLock.reset(); // in case there's a leak
                stopPortMappingMeasurement();
                if (isWifi && (mWifiLock != null)) startWifiScanner();
            }
        } catch (SipException e) {
            Log.e(TAG, "onConnectivityChanged()", e);
        }
    }

    private void stopPortMappingMeasurement() {
        if (mIntervalMeasurementProcess != null) {
            mIntervalMeasurementProcess.stop();
            mIntervalMeasurementProcess = null;
        }
    }

    private void startPortMappingLifetimeMeasurement(
            SipProfile localProfile) {
        if ((mIntervalMeasurementProcess == null)
                && (mKeepAliveInterval == -1)
                && isBehindNAT(mLocalIp)) {
            Log.d(TAG, "start NAT port mapping timeout measurement on "
                    + localProfile.getUriString());

            mIntervalMeasurementProcess = new IntervalMeasurementProcess(localProfile);
            mIntervalMeasurementProcess.start();
        }
    }

    private synchronized void addPendingSession(ISipSession session) {
        try {
            cleanUpPendingSessions();
            mPendingSessions.put(session.getCallId(), session);
            if (DEBUG) Log.d(TAG, "#pending sess=" + mPendingSessions.size());
        } catch (RemoteException e) {
            // should not happen with a local call
            Log.e(TAG, "addPendingSession()", e);
        }
    }

    private void cleanUpPendingSessions() throws RemoteException {
        Map.Entry<String, ISipSession>[] entries =
                mPendingSessions.entrySet().toArray(
                new Map.Entry[mPendingSessions.size()]);
        for (Map.Entry<String, ISipSession> entry : entries) {
            if (entry.getValue().getState() != SipSession.State.INCOMING_CALL) {
                mPendingSessions.remove(entry.getKey());
            }
        }
    }

    private synchronized boolean callingSelf(SipSessionGroupExt ringingGroup,
            SipSessionGroup.SipSessionImpl ringingSession) {
        String callId = ringingSession.getCallId();
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if ((group != ringingGroup) && group.containsSession(callId)) {
                if (DEBUG) Log.d(TAG, "call self: "
                        + ringingSession.getLocalProfile().getUriString()
                        + " -> " + group.getLocalProfile().getUriString());
                return true;
            }
        }
        return false;
    }

    private synchronized void onKeepAliveIntervalChanged() {
        for (SipSessionGroupExt group : mSipGroups.values()) {
            group.onKeepAliveIntervalChanged();
        }
    }

    private int getKeepAliveInterval() {
        return (mKeepAliveInterval < 0)
                ? DEFAULT_KEEPALIVE_INTERVAL
                : mKeepAliveInterval;
    }

    private boolean isBehindNAT(String address) {
        try {
            byte[] d = InetAddress.getByName(address).getAddress();
            if ((d[0] == 10) ||
                    (((0x000000FF & ((int)d[0])) == 172) &&
                    ((0x000000F0 & ((int)d[1])) == 16)) ||
                    (((0x000000FF & ((int)d[0])) == 192) &&
                    ((0x000000FF & ((int)d[1])) == 168))) {
                return true;
            }
        } catch (UnknownHostException e) {
            Log.e(TAG, "isBehindAT()" + address, e);
        }
        return false;
    }

    private class SipSessionGroupExt extends SipSessionAdapter {
        private SipSessionGroup mSipGroup;
        private PendingIntent mIncomingCallPendingIntent;
        private boolean mOpenedToReceiveCalls;

        private AutoRegistrationProcess mAutoRegistration =
                new AutoRegistrationProcess();

        public SipSessionGroupExt(SipProfile localProfile,
                PendingIntent incomingCallPendingIntent,
                ISipSessionListener listener) throws SipException {
            String password = localProfile.getPassword();
            SipProfile p = duplicate(localProfile);
            mSipGroup = createSipSessionGroup(mLocalIp, p, password);
            mIncomingCallPendingIntent = incomingCallPendingIntent;
            mAutoRegistration.setListener(listener);
        }

        public SipProfile getLocalProfile() {
            return mSipGroup.getLocalProfile();
        }

        public boolean containsSession(String callId) {
            return mSipGroup.containsSession(callId);
        }

        public void onKeepAliveIntervalChanged() {
            mAutoRegistration.onKeepAliveIntervalChanged();
        }

        // TODO: remove this method once SipWakeupTimer can better handle variety
        // of timeout values
        void setWakeupTimer(SipWakeupTimer timer) {
            mSipGroup.setWakeupTimer(timer);
        }

        // network connectivity is tricky because network can be disconnected
        // at any instant so need to deal with exceptions carefully even when
        // you think you are connected
        private SipSessionGroup createSipSessionGroup(String localIp,
                SipProfile localProfile, String password) throws SipException {
            try {
                return new SipSessionGroup(localIp, localProfile, password,
                        mTimer, mMyWakeLock);
            } catch (IOException e) {
                // network disconnected
                Log.w(TAG, "createSipSessionGroup(): network disconnected?");
                if (localIp != null) {
                    return createSipSessionGroup(null, localProfile, password);
                } else {
                    // recursive
                    Log.wtf(TAG, "impossible! recursive!");
                    throw new RuntimeException("createSipSessionGroup");
                }
            }
        }

        private SipProfile duplicate(SipProfile p) {
            try {
                return new SipProfile.Builder(p).setPassword("*").build();
            } catch (Exception e) {
                Log.wtf(TAG, "duplicate()", e);
                throw new RuntimeException("duplicate profile", e);
            }
        }

        public void setListener(ISipSessionListener listener) {
            mAutoRegistration.setListener(listener);
        }

        public void setIncomingCallPendingIntent(PendingIntent pIntent) {
            mIncomingCallPendingIntent = pIntent;
        }

        public void openToReceiveCalls() throws SipException {
            mOpenedToReceiveCalls = true;
            if (mConnected) {
                mSipGroup.openToReceiveCalls(this);
                mAutoRegistration.start(mSipGroup);
            }
            if (DEBUG) Log.d(TAG, "  openToReceiveCalls: " + getUri() + ": "
                    + mIncomingCallPendingIntent);
        }

        public void onConnectivityChanged(boolean connected)
                throws SipException {
            mSipGroup.onConnectivityChanged();
            if (connected) {
                resetGroup(mLocalIp);
                if (mOpenedToReceiveCalls) openToReceiveCalls();
            } else {
                // close mSipGroup but remember mOpenedToReceiveCalls
                if (DEBUG) Log.d(TAG, "  close auto reg temporarily: "
                        + getUri() + ": " + mIncomingCallPendingIntent);
                mSipGroup.close();
                mAutoRegistration.stop();
            }
        }

        private void resetGroup(String localIp) throws SipException {
            try {
                mSipGroup.reset(localIp);
            } catch (IOException e) {
                // network disconnected
                Log.w(TAG, "resetGroup(): network disconnected?");
                if (localIp != null) {
                    resetGroup(null); // reset w/o local IP
                } else {
                    // recursive
                    Log.wtf(TAG, "impossible!");
                    throw new RuntimeException("resetGroup");
                }
            }
        }

        public void close() {
            mOpenedToReceiveCalls = false;
            mSipGroup.close();
            mAutoRegistration.stop();
            if (DEBUG) Log.d(TAG, "   close: " + getUri() + ": "
                    + mIncomingCallPendingIntent);
        }

        public ISipSession createSession(ISipSessionListener listener) {
            return mSipGroup.createSession(listener);
        }

        @Override
        public void onRinging(ISipSession s, SipProfile caller,
                String sessionDescription) {
            if (DEBUGV) Log.d(TAG, "<<<<< onRinging()");
            SipSessionGroup.SipSessionImpl session =
                    (SipSessionGroup.SipSessionImpl) s;
            synchronized (SipService.this) {
                try {
                    if (!isRegistered() || callingSelf(this, session)) {
                        session.endCall();
                        return;
                    }

                    // send out incoming call broadcast
                    addPendingSession(session);
                    Intent intent = SipManager.createIncomingCallBroadcast(
                            session.getCallId(), sessionDescription);
                    if (DEBUG) Log.d(TAG, " ringing~~ " + getUri() + ": "
                            + caller.getUri() + ": " + session.getCallId()
                            + " " + mIncomingCallPendingIntent);
                    mIncomingCallPendingIntent.send(mContext,
                            SipManager.INCOMING_CALL_RESULT_CODE, intent);
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "pendingIntent is canceled, drop incoming call");
                    session.endCall();
                }
            }
        }

        @Override
        public void onError(ISipSession session, int errorCode,
                String message) {
            if (DEBUG) Log.d(TAG, "sip session error: "
                    + SipErrorCode.toString(errorCode) + ": " + message);
        }

        public boolean isOpenedToReceiveCalls() {
            return mOpenedToReceiveCalls;
        }

        public boolean isRegistered() {
            return mAutoRegistration.isRegistered();
        }

        private String getUri() {
            return mSipGroup.getLocalProfileUri();
        }
    }

    private class WifiScanProcess implements Runnable {
        private static final String TAG = "\\WIFI_SCAN/";
        private static final int INTERVAL = 60;
        private boolean mRunning = false;

        private WifiManager mWifiManager;

        public void start() {
            if (mRunning) return;
            mRunning = true;
            mTimer.set(INTERVAL * 1000, this);
        }

        WifiScanProcess() {
            mWifiManager = (WifiManager)
                    mContext.getSystemService(Context.WIFI_SERVICE);
        }

        public void run() {
            // scan and associate now
            if (DEBUGV) Log.v(TAG, "just wake up here for wifi scanning...");
            mWifiManager.startScanActive();
        }

        public void stop() {
            mRunning = false;
            mTimer.cancel(this);
        }
    }

    private class IntervalMeasurementProcess implements
            SipSessionGroup.KeepAliveProcessCallback {
        private static final String TAG = "SipKeepAliveInterval";
        private static final int MAX_INTERVAL = 120; // seconds
        private static final int MIN_INTERVAL = SHORT_EXPIRY_TIME;
        private static final int PASS_THRESHOLD = 10;
        private SipSessionGroupExt mGroup;
        private SipSessionGroup.SipSessionImpl mSession;
        private boolean mRunning;
        private int mMinInterval = 10; // in seconds
        private int mMaxInterval = MAX_INTERVAL;
        private int mInterval = MAX_INTERVAL / 2;
        private int mPassCounter = 0;

        public IntervalMeasurementProcess(SipProfile localProfile) {
            try {
                mGroup =  new SipSessionGroupExt(localProfile, null, null);
                // TODO: remove this line once SipWakeupTimer can better handle
                // variety of timeout values
                mGroup.setWakeupTimer(new SipWakeupTimer(mContext, mExecutor));
                mSession = (SipSessionGroup.SipSessionImpl)
                        mGroup.createSession(null);
            } catch (Exception e) {
                Log.w(TAG, "start interval measurement error: " + e);
            }
        }

        public void start() {
            synchronized (SipService.this) {
                try {
                    mSession.startKeepAliveProcess(mInterval, this);
                } catch (SipException e) {
                    Log.e(TAG, "start()", e);
                }
            }
        }

        public void stop() {
            synchronized (SipService.this) {
                mSession.stopKeepAliveProcess();
            }
        }

        private void restart() {
            synchronized (SipService.this) {
                try {
                    mSession.stopKeepAliveProcess();
                    mSession.startKeepAliveProcess(mInterval, this);
                } catch (SipException e) {
                    Log.e(TAG, "restart()", e);
                }
            }
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onResponse(boolean portChanged) {
            synchronized (SipService.this) {
                if (!portChanged) {
                    if (++mPassCounter != PASS_THRESHOLD) return;
                    // update the interval, since the current interval is good to
                    // keep the port mapping.
                    mKeepAliveInterval = mMinInterval = mInterval;
                    if (DEBUG) {
                        Log.d(TAG, "measured good keepalive interval: "
                                + mKeepAliveInterval);
                    }
                    onKeepAliveIntervalChanged();
                } else {
                    // Since the rport is changed, shorten the interval.
                    mMaxInterval = mInterval;
                }
                if ((mMaxInterval - mMinInterval) < MIN_INTERVAL) {
                    // update mKeepAliveInterval and stop measurement.
                    stop();
                    mKeepAliveInterval = mMinInterval;
                    if (DEBUG) {
                        Log.d(TAG, "measured keepalive interval: "
                                + mKeepAliveInterval);
                    }
                } else {
                    // calculate the new interval and continue.
                    mInterval = (mMaxInterval + mMinInterval) / 2;
                    mPassCounter = 0;
                    if (DEBUG) {
                        Log.d(TAG, "current interval: " + mKeepAliveInterval
                                + ", test new interval: " + mInterval);
                    }
                    restart();
                }
            }
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onError(int errorCode, String description) {
            synchronized (SipService.this) {
                Log.w(TAG, "interval measurement error: " + description);
            }
        }
    }

    private class AutoRegistrationProcess extends SipSessionAdapter
            implements Runnable, SipSessionGroup.KeepAliveProcessCallback {
        private String TAG = "SipAudoReg";
        private SipSessionGroup.SipSessionImpl mSession;
        private SipSessionGroup.SipSessionImpl mKeepAliveSession;
        private SipSessionListenerProxy mProxy = new SipSessionListenerProxy();
        private int mBackoff = 1;
        private boolean mRegistered;
        private long mExpiryTime;
        private int mErrorCode;
        private String mErrorMessage;
        private boolean mRunning = false;

        private String getAction() {
            return toString();
        }

        public void start(SipSessionGroup group) {
            if (!mRunning) {
                mRunning = true;
                mBackoff = 1;
                mSession = (SipSessionGroup.SipSessionImpl)
                        group.createSession(this);
                // return right away if no active network connection.
                if (mSession == null) return;

                // start unregistration to clear up old registration at server
                // TODO: when rfc5626 is deployed, use reg-id and sip.instance
                // in registration to avoid adding duplicate entries to server
                mMyWakeLock.acquire(mSession);
                mSession.unregister();
                if (DEBUG) TAG = mSession.getLocalProfile().getUriString();
                if (DEBUG) Log.d(TAG, "start AutoRegistrationProcess");
            }
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onResponse(boolean portChanged) {
            synchronized (SipService.this) {
                // Start keep-alive interval measurement on the first successfully
                // kept-alive SipSessionGroup
                startPortMappingLifetimeMeasurement(mSession.getLocalProfile());

                if (!mRunning || !portChanged) return;

                // The keep alive process is stopped when port is changed;
                // Nullify the session so that the process can be restarted
                // again when the re-registration is done
                mKeepAliveSession = null;

                // Acquire wake lock for the registration process. The
                // lock will be released when registration is complete.
                mMyWakeLock.acquire(mSession);
                mSession.register(EXPIRY_TIME);
            }
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onError(int errorCode, String description) {
            Log.e(TAG, "keepalive error: " + description);
        }

        public void stop() {
            if (!mRunning) return;
            mRunning = false;
            mMyWakeLock.release(mSession);
            if (mSession != null) {
                mSession.setListener(null);
                if (mConnected && mRegistered) mSession.unregister();
            }

            mTimer.cancel(this);
            if (mKeepAliveSession != null) {
                mKeepAliveSession.stopKeepAliveProcess();
                mKeepAliveSession = null;
            }

            mRegistered = false;
            setListener(mProxy.getListener());
        }

        public void onKeepAliveIntervalChanged() {
            if (mKeepAliveSession != null) {
                int newInterval = getKeepAliveInterval();
                if (DEBUGV) {
                    Log.v(TAG, "restart keepalive w interval=" + newInterval);
                }
                mKeepAliveSession.stopKeepAliveProcess();
                try {
                    mKeepAliveSession.startKeepAliveProcess(newInterval, this);
                } catch (SipException e) {
                    Log.e(TAG, "onKeepAliveIntervalChanged()", e);
                }
            }
        }

        public void setListener(ISipSessionListener listener) {
            synchronized (SipService.this) {
                mProxy.setListener(listener);

                try {
                    int state = (mSession == null)
                            ? SipSession.State.READY_TO_CALL
                            : mSession.getState();
                    if ((state == SipSession.State.REGISTERING)
                            || (state == SipSession.State.DEREGISTERING)) {
                        mProxy.onRegistering(mSession);
                    } else if (mRegistered) {
                        int duration = (int)
                                (mExpiryTime - SystemClock.elapsedRealtime());
                        mProxy.onRegistrationDone(mSession, duration);
                    } else if (mErrorCode != SipErrorCode.NO_ERROR) {
                        if (mErrorCode == SipErrorCode.TIME_OUT) {
                            mProxy.onRegistrationTimeout(mSession);
                        } else {
                            mProxy.onRegistrationFailed(mSession, mErrorCode,
                                    mErrorMessage);
                        }
                    } else if (!mConnected) {
                        mProxy.onRegistrationFailed(mSession,
                                SipErrorCode.DATA_CONNECTION_LOST,
                                "no data connection");
                    } else if (!mRunning) {
                        mProxy.onRegistrationFailed(mSession,
                                SipErrorCode.CLIENT_ERROR,
                                "registration not running");
                    } else {
                        mProxy.onRegistrationFailed(mSession,
                                SipErrorCode.IN_PROGRESS,
                                String.valueOf(state));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setListener(): " + t);
                }
            }
        }

        public boolean isRegistered() {
            return mRegistered;
        }

        // timeout handler: re-register
        @Override
        public void run() {
            synchronized (SipService.this) {
                if (!mRunning) return;

                mErrorCode = SipErrorCode.NO_ERROR;
                mErrorMessage = null;
                if (DEBUG) Log.d(TAG, "registering");
                if (mConnected) {
                    mMyWakeLock.acquire(mSession);
                    mSession.register(EXPIRY_TIME);
                }
            }
        }

        private void restart(int duration) {
            if (DEBUG) Log.d(TAG, "Refresh registration " + duration + "s later.");
            mTimer.cancel(this);
            mTimer.set(duration * 1000, this);
        }

        private int backoffDuration() {
            int duration = SHORT_EXPIRY_TIME * mBackoff;
            if (duration > 3600) {
                duration = 3600;
            } else {
                mBackoff *= 2;
            }
            return duration;
        }

        @Override
        public void onRegistering(ISipSession session) {
            if (DEBUG) Log.d(TAG, "onRegistering(): " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mRegistered = false;
                mProxy.onRegistering(session);
            }
        }

        private boolean notCurrentSession(ISipSession session) {
            if (session != mSession) {
                ((SipSessionGroup.SipSessionImpl) session).setListener(null);
                mMyWakeLock.release(session);
                return true;
            }
            return !mRunning;
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            if (DEBUG) Log.d(TAG, "onRegistrationDone(): " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mProxy.onRegistrationDone(session, duration);

                if (duration > 0) {
                    mExpiryTime = SystemClock.elapsedRealtime()
                            + (duration * 1000);

                    if (!mRegistered) {
                        mRegistered = true;
                        // allow some overlap to avoid call drop during renew
                        duration -= MIN_EXPIRY_TIME;
                        if (duration < MIN_EXPIRY_TIME) {
                            duration = MIN_EXPIRY_TIME;
                        }
                        restart(duration);

                        SipProfile localProfile = mSession.getLocalProfile();
                        if ((mKeepAliveSession == null) && (isBehindNAT(mLocalIp)
                                || localProfile.getSendKeepAlive())) {
                            mKeepAliveSession = mSession.duplicate();
                            Log.d(TAG, "start keepalive");
                            try {
                                mKeepAliveSession.startKeepAliveProcess(
                                        getKeepAliveInterval(), this);
                            } catch (SipException e) {
                                Log.e(TAG, "AutoRegistrationProcess", e);
                            }
                        }
                    }
                    mMyWakeLock.release(session);
                } else {
                    mRegistered = false;
                    mExpiryTime = -1L;
                    if (DEBUG) Log.d(TAG, "Refresh registration immediately");
                    run();
                }
            }
        }

        @Override
        public void onRegistrationFailed(ISipSession session, int errorCode,
                String message) {
            if (DEBUG) Log.d(TAG, "onRegistrationFailed(): " + session + ": "
                    + SipErrorCode.toString(errorCode) + ": " + message);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                switch (errorCode) {
                    case SipErrorCode.INVALID_CREDENTIALS:
                    case SipErrorCode.SERVER_UNREACHABLE:
                        if (DEBUG) Log.d(TAG, "   pause auto-registration");
                        stop();
                        break;
                    default:
                        restartLater();
                }

                mErrorCode = errorCode;
                mErrorMessage = message;
                mProxy.onRegistrationFailed(session, errorCode, message);
                mMyWakeLock.release(session);
            }
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            if (DEBUG) Log.d(TAG, "onRegistrationTimeout(): " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mErrorCode = SipErrorCode.TIME_OUT;
                mProxy.onRegistrationTimeout(session);
                restartLater();
                mMyWakeLock.release(session);
            }
        }

        private void restartLater() {
            mRegistered = false;
            restart(backoffDuration());
        }
    }

    private class ConnectivityReceiver extends BroadcastReceiver {
        private Timer mTimer = new Timer();
        private MyTimerTask mTask;

        @Override
        public void onReceive(final Context context, final Intent intent) {
            // Run the handler in MyExecutor to be protected by wake lock
            mExecutor.execute(new Runnable() {
                public void run() {
                    onReceiveInternal(context, intent);
                }
            });
        }

        private void onReceiveInternal(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo netInfo = (NetworkInfo)
                            b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String type = netInfo.getTypeName();
                    NetworkInfo.State state = netInfo.getState();

                    if (mWifiOnly && (netInfo.getType() !=
                            ConnectivityManager.TYPE_WIFI)) {
                        if (DEBUG) {
                            Log.d(TAG, "Wifi only, other connectivity ignored: "
                                    + type);
                        }
                        return;
                    }

                    NetworkInfo activeNetInfo = getActiveNetworkInfo();
                    if (DEBUG) {
                        if (activeNetInfo != null) {
                            Log.d(TAG, "active network: "
                                    + activeNetInfo.getTypeName()
                                    + ((activeNetInfo.getState() == NetworkInfo.State.CONNECTED)
                                            ? " CONNECTED" : " DISCONNECTED"));
                        } else {
                            Log.d(TAG, "active network: null");
                        }
                    }
                    if ((state == NetworkInfo.State.CONNECTED)
                            && (activeNetInfo != null)
                            && (activeNetInfo.getType() != netInfo.getType())) {
                        if (DEBUG) Log.d(TAG, "ignore connect event: " + type
                                + ", active: " + activeNetInfo.getTypeName());
                        return;
                    }

                    if (state == NetworkInfo.State.CONNECTED) {
                        if (DEBUG) Log.d(TAG, "Connectivity alert: CONNECTED " + type);
                        onChanged(type, true);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        if (DEBUG) Log.d(TAG, "Connectivity alert: DISCONNECTED " + type);
                        onChanged(type, false);
                    } else {
                        if (DEBUG) Log.d(TAG, "Connectivity alert not processed: "
                                + state + " " + type);
                    }
                }
            }
        }

        private NetworkInfo getActiveNetworkInfo() {
            ConnectivityManager cm = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.getActiveNetworkInfo();
        }

        private void onChanged(String type, boolean connected) {
            synchronized (SipService.this) {
                // When turning on WIFI, it needs some time for network
                // connectivity to get stabile so we defer good news (because
                // we want to skip the interim ones) but deliver bad news
                // immediately
                if (connected) {
                    if (mTask != null) {
                        mTask.cancel();
                        mMyWakeLock.release(mTask);
                    }
                    mTask = new MyTimerTask(type, connected);
                    mTimer.schedule(mTask, 2 * 1000L);
                    // hold wakup lock so that we can finish changes before the
                    // device goes to sleep
                    mMyWakeLock.acquire(mTask);
                } else {
                    if ((mTask != null) && mTask.mNetworkType.equals(type)) {
                        mTask.cancel();
                        mMyWakeLock.release(mTask);
                    }
                    onConnectivityChanged(type, false);
                }
            }
        }

        private class MyTimerTask extends TimerTask {
            private boolean mConnected;
            private String mNetworkType;

            public MyTimerTask(String type, boolean connected) {
                mNetworkType = type;
                mConnected = connected;
            }

            // timeout handler
            @Override
            public void run() {
                // delegate to mExecutor
                mExecutor.execute(new Runnable() {
                    public void run() {
                        realRun();
                    }
                });
            }

            private void realRun() {
                synchronized (SipService.this) {
                    if (mTask != this) {
                        Log.w(TAG, "  unexpected task: " + mNetworkType
                                + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                        mMyWakeLock.release(this);
                        return;
                    }
                    mTask = null;
                    if (DEBUG) Log.d(TAG, " deliver change for " + mNetworkType
                            + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                    onConnectivityChanged(mNetworkType, mConnected);
                    mMyWakeLock.release(this);
                }
            }
        }
    }

    private static Looper createLooper() {
        HandlerThread thread = new HandlerThread("SipService.Executor");
        thread.start();
        return thread.getLooper();
    }

    // Executes immediate tasks in a single thread.
    // Hold/release wake lock for running tasks
    private class MyExecutor extends Handler implements Executor {
        MyExecutor() {
            super(createLooper());
        }

        @Override
        public void execute(Runnable task) {
            mMyWakeLock.acquire(task);
            Message.obtain(this, 0/* don't care */, task).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof Runnable) {
                executeInternal((Runnable) msg.obj);
            } else {
                Log.w(TAG, "can't handle msg: " + msg);
            }
        }

        private void executeInternal(Runnable task) {
            try {
                task.run();
            } catch (Throwable t) {
                Log.e(TAG, "run task: " + task, t);
            } finally {
                mMyWakeLock.release(task);
            }
        }
    }
}
