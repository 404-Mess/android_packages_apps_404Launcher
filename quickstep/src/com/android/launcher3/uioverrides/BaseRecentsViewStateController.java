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

package com.android.launcher3.uioverrides;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA;
import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X;
import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_Y;
import static com.android.launcher3.anim.Interpolators.AGGRESSIVE_EASE_IN_OUT;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.graphics.Scrim.SCRIM_PROGRESS;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_OVERVIEW_FADE;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_OVERVIEW_SCALE;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_OVERVIEW_SCRIM_FADE;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_OVERVIEW_TRANSLATE_X;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_OVERVIEW_TRANSLATE_Y;
import static com.android.launcher3.states.StateAnimationConfig.PLAY_ATOMIC_OVERVIEW_PEEK;
import static com.android.launcher3.states.StateAnimationConfig.PLAY_ATOMIC_OVERVIEW_SCALE;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_OVERVIEW;

import android.util.FloatProperty;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.LauncherState.ScaleAndTranslation;
import com.android.launcher3.LauncherStateManager.StateHandler;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.graphics.OverviewScrim;
import com.android.launcher3.states.StateAnimationConfig;

/**
 * State handler for recents view. Manages UI changes and animations for recents view based off the
 * current {@link LauncherState}.
 *
 * @param <T> the recents view
 */
public abstract class BaseRecentsViewStateController<T extends View>
        implements StateHandler {
    protected final T mRecentsView;
    protected final Launcher mLauncher;
    protected final View mActionsView;

    public BaseRecentsViewStateController(@NonNull Launcher launcher) {
        mLauncher = launcher;
        mRecentsView = launcher.getOverviewPanel();
        mActionsView = launcher.getActionsView();
    }

    @Override
    public void setState(@NonNull LauncherState state) {
        ScaleAndTranslation scaleAndTranslation = state
                .getOverviewScaleAndTranslation(mLauncher);
        SCALE_PROPERTY.set(mRecentsView, scaleAndTranslation.scale);
        float translationX = scaleAndTranslation.translationX;
        if (mRecentsView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            translationX = -translationX;
        }
        mRecentsView.setTranslationX(translationX);
        mRecentsView.setTranslationY(scaleAndTranslation.translationY);
        getContentAlphaProperty().set(mRecentsView, state.overviewUi ? 1f : 0);
        OverviewScrim scrim = mLauncher.getDragLayer().getOverviewScrim();
        SCRIM_PROGRESS.set(scrim, state.getOverviewScrimAlpha(mLauncher));
        if (mActionsView != null) {
            mActionsView.setTranslationX(translationX);
            mActionsView.setAlpha(state.overviewUi ? 1f : 0);
        }
    }

    @Override
    public void setStateWithAnimation(LauncherState toState, StateAnimationConfig config,
            PendingAnimation builder) {
        if (!config.hasAnimationFlag(PLAY_ATOMIC_OVERVIEW_PEEK | PLAY_ATOMIC_OVERVIEW_SCALE)) {
            // The entire recents animation is played atomically.
            return;
        }
        if (config.hasAnimationFlag(SKIP_OVERVIEW)) {
            return;
        }
        setStateWithAnimationInternal(toState, config, builder);
    }

    /**
     * Core logic for animating the recents view UI.
     *
     * @param toState state to animate to
     * @param config current animation config
     * @param setter animator set builder
     */
    void setStateWithAnimationInternal(@NonNull final LauncherState toState,
            @NonNull StateAnimationConfig config, @NonNull PendingAnimation setter) {
        ScaleAndTranslation scaleAndTranslation = toState.getOverviewScaleAndTranslation(mLauncher);
        Interpolator scaleInterpolator = config.getInterpolator(ANIM_OVERVIEW_SCALE, LINEAR);
        setter.setFloat(mRecentsView, SCALE_PROPERTY, scaleAndTranslation.scale, scaleInterpolator);
        Interpolator translateXInterpolator = config.getInterpolator(
                ANIM_OVERVIEW_TRANSLATE_X, LINEAR);
        Interpolator translateYInterpolator = config.getInterpolator(
                ANIM_OVERVIEW_TRANSLATE_Y, LINEAR);
        float translationX = scaleAndTranslation.translationX;
        if (mRecentsView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            translationX = -translationX;
        }
        setter.setFloat(mRecentsView, VIEW_TRANSLATE_X, translationX, translateXInterpolator);
        setter.setFloat(mRecentsView, VIEW_TRANSLATE_Y, scaleAndTranslation.translationY,
                translateYInterpolator);
        setter.setFloat(mRecentsView, getContentAlphaProperty(), toState.overviewUi ? 1 : 0,
                config.getInterpolator(ANIM_OVERVIEW_FADE, AGGRESSIVE_EASE_IN_OUT));
        OverviewScrim scrim = mLauncher.getDragLayer().getOverviewScrim();
        setter.setFloat(scrim, SCRIM_PROGRESS, toState.getOverviewScrimAlpha(mLauncher),
                config.getInterpolator(ANIM_OVERVIEW_SCRIM_FADE, LINEAR));
        if (mActionsView != null) {
            setter.setFloat(mActionsView, VIEW_TRANSLATE_X, translationX, translateXInterpolator);
            setter.setFloat(mActionsView, VIEW_ALPHA, toState.overviewUi ? 1 : 0,
                    config.getInterpolator(ANIM_OVERVIEW_FADE, AGGRESSIVE_EASE_IN_OUT));
        }
    }

    /**
     * Get property for content alpha for the recents view.
     *
     * @return the float property for the view's content alpha
     */
    abstract FloatProperty getContentAlphaProperty();
}
