/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.Xml;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;

/*
 * Wraps the C++ InputManager and provides its callbacks.
 */
public class InputManager {
    static final String TAG = "InputManager";
    
    private static final boolean DEBUG = false;

    private final Callbacks mCallbacks;
    private final Context mContext;
    private final WindowManagerService mWindowManagerService;
    
    private static native void nativeInit(Callbacks callbacks);
    private static native void nativeStart();
    private static native void nativeSetDisplaySize(int displayId, int width, int height);
    private static native void nativeSetDisplayOrientation(int displayId, int rotation);
    
    private static native int nativeGetScanCodeState(int deviceId, int sourceMask,
            int scanCode);
    private static native int nativeGetKeyCodeState(int deviceId, int sourceMask,
            int keyCode);
    private static native int nativeGetSwitchState(int deviceId, int sourceMask,
            int sw);
    private static native boolean nativeHasKeys(int deviceId, int sourceMask,
            int[] keyCodes, boolean[] keyExists);
    private static native void nativeRegisterInputChannel(InputChannel inputChannel,
            InputWindowHandle inputWindowHandle, boolean monitor);
    private static native void nativeUnregisterInputChannel(InputChannel inputChannel);
    private static native int nativeInjectInputEvent(InputEvent event,
            int injectorPid, int injectorUid, int syncMode, int timeoutMillis);
    private static native void nativeSetInputWindows(InputWindow[] windows);
    private static native void nativeSetInputDispatchMode(boolean enabled, boolean frozen);
    private static native void nativeSetFocusedApplication(InputApplication application);
    private static native InputDevice nativeGetInputDevice(int deviceId);
    private static native void nativeGetInputConfiguration(Configuration configuration);
    private static native int[] nativeGetInputDeviceIds();
    private static native boolean nativeTransferTouchFocus(InputChannel fromChannel,
            InputChannel toChannel);
    private static native String nativeDump();
    
    // Input event injection constants defined in InputDispatcher.h.
    static final int INPUT_EVENT_INJECTION_SUCCEEDED = 0;
    static final int INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1;
    static final int INPUT_EVENT_INJECTION_FAILED = 2;
    static final int INPUT_EVENT_INJECTION_TIMED_OUT = 3;
    
    // Input event injection synchronization modes defined in InputDispatcher.h
    static final int INPUT_EVENT_INJECTION_SYNC_NONE = 0;
    static final int INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT = 1;
    static final int INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH = 2;
    
    // Key states (may be returned by queries about the current state of a
    // particular key code, scan code or switch).
    
    /** The key state is unknown or the requested key itself is not supported. */
    public static final int KEY_STATE_UNKNOWN = -1;

    /** The key is up. /*/
    public static final int KEY_STATE_UP = 0;

    /** The key is down. */
    public static final int KEY_STATE_DOWN = 1;

    /** The key is down but is a virtual key press that is being emulated by the system. */
    public static final int KEY_STATE_VIRTUAL = 2;

    public InputManager(Context context, WindowManagerService windowManagerService) {
        this.mContext = context;
        this.mWindowManagerService = windowManagerService;
        
        this.mCallbacks = new Callbacks();
        
        init();
    }
    
    private void init() {
        Slog.i(TAG, "Initializing input manager");
        nativeInit(mCallbacks);
    }
    
    public void start() {
        Slog.i(TAG, "Starting input manager");
        nativeStart();
    }
    
