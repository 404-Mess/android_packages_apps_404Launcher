/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.quickstep;

import static android.content.Intent.ACTION_USER_UNLOCKED;

import static com.android.launcher3.ResourceUtils.NAVBAR_BOTTOM_GESTURE_SIZE;
import static com.android.launcher3.ResourceUtils.NAVBAR_LANDSCAPE_LEFT_RIGHT_SIZE;
import static com.android.quickstep.SysUINavigationMode.Mode.NO_BUTTON;
import static com.android.quickstep.SysUINavigationMode.Mode.THREE_BUTTONS;
import static com.android.quickstep.SysUINavigationMode.Mode.TWO_BUTTONS;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Process;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.annotation.BinderThread;

import com.android.launcher3.R;
import com.android.launcher3.ResourceUtils;
import com.android.launcher3.Utilities;
import com.android.launcher3.testing.TestProtocol;
import com.android.launcher3.util.DefaultDisplay;
import com.android.quickstep.SysUINavigationMode.NavigationModeChangeListener;
import com.android.quickstep.util.NavBarPosition;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;
import com.android.systemui.shared.system.SystemGestureExclusionListenerCompat;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the state of the system during a swipe up gesture.
 */
public class RecentsAnimationDeviceState implements
        NavigationModeChangeListener,
        DefaultDisplay.DisplayInfoChangeListener {

    private final Context mContext;
    private final SysUINavigationMode mSysUiNavMode;
    private final DefaultDisplay mDefaultDisplay;
    private final int mDisplayId;

    private final ArrayList<Runnable> mOnDestroyActions = new ArrayList<>();

    private @SystemUiStateFlags int mSystemUiStateFlags;
    private SysUINavigationMode.Mode mMode = THREE_BUTTONS;
    private NavBarPosition mNavBarPosition;

    private final RectF mSwipeUpTouchRegion = new RectF();
    private final Region mDeferredGestureRegion = new Region();
    private final RectF mAssistantLeftRegion = new RectF();
    private final RectF mAssistantRightRegion = new RectF();
    private boolean mAssistantAvailable;
    private float mAssistantVisibility;

    private boolean mIsUserUnlocked;
    private final ArrayList<Runnable> mUserUnlockedActions = new ArrayList<>();
    private final BroadcastReceiver mUserUnlockedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                mIsUserUnlocked = true;
                notifyUserUnlocked();
            }
        }
    };

    private Region mExclusionRegion;
    private SystemGestureExclusionListenerCompat mExclusionListener;

    private final List<ComponentName> mGestureBlockedActivities;

    public RecentsAnimationDeviceState(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        mContext = context;
        mSysUiNavMode = SysUINavigationMode.INSTANCE.get(context);
        mDefaultDisplay = DefaultDisplay.INSTANCE.get(context);
        mDisplayId = mDefaultDisplay.getInfo().id;
        runOnDestroy(() -> mDefaultDisplay.removeChangeListener(this));

        // Register for user unlocked if necessary
        mIsUserUnlocked = context.getSystemService(UserManager.class)
                .isUserUnlocked(Process.myUserHandle());
        if (!mIsUserUnlocked) {
            mContext.registerReceiver(mUserUnlockedReceiver,
                    new IntentFilter(ACTION_USER_UNLOCKED));
        }
        runOnDestroy(() -> Utilities.unregisterReceiverSafely(mContext, mUserUnlockedReceiver));

        // Register for exclusion updates
        mExclusionListener = new SystemGestureExclusionListenerCompat(mDisplayId) {
            @Override
            @BinderThread
            public void onExclusionChanged(Region region) {
                // Assignments are atomic, it should be safe on binder thread
                mExclusionRegion = region;
            }
        };
        runOnDestroy(mExclusionListener::unregister);

        // Register for navigation mode changes
        onNavigationModeChanged(mSysUiNavMode.addModeChangeListener(this));
        runOnDestroy(() -> mSysUiNavMode.removeModeChangeListener(this));

        // Add any blocked activities
        String[] blockingActivities;
        try {
            blockingActivities =
                    context.getResources().getStringArray(R.array.gesture_blocking_activities);
        } catch (Resources.NotFoundException e) {
            blockingActivities = new String[0];
        }
        mGestureBlockedActivities = new ArrayList<>(blockingActivities.length);
        for (String blockingActivity : blockingActivities) {
            if (!TextUtils.isEmpty(blockingActivity)) {
                mGestureBlockedActivities.add(
                        ComponentName.unflattenFromString(blockingActivity));
            }
        }
    }

    private void runOnDestroy(Runnable action) {
        mOnDestroyActions.add(action);
    }

    /**
     * Cleans up all the registered listeners and receivers.
     */
    public void destroy() {
        for (Runnable r : mOnDestroyActions) {
            r.run();
        }
    }

    /**
     * Adds a listener for the nav mode change, guaranteed to be called after the device state's
     * mode has changed.
     */
    public void addNavigationModeChangedCallback(NavigationModeChangeListener listener) {
        listener.onNavigationModeChanged(mSysUiNavMode.addModeChangeListener(listener));
        runOnDestroy(() -> mSysUiNavMode.removeModeChangeListener(listener));
    }

    @Override
    public void onNavigationModeChanged(SysUINavigationMode.Mode newMode) {
        if (TestProtocol.sDebugTracing) {
            Log.d(TestProtocol.NO_BACKGROUND_TO_OVERVIEW_TAG, "onNavigationModeChanged " + newMode);
        }
        mDefaultDisplay.removeChangeListener(this);
        if (newMode.hasGestures) {
            mDefaultDisplay.addChangeListener(this);
        }

        if (newMode == NO_BUTTON) {
            mExclusionListener.register();
        } else {
            mExclusionListener.unregister();
        }
        mMode = newMode;
        mNavBarPosition = new NavBarPosition(mMode, mDefaultDisplay.getInfo());
    }

    @Override
    public void onDisplayInfoChanged(DefaultDisplay.Info info, int flags) {
        if (info.id != getDisplayId()) {
            return;
        }

        mNavBarPosition = new NavBarPosition(mMode, info);
        updateGestureTouchRegions();
    }

    /**
     * @return the current navigation mode for the device.
     */
    public SysUINavigationMode.Mode getNavMode() {
        return mMode;
    }

    /**
     * @return the nav bar position for the current nav bar mode and display rotation.
     */
    public NavBarPosition getNavBarPosition() {
        return mNavBarPosition;
    }

    /**
     * @return whether the current nav mode is fully gestural.
     */
    public boolean isFullyGesturalNavMode() {
        return mMode == NO_BUTTON;
    }

    /**
     * @return whether the current nav mode has some gestures (either 2 or 0 button mode).
     */
    public boolean isGesturalNavMode() {
        return mMode == TWO_BUTTONS || mMode == NO_BUTTON;
    }

    /**
     * @return whether the current nav mode is button-based.
     */
    public boolean isButtonNavMode() {
        return mMode == THREE_BUTTONS;
    }

    /**
     * @return the display id for the display that Launcher is running on.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Adds a callback for when a user is unlocked. If the user is already unlocked, this listener
     * will be called back immediately.
     */
    public void runOnUserUnlocked(Runnable action) {
        if (mIsUserUnlocked) {
            action.run();
        } else {
            mUserUnlockedActions.add(action);
        }
    }

    /**
     * @return whether the user is unlocked.
     */
    public boolean isUserUnlocked() {
        return mIsUserUnlocked;
    }

    private void notifyUserUnlocked() {
        for (Runnable action : mUserUnlockedActions) {
            action.run();
        }
        mUserUnlockedActions.clear();
        Utilities.unregisterReceiverSafely(mContext, mUserUnlockedReceiver);
    }

    /**
     * @return whether the given running task info matches the gesture-blocked activity.
     */
    public boolean isGestureBlockedActivity(ActivityManager.RunningTaskInfo runningTaskInfo) {
        return runningTaskInfo != null
                && mGestureBlockedActivities.contains(runningTaskInfo.topActivity);
    }

    /**
     * @return the packages of gesture-blocked activities.
     */
    public List<String> getGestureBlockedActivityPackages() {
        return mGestureBlockedActivities.stream().map(ComponentName::getPackageName)
                .collect(Collectors.toList());
    }

    /**
     * Updates the system ui state flags from SystemUI.
     */
    public void setSystemUiFlags(int stateFlags) {
        mSystemUiStateFlags = stateFlags;
    }

    /**
     * @return the system ui state flags.
     */
    // TODO(141886704): See if we can remove this
    public @SystemUiStateFlags int getSystemUiStateFlags() {
        return mSystemUiStateFlags;
    }

    /**
     * @return whether SystemUI is in a state where we can start a system gesture.
     */
    public boolean canStartSystemGesture() {
        return (mSystemUiStateFlags & SYSUI_STATE_NAV_BAR_HIDDEN) == 0
                && (mSystemUiStateFlags & SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED) == 0
                && (mSystemUiStateFlags & SYSUI_STATE_QUICK_SETTINGS_EXPANDED) == 0
                && ((mSystemUiStateFlags & SYSUI_STATE_HOME_DISABLED) == 0
                        || (mSystemUiStateFlags & SYSUI_STATE_OVERVIEW_DISABLED) == 0);
    }

    /**
     * @return whether the keyguard is showing and is occluded by an app showing above the keyguard
     *         (like camera or maps)
     */
    public boolean isKeyguardShowingOccluded() {
        return (mSystemUiStateFlags & SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED) != 0;
    }

    /**
     * @return whether screen pinning is enabled and active
     */
    public boolean isScreenPinningActive() {
        return (mSystemUiStateFlags & SYSUI_STATE_SCREEN_PINNING) != 0;
    }

    /**
     * @return whether lock-task mode is active
     */
    public boolean isLockToAppActive() {
        return ActivityManagerWrapper.getInstance().isLockToAppActive();
    }

    /**
     * @return whether the accessibility menu is available.
     */
    public boolean isAccessibilityMenuAvailable() {
        return (mSystemUiStateFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
    }

    /**
     * @return whether the accessibility menu shortcut is available.
     */
    public boolean isAccessibilityMenuShortcutAvailable() {
        return (mSystemUiStateFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;
    }

    /**
     * @return whether home is disabled (either by SUW/SysUI/device policy)
     */
    public boolean isHomeDisabled() {
        return (mSystemUiStateFlags & SYSUI_STATE_HOME_DISABLED) != 0;
    }

    /**
     * @return whether overview is disabled (either by SUW/SysUI/device policy)
     */
    public boolean isOverviewDisabled() {
        return (mSystemUiStateFlags & SYSUI_STATE_OVERVIEW_DISABLED) != 0;
    }

    /**
     * Updates the regions for detecting the swipe up/quickswitch and assistant gestures.
     */
    public void updateGestureTouchRegions() {
        if (!mMode.hasGestures) {
            return;
        }

        Resources res = mContext.getResources();
        DefaultDisplay.Info displayInfo = mDefaultDisplay.getInfo();
        Point realSize = new Point(displayInfo.realSize);
        mSwipeUpTouchRegion.set(0, 0, realSize.x, realSize.y);
        if (mMode == NO_BUTTON) {
            int touchHeight = ResourceUtils.getNavbarSize(NAVBAR_BOTTOM_GESTURE_SIZE, res);
            mSwipeUpTouchRegion.top = mSwipeUpTouchRegion.bottom - touchHeight;

            final int assistantWidth = res.getDimensionPixelSize(R.dimen.gestures_assistant_width);
            final float assistantHeight = Math.max(touchHeight,
                    QuickStepContract.getWindowCornerRadius(res));
            mAssistantLeftRegion.bottom = mAssistantRightRegion.bottom = mSwipeUpTouchRegion.bottom;
            mAssistantLeftRegion.top = mAssistantRightRegion.top =
                    mSwipeUpTouchRegion.bottom - assistantHeight;

            mAssistantLeftRegion.left = 0;
            mAssistantLeftRegion.right = assistantWidth;

            mAssistantRightRegion.right = mSwipeUpTouchRegion.right;
            mAssistantRightRegion.left = mSwipeUpTouchRegion.right - assistantWidth;
        } else {
            mAssistantLeftRegion.setEmpty();
            mAssistantRightRegion.setEmpty();
            switch (displayInfo.rotation) {
                case Surface.ROTATION_90:
                    mSwipeUpTouchRegion.left = mSwipeUpTouchRegion.right
                            - ResourceUtils.getNavbarSize(NAVBAR_LANDSCAPE_LEFT_RIGHT_SIZE, res);
                    break;
                case Surface.ROTATION_270:
                    mSwipeUpTouchRegion.right = mSwipeUpTouchRegion.left
                            + ResourceUtils.getNavbarSize(NAVBAR_LANDSCAPE_LEFT_RIGHT_SIZE, res);
                    break;
                default:
                    mSwipeUpTouchRegion.top = mSwipeUpTouchRegion.bottom
                            - ResourceUtils.getNavbarSize(NAVBAR_BOTTOM_GESTURE_SIZE, res);
            }
        }
    }

    /**
     * @return whether the coordinates of the {@param event} is in the swipe up gesture region.
     */
    public boolean isInSwipeUpTouchRegion(MotionEvent event) {
        return mSwipeUpTouchRegion.contains(event.getX(), event.getY());
    }

    /**
     * @return whether the coordinates of the {@param event} with the given {@param pointerIndex}
     *         is in the swipe up gesture region.
     */
    public boolean isInSwipeUpTouchRegion(MotionEvent event, int pointerIndex) {
        return mSwipeUpTouchRegion.contains(event.getX(pointerIndex), event.getY(pointerIndex));
    }

    /**
     * Sets the region in screen space where the gestures should be deferred (ie. due to specific
     * nav bar ui).
     */
    public void setDeferredGestureRegion(Region deferredGestureRegion) {
        mDeferredGestureRegion.set(deferredGestureRegion);
    }

    /**
     * @return whether the given {@param event} is in the deferred gesture region indicating that
     *         the Launcher should not immediately start the recents animation until the gesture
     *         passes a certain threshold.
     */
    public boolean isInDeferredGestureRegion(MotionEvent event) {
        return mDeferredGestureRegion.contains((int) event.getX(), (int) event.getY());
    }

    /**
     * @return whether the given {@param event} is in the app-requested gesture-exclusion region.
     *         This is only used for quickswitch, and not swipe up.
     */
    public boolean isInExclusionRegion(MotionEvent event) {
        // mExclusionRegion can change on binder thread, use a local instance here.
        Region exclusionRegion = mExclusionRegion;
        return mMode == NO_BUTTON && exclusionRegion != null
                && exclusionRegion.contains((int) event.getX(), (int) event.getY());
    }

    /**
     * Sets whether the assistant is available.
     */
    public void setAssistantAvailable(boolean assistantAvailable) {
        mAssistantAvailable = assistantAvailable;
    }

    /**
     * Sets the visibility fraction of the assistant.
     */
    public void setAssistantVisibility(float visibility) {
        mAssistantVisibility = visibility;
    }

    /**
     * @return the visibility fraction of the assistant.
     */
    public float getAssistantVisibility() {
        return mAssistantVisibility;
    }

    /**
     * @param ev An ACTION_DOWN motion event
     * @return whether the given motion event can trigger the assistant.
     */
    public boolean canTriggerAssistantAction(MotionEvent ev) {
        return mAssistantAvailable
                && !QuickStepContract.isAssistantGestureDisabled(mSystemUiStateFlags)
                && (mAssistantLeftRegion.contains(ev.getX(), ev.getY())
                        || mAssistantRightRegion.contains(ev.getX(), ev.getY()))
                && !isLockToAppActive();
    }

    public void dump(PrintWriter pw) {
        pw.println("DeviceState:");
        pw.println("  canStartSystemGesture=" + canStartSystemGesture());
        pw.println("  systemUiFlags=" + mSystemUiStateFlags);
        pw.println("  systemUiFlagsDesc="
                + QuickStepContract.getSystemUiStateString(mSystemUiStateFlags));
        pw.println("  assistantAvailable=" + mAssistantAvailable);
        pw.println("  assistantDisabled="
                + QuickStepContract.isAssistantGestureDisabled(mSystemUiStateFlags));
    }
}