    public void setDisplaySize(int displayId, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid display id or dimensions.");
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Setting display #" + displayId + " size to " + width + "x" + height);
        }
        nativeSetDisplaySize(displayId, width, height);
    }
    
    public void setDisplayOrientation(int displayId, int rotation) {
        if (rotation < Surface.ROTATION_0 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Invalid rotation.");
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Setting display #" + displayId + " orientation to " + rotation);
        }
        nativeSetDisplayOrientation(displayId, rotation);
    }
    
    public void getInputConfiguration(Configuration config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null.");
        }
        
        nativeGetInputConfiguration(config);
    }
    
    /**
     * Gets the current state of a key or button by key code.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param keyCode The key code to check.
     * @return The key state.
     */
    public int getKeyCodeState(int deviceId, int sourceMask, int keyCode) {
        return nativeGetKeyCodeState(deviceId, sourceMask, keyCode);
    }
    
    /**
     * Gets the current state of a key or button by scan code.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param scanCode The scan code to check.
     * @return The key state.
     */
    public int getScanCodeState(int deviceId, int sourceMask, int scanCode) {
        return nativeGetScanCodeState(deviceId, sourceMask, scanCode);
    }
    
    /**
     * Gets the current state of a switch by switch code.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param switchCode The switch code to check.
     * @return The switch state.
     */
    public int getSwitchState(int deviceId, int sourceMask, int switchCode) {
        return nativeGetSwitchState(deviceId, sourceMask, switchCode);
    }

    /**
     * Determines whether the specified key codes are supported by a particular device.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param keyCodes The array of key codes to check.
     * @param keyExists An array at least as large as keyCodes whose entries will be set
     * to true or false based on the presence or absence of support for the corresponding
     * key codes.
     * @return True if the lookup was successful, false otherwise.
     */
    public boolean hasKeys(int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists) {
        if (keyCodes == null) {
            throw new IllegalArgumentException("keyCodes must not be null.");
        }
        if (keyExists == null || keyExists.length < keyCodes.length) {
            throw new IllegalArgumentException("keyExists must not be null and must be at "
                    + "least as large as keyCodes.");
        }
        
        return nativeHasKeys(deviceId, sourceMask, keyCodes, keyExists);
    }
    
    /**
     * Creates an input channel that will receive all input from the input dispatcher.
     * @param inputChannelName The input channel name.
     * @return The input channel.
     */
    public InputChannel monitorInput(String inputChannelName) {
        if (inputChannelName == null) {
            throw new IllegalArgumentException("inputChannelName must not be null.");
        }
        
        InputChannel[] inputChannels = InputChannel.openInputChannelPair(inputChannelName);
        nativeRegisterInputChannel(inputChannels[0], null, true);
        inputChannels[0].dispose(); // don't need to retain the Java object reference
        return inputChannels[1];
    }

    /**
     * Registers an input channel so that it can be used as an input event target.
     * @param inputChannel The input channel to register.
     * @param inputWindowHandle The handle of the input window associated with the
     * input channel, or null if none.
     */
    public void registerInputChannel(InputChannel inputChannel,
            InputWindowHandle inputWindowHandle) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }
        
        nativeRegisterInputChannel(inputChannel, inputWindowHandle, false);
    }
    
    /**
     * Unregisters an input channel.
     * @param inputChannel The input channel to unregister.
     */
    public void unregisterInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }
        
        nativeUnregisterInputChannel(inputChannel);
    }
    
    /**
     * Injects an input event into the event system on behalf of an application.
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * 
     * {@link #INPUT_EVENT_INJECTION_SYNC_NONE} never blocks.  Injection is asynchronous and
     * is assumed always to be successful.
     * 
     * {@link #INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT} waits for previous events to be
     * dispatched so that the input dispatcher can determine whether input event injection will
     * be permitted based on the current input focus.  Does not wait for the input event to
     * finish processing.
     * 
     * {@link #INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH} waits for the input event to
     * be completely processed.
     * 
     * @param event The event to inject.
     * @param injectorPid The pid of the injecting application.
     * @param injectorUid The uid of the injecting application.
     * @param syncMode The synchronization mode.
     * @param timeoutMillis The injection timeout in milliseconds.
     * @return One of the INPUT_EVENT_INJECTION_XXX constants.
     */
    public int injectInputEvent(InputEvent event, int injectorPid, int injectorUid,
            int syncMode, int timeoutMillis) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (injectorPid < 0 || injectorUid < 0) {
            throw new IllegalArgumentException("injectorPid and injectorUid must not be negative.");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }

        return nativeInjectInputEvent(event, injectorPid, injectorUid, syncMode, timeoutMillis);
    }
    
    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public InputDevice getInputDevice(int deviceId) {
        return nativeGetInputDevice(deviceId);
    }
    
    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public int[] getInputDeviceIds() {
        return nativeGetInputDeviceIds();
    }
    
    public void setInputWindows(InputWindow[] windows) {
        nativeSetInputWindows(windows);
    }
    
    public void setFocusedApplication(InputApplication application) {
        nativeSetFocusedApplication(application);
    }
    
    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        nativeSetInputDispatchMode(enabled, frozen);
    }
    
    /**
     * Atomically transfers touch focus from one window to another as identified by
     * their input channels.  It is possible for multiple windows to have
     * touch focus if they support split touch dispatch
     * {@link android.view.WindowManager.LayoutParams#FLAG_SPLIT_TOUCH} but this
     * method only transfers touch focus of the specified window without affecting
     * other windows that may also have touch focus at the same time.
     * @param fromChannel The channel of a window that currently has touch focus.
     * @param toChannel The channel of the window that should receive touch focus in
     * place of the first.
     * @return True if the transfer was successful.  False if the window with the
     * specified channel did not actually have touch focus at the time of the request.
     */
    public boolean transferTouchFocus(InputChannel fromChannel, InputChannel toChannel) {
        if (fromChannel == null) {
            throw new IllegalArgumentException("fromChannel must not be null.");
        }
        if (toChannel == null) {
            throw new IllegalArgumentException("toChannel must not be null.");
        }
        return nativeTransferTouchFocus(fromChannel, toChannel);
    }
    
    public void dump(PrintWriter pw) {
        String dumpStr = nativeDump();
        if (dumpStr != null) {
            pw.println(dumpStr);
        }
    }

    private static final class PointerIcon {
        public Bitmap bitmap;
        public float hotSpotX;
        public float hotSpotY;

        public static PointerIcon load(Resources resources, int resourceId) {
            PointerIcon icon = new PointerIcon();

            XmlResourceParser parser = resources.getXml(resourceId);
            final int bitmapRes;
            try {
                XmlUtils.beginDocument(parser, "pointer-icon");

                TypedArray a = resources.obtainAttributes(
                        parser, com.android.internal.R.styleable.PointerIcon);
                bitmapRes = a.getResourceId(com.android.internal.R.styleable.PointerIcon_bitmap, 0);
                icon.hotSpotX = a.getFloat(com.android.internal.R.styleable.PointerIcon_hotSpotX, 0);
                icon.hotSpotY = a.getFloat(com.android.internal.R.styleable.PointerIcon_hotSpotY, 0);
                a.recycle();
            } catch (Exception ex) {
                Slog.e(TAG, "Exception parsing pointer icon resource.", ex);
                return null;
            } finally {
                parser.close();
            }

            if (bitmapRes == 0) {
                Slog.e(TAG, "<pointer-icon> is missing bitmap attribute");
                return null;
            }

            Drawable drawable = resources.getDrawable(bitmapRes);
            if (!(drawable instanceof BitmapDrawable)) {
                Slog.e(TAG, "<pointer-icon> bitmap attribute must refer to a bitmap drawable");
                return null;
            }

            icon.bitmap = ((BitmapDrawable)drawable).getBitmap();
            return icon;
        }
    }

    /*
     * Callbacks from native.
     */
    private class Callbacks {
        static final String TAG = "InputManager-Callbacks";
        
        private static final boolean DEBUG_VIRTUAL_KEYS = false;
        private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";
        private static final String CALIBRATION_DIR_PATH = "usr/idc/";
        
        @SuppressWarnings("unused")
        public void notifyConfigurationChanged(long whenNanos) {
            mWindowManagerService.mInputMonitor.notifyConfigurationChanged();
        }
        
        @SuppressWarnings("unused")
        public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
            mWindowManagerService.mInputMonitor.notifyLidSwitchChanged(whenNanos, lidOpen);
        }
        
        @SuppressWarnings("unused")
        public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
            mWindowManagerService.mInputMonitor.notifyInputChannelBroken(inputWindowHandle);
        }
        
        @SuppressWarnings("unused")
        public long notifyANR(InputApplicationHandle inputApplicationHandle,
                InputWindowHandle inputWindowHandle) {
            return mWindowManagerService.mInputMonitor.notifyANR(
                    inputApplicationHandle, inputWindowHandle);
        }
        
        @SuppressWarnings("unused")
        public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags, boolean isScreenOn) {
            return mWindowManagerService.mInputMonitor.interceptKeyBeforeQueueing(
                    event, policyFlags, isScreenOn);
        }
        
        @SuppressWarnings("unused")
        public boolean interceptKeyBeforeDispatching(InputWindowHandle focus,
                KeyEvent event, int policyFlags) {
            return mWindowManagerService.mInputMonitor.interceptKeyBeforeDispatching(
                    focus, event, policyFlags);
        }
        
        @SuppressWarnings("unused")
        public KeyEvent dispatchUnhandledKey(InputWindowHandle focus,
                KeyEvent event, int policyFlags) {
            return mWindowManagerService.mInputMonitor.dispatchUnhandledKey(
                    focus, event, policyFlags);
        }
        
        @SuppressWarnings("unused")
        public boolean checkInjectEventsPermission(int injectorPid, int injectorUid) {
            return mContext.checkPermission(
                    android.Manifest.permission.INJECT_EVENTS, injectorPid, injectorUid)
                    == PackageManager.PERMISSION_GRANTED;
        }
        
        @SuppressWarnings("unused")
        public boolean filterTouchEvents() {
            return mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_filterTouchEvents);
        }
        
        @SuppressWarnings("unused")
        public boolean filterJumpyTouchEvents() {
            return mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_filterJumpyTouchEvents);
        }

        @SuppressWarnings("unused")
        public int getVirtualKeyQuietTimeMillis() {
            return mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_virtualKeyQuietTimeMillis);
        }

        @SuppressWarnings("unused")
        public String[] getExcludedDeviceNames() {
            ArrayList<String> names = new ArrayList<String>();
            
            // Read partner-provided list of excluded input devices
            XmlPullParser parser = null;
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), EXCLUDED_DEVICES_PATH);
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                parser = Xml.newPullParser();
                parser.setInput(confreader);
                XmlUtils.beginDocument(parser, "devices");

                while (true) {
                    XmlUtils.nextElement(parser);
                    if (!"device".equals(parser.getName())) {
                        break;
                    }
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        names.add(name);
                    }
                }
            } catch (FileNotFoundException e) {
                // It's ok if the file does not exist.
            } catch (Exception e) {
                Slog.e(TAG, "Exception while parsing '" + confFile.getAbsolutePath() + "'", e);
            } finally {
                try { if (confreader != null) confreader.close(); } catch (IOException e) { }
            }
            
            return names.toArray(new String[names.size()]);
        }
        
        @SuppressWarnings("unused")
        public int getMaxEventsPerSecond() {
            int result = 0;
            try {
                result = Integer.parseInt(SystemProperties.get("windowsmgr.max_events_per_sec"));
            } catch (NumberFormatException e) {
            }
            if (result < 1) {
                result = 60;
            }
            return result;
        }

        @SuppressWarnings("unused")
        public int getPointerLayer() {
            return mWindowManagerService.mPolicy.windowTypeToLayerLw(
                    WindowManager.LayoutParams.TYPE_POINTER)
                    * WindowManagerService.TYPE_LAYER_MULTIPLIER
                    + WindowManagerService.TYPE_LAYER_OFFSET;
        }

        @SuppressWarnings("unused")
        public PointerIcon getPointerIcon() {
            return PointerIcon.load(mContext.getResources(),
                    com.android.internal.R.drawable.pointer_arrow_icon);
        }
    }
}
