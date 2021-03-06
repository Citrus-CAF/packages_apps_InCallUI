/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.incallui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Message;
import android.os.Trace;
import android.os.Handler;
import android.os.Looper;
import android.content.ContentResolver;
import android.media.AudioManager;
import android.provider.Settings;
import android.os.Message;
import android.provider.Telephony.Sms;
import android.telecom.DisconnectCause;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import android.telecom.CallAudioState;

import com.android.contacts.common.util.MaterialColorMapUtils.MaterialPalette;
import com.android.contacts.common.widget.FloatingActionButtonController;
import com.android.phone.common.animation.AnimUtils;

import com.suntek.mway.rcs.client.api.basic.BasicApi;
import com.suntek.mway.rcs.client.api.support.SupportApi;
import com.suntek.rcs.ui.common.GifMovieView;
import com.suntek.rcs.ui.common.RcsLog;
import com.suntek.rcs.ui.common.RcsRichScreen;

import java.util.List;

/**
 * Fragment for call card.
 */
public class CallCardFragment extends BaseFragment<CallCardPresenter, CallCardPresenter.CallCardUi>
        implements CallCardPresenter.CallCardUi {
    private static final String TAG = "CallCardFragment";

    /**
     * Internal class which represents the call state label which is to be applied.
     */
    private class CallStateLabel {
        private CharSequence mCallStateLabel;
        private boolean mIsAutoDismissing;

        public CallStateLabel(CharSequence callStateLabel, boolean isAutoDismissing) {
            mCallStateLabel = callStateLabel;
            mIsAutoDismissing = isAutoDismissing;
        }

        public CharSequence getCallStateLabel() {
            return mCallStateLabel;
        }

        /**
         * Determines if the call state label should auto-dismiss.
         *
         * @return {@code true} if the call state label should auto-dismiss.
         */
        public boolean isAutoDismissing() {
            return mIsAutoDismissing;
        }
    };

    /**
     * The duration of time (in milliseconds) a call state label should remain visible before
     * resetting to its previous value.
     */
    private static final long CALL_STATE_LABEL_RESET_DELAY_MS = 3000;
    /**
     * Amount of time to wait before sending an announcement via the accessibility manager.
     * When the call state changes to an outgoing or incoming state for the first time, the
     * UI can often be changing due to call updates or contact lookup. This allows the UI
     * to settle to a stable state to ensure that the correct information is announced.
     */
    private static final long ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS = 500;

    private static final String RECORD_STATE_CHANGED =
            "com.qualcomm.qti.phonefeature.RECORD_STATE_CHANGED";

    private static final int MESSAGE_TIMER = 1;

    private AnimatorSet mAnimatorSet;
    private int mShrinkAnimationDuration;
    private int mFabNormalDiameter;
    private int mFabSmallDiameter;
    private boolean mIsLandscape;
    private boolean mIsDialpadShowing;

    // Primary caller info
    private TextView mPhoneNumber;
    private TextView mNumberLabel;
    private TextView mPrimaryName;
    private View mCallStateButton;
    private ImageView mCallStateIcon;
    private ImageView mCallStateVideoCallIcon;
    private TextView mCallStateLabel;
    private TextView mCallTypeLabel;
    private ImageView mHdAudioIcon;
    private ImageView mForwardIcon;
    private View mCallNumberAndLabel;
    private ImageView mPhoto;
    private TextView mElapsedTime;
    private Drawable mPrimaryPhotoDrawable;
    private TextView mCallSubject;

    // Container view that houses the entire primary call card, including the call buttons
    private View mPrimaryCallCardContainer;
    // Container view that houses the primary call information
    private ViewGroup mPrimaryCallInfo;
    private View mCallButtonsContainer;
    private ImageButton mVbButton;
    private AudioManager mAudioManager;
    private Toast mVbNotify;

    // Secondary caller info
    private View mSecondaryCallInfo;
    private TextView mSecondaryCallName;
    private View mSecondaryCallProviderInfo;
    private TextView mSecondaryCallProviderLabel;
    private View mSecondaryCallConferenceCallIcon;
    private View mSecondaryCallVideoCallIcon;
    private View mProgressSpinner;

    private View mManageConferenceCallButton;

    // Dark number info bar
    private TextView mInCallMessageLabel;

    private InCallActivity mInCallActivity;
    private TextView mRecordingTimeLabel;
    private TextView mRecordingIcon;
    private String mRecordingTime;

    private FloatingActionButtonController mFloatingActionButtonController;
    private View mFloatingActionButtonContainer;
    private ImageButton mFloatingActionButton;
    private int mFloatingActionButtonVerticalOffset;

    private float mTranslationOffset;
    private Animation mPulseAnimation;

    private int mVideoAnimationDuration;
    // Whether or not the call card is currently in the process of an animation
    private boolean mIsAnimating;

    private MaterialPalette mCurrentThemeColors;

    /**
     * Call state label to set when an auto-dismissing call state label is dismissed.
     */
    private CharSequence mPostResetCallStateLabel;
    private boolean mCallStateLabelResetPending = false;
    private Handler mHandler;

    private BroadcastReceiver recorderStateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!RECORD_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            if (mInCallActivity.isCallRecording()) {
                recorderHandler.sendEmptyMessage(MESSAGE_TIMER);
            } else {
                mRecordingTimeLabel.setVisibility(View.GONE);
                mRecordingIcon.setVisibility(View.GONE);
            }
        }
    };

    private Handler recorderHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case MESSAGE_TIMER:
                    if (!mInCallActivity.isCallRecording()) {
                        break;
                    }

                    String recordingTime = mInCallActivity.getCallRecordingTime();

                    if (!TextUtils.isEmpty(recordingTime)) {
                        mRecordingTime = recordingTime;
                        mRecordingTimeLabel.setVisibility(View.VISIBLE);
                        showCallRecordingElapsedTime();
                        mRecordingIcon.setVisibility(View.VISIBLE);
                    }

                    if (!recorderHandler.hasMessages(MESSAGE_TIMER)) {
                        sendEmptyMessageDelayed(MESSAGE_TIMER, 1000);
                    }

                    break;
            }
        }
    };

    /**
     * Determines if secondary call info is populated in the secondary call info UI.
     */
    private boolean mHasSecondaryCallInfo = false;

    private static final int TTY_MODE_OFF = 0;
    private static final int TTY_MODE_HCO = 2;

    private static final String VOLUME_BOOST = "volume_boost";
    /* Begin add for RCS */
    private View mSendMessageView;
    private RcsRichScreen mRcsRichScreen = null;
    private boolean misEhanceScreenApkInstalled = false;
    private boolean mIsRcsServiceInstalled = false;
    private static final String ENHANCE_SCREEN_APK_NAME = "com.cmdm.rcs";
    private static final String LOG_TAG = "RCS_UI";
    /* End add for RCS */

    @Override
    public CallCardPresenter.CallCardUi getUi() {
        return this;
    }

    @Override
    public CallCardPresenter createPresenter() {
        return new CallCardPresenter();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler(Looper.getMainLooper());
        mShrinkAnimationDuration = getResources().getInteger(R.integer.shrink_animation_duration);
        mVideoAnimationDuration = getResources().getInteger(R.integer.video_animation_duration);
        mFloatingActionButtonVerticalOffset = getResources().getDimensionPixelOffset(
                R.dimen.floating_action_button_vertical_offset);
        mFabNormalDiameter = getResources().getDimensionPixelOffset(
                R.dimen.end_call_floating_action_button_diameter);
        mFabSmallDiameter = getResources().getDimensionPixelOffset(
                R.dimen.end_call_floating_action_button_small_diameter);

        IntentFilter filter = new IntentFilter();
        filter.addAction(RECORD_STATE_CHANGED);
        getActivity().registerReceiver(recorderStateReceiver, filter);

        mInCallActivity = (InCallActivity) getActivity();
        /* Begin add for RCS */
        misEhanceScreenApkInstalled = isEnhanceScreenInstalled();
        mIsRcsServiceInstalled = SupportApi.getInstance().isRcsSupported();
        /* Begin add for RCS */
        if (mInCallActivity.isCallRecording()) {
            recorderHandler.sendEmptyMessage(MESSAGE_TIMER);
        }
        mAudioManager = (AudioManager) getActivity()
                .getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        getActivity().unregisterReceiver(recorderStateReceiver);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final CallList calls = CallList.getInstance();
        final Call call = calls.getFirstCall();
        getPresenter().init(getActivity(), call);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        Trace.beginSection(TAG + " onCreate");
        mTranslationOffset =
                getResources().getDimensionPixelSize(R.dimen.call_card_anim_translate_y_offset);
        /* Begin add for RCS */
        if(!isRcsAvailable()){
            final View view = inflater.inflate(R.layout.call_card_fragment, container, false);
            Trace.endSection();
            return view;

        } else {
            final View rcsCallCardView = inflater.inflate(
                    R.layout.rcs_call_card_content, container, false);
            Trace.endSection();
            return rcsCallCardView;
        }
        /* End add for RCS */
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mPulseAnimation =
                AnimationUtils.loadAnimation(view.getContext(), R.anim.call_status_pulse);

        mPhoneNumber = (TextView) view.findViewById(R.id.phoneNumber);
        mPrimaryName = (TextView) view.findViewById(R.id.name);
        mNumberLabel = (TextView) view.findViewById(R.id.label);
        mSecondaryCallInfo = view.findViewById(R.id.secondary_call_info);
        mSecondaryCallProviderInfo = view.findViewById(R.id.secondary_call_provider_info);
        mPhoto = (ImageView) view.findViewById(R.id.photo);
        mPhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getPresenter().onContactPhotoClick();
            }
        });
        mCallStateIcon = (ImageView) view.findViewById(R.id.callStateIcon);
        mCallStateVideoCallIcon = (ImageView) view.findViewById(R.id.videoCallIcon);
        mCallStateLabel = (TextView) view.findViewById(R.id.callStateLabel);
        mHdAudioIcon = (ImageView) view.findViewById(R.id.hdAudioIcon);
        mForwardIcon = (ImageView) view.findViewById(R.id.forwardIcon);
        mCallNumberAndLabel = view.findViewById(R.id.labelAndNumber);
        mCallTypeLabel = (TextView) view.findViewById(R.id.callTypeLabel);
        mElapsedTime = (TextView) view.findViewById(R.id.elapsedTime);
        mPrimaryCallCardContainer = view.findViewById(R.id.primary_call_info_container);
        mPrimaryCallInfo = (ViewGroup) view.findViewById(R.id.primary_call_banner);
        mCallButtonsContainer = view.findViewById(R.id.callButtonFragment);
        mInCallMessageLabel = (TextView) view.findViewById(R.id.connectionServiceMessage);
        mProgressSpinner = view.findViewById(R.id.progressSpinner);

        /* Begin add for RCS */
        if (isRcsAvailable()) {
            TextView rcsmissdnAddress = (TextView)view.findViewById(R.id.missdnaddress);
            TextView rcsgreeting = (TextView)view.findViewById(R.id.greeting);
            SurfaceView rcssurface = (SurfaceView)view.findViewById(R.id.surface);
            ImageView rcsPhoto = (ImageView) view.findViewById(R.id.rcs_photo);
            GifMovieView rcsGifMovieView = (GifMovieView) view.findViewById(R.id.incallgifview);
            mRcsRichScreen = new RcsRichScreen(getActivity(),
                rcsPhoto, rcsgreeting, rcsmissdnAddress, rcsGifMovieView, rcssurface);
        }
        if (mIsRcsServiceInstalled) {
            mSendMessageView = view.findViewById(R.id.sendMessage);
            mSendMessageView.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    getPresenter().sendSmsClicked();
                }
            });
        } else {
            mSendMessageView = view.findViewById(R.id.sendMessage);
            mSendMessageView.setVisibility(View.GONE);
        }
        /* End add for RCS */

        mFloatingActionButtonContainer = view.findViewById(
                R.id.floating_end_call_action_button_container);
        mFloatingActionButton = (ImageButton) view.findViewById(
                R.id.floating_end_call_action_button);
        mFloatingActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getPresenter().endCallClicked();
            }
        });
        mFloatingActionButtonController = new FloatingActionButtonController(getActivity(),
                mFloatingActionButtonContainer, mFloatingActionButton);

        mSecondaryCallInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getPresenter().secondaryInfoClicked();
                updateFabPositionForSecondaryCallInfo();
            }
        });

        mCallStateButton = view.findViewById(R.id.callStateButton);
        mCallStateButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                getPresenter().onCallStateButtonTouched();
                return false;
            }
        });

        mManageConferenceCallButton = view.findViewById(R.id.manage_conference_call_button);
        mManageConferenceCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InCallActivity activity = (InCallActivity) getActivity();
                activity.showConferenceFragment(true);
            }
        });

        mPrimaryName.setElegantTextHeight(false);
        mCallStateLabel.setElegantTextHeight(false);
        mCallSubject = (TextView) view.findViewById(R.id.callSubject);
        mRecordingTimeLabel = (TextView) view.findViewById(R.id.recordingTime);
        mRecordingIcon = (TextView) view.findViewById(R.id.recordingIcon);

        mVbButton = (ImageButton) view.findViewById(R.id.volumeBoost);
        if (null != mVbButton) {
            mVbButton.setOnClickListener(mVbListener);
        }
    }

    private void showCallRecordingElapsedTime() {
        if (mRecordingTimeLabel.getVisibility() != View.VISIBLE) {
            AnimUtils.fadeIn(mRecordingTimeLabel, AnimUtils.DEFAULT_DURATION);
        }

        mRecordingTimeLabel.setText(mRecordingTime);
    }

    @Override
    public void setVisible(boolean on) {
        if (on) {
            getView().setVisibility(View.VISIBLE);
        } else {
            getView().setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Hides or shows the progress spinner.
     *
     * @param visible {@code True} if the progress spinner should be visible.
     */
    @Override
    public void setProgressSpinnerVisible(boolean visible) {
        mProgressSpinner.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets the visibility of the primary call card.
     * Ensures that when the primary call card is hidden, the video surface slides over to fill the
     * entire screen.
     *
     * @param visible {@code True} if the primary call card should be visible.
     */
    @Override
    public void setCallCardVisible(final boolean visible) {
        Log.v(this, "setCallCardVisible : isVisible = " + visible);
        // When animating the hide/show of the views in a landscape layout, we need to take into
        // account whether we are in a left-to-right locale or a right-to-left locale and adjust
        // the animations accordingly.
        final boolean isLayoutRtl = InCallPresenter.isRtl();

        // Retrieve here since at fragment creation time the incoming video view is not inflated.
        final View videoView = getView().findViewById(R.id.incomingVideo);
        if (videoView == null) {
            return;
        }

        // Determine how much space there is below or to the side of the call card.
        final float spaceBesideCallCard = getSpaceBesideCallCard();

        doActionOnPredraw(visible, isLayoutRtl, videoView, spaceBesideCallCard);
        // We need to translate the video surface, but we need to know its position after the layout
        // has occurred so use a {@code ViewTreeObserver}.
        final ViewTreeObserver observer = getView().getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // We don't want to continue getting called.
                getView().getViewTreeObserver().removeOnPreDrawListener(this);
                doActionOnPredraw(visible, isLayoutRtl, videoView, spaceBesideCallCard);
                return true;
            }
        });
    }

    private void doActionOnPredraw(final boolean visible, final boolean isLayoutRtl,
            final View videoView, final float spaceBesideCallCard) {

        float videoViewTranslation = 0f;

        // Translate the call card to its pre-animation state.
        if (!mIsLandscape) {
            mPrimaryCallCardContainer.setTranslationY(visible ?
                    -mPrimaryCallCardContainer.getHeight() : 0);

            if (visible) {
                videoViewTranslation = videoView.getHeight() / 2 - spaceBesideCallCard / 2;
            }
        }

        // Perform animation of video view.
        ViewPropertyAnimator videoViewAnimator = videoView.animate()
                .setInterpolator(AnimUtils.EASE_OUT_EASE_IN)
                .setDuration(mVideoAnimationDuration);
        if (mIsLandscape) {
            videoViewAnimator
                    .translationX(videoViewTranslation)
                    .start();
        } else {
            videoViewAnimator
                    .translationY(videoViewTranslation)
                    .start();
        }
        videoViewAnimator.start();

        // Animate the call card sliding.
        ViewPropertyAnimator callCardAnimator = mPrimaryCallCardContainer.animate()
                .setInterpolator(AnimUtils.EASE_OUT_EASE_IN)
                .setDuration(mVideoAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        if (!visible) {
                            mPrimaryCallCardContainer.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onAnimationStart(Animator animation) {
                        super.onAnimationStart(animation);
                        if (visible) {
                            mPrimaryCallCardContainer.setVisibility(View.VISIBLE);
                        }
                    }
                });

        if (mIsLandscape) {
            float translationX = mPrimaryCallCardContainer.getWidth();
            translationX *= isLayoutRtl ? 1 : -1;
            callCardAnimator
                    .translationX(visible ? 0 : translationX)
                    .start();
        } else {
            callCardAnimator
                    .translationY(visible ? 0 : -mPrimaryCallCardContainer.getHeight())
                    .start();
        }

    }
    /**
     * Determines the amount of space below the call card for portrait layouts), or beside the
     * call card for landscape layouts.
     *
     * @return The amount of space below or beside the call card.
     */
    public float getSpaceBesideCallCard() {
        if (mIsLandscape) {
            return getView().getWidth() - mPrimaryCallCardContainer.getWidth();
        } else {
            final int callCardHeight;
            // Retrieve the actual height of the call card, independent of whether or not the
            // outgoing call animation is in progress. The animation does not run in landscape mode
            // so this only needs to be done for portrait.
            if (mPrimaryCallCardContainer.getTag(R.id.view_tag_callcard_actual_height) != null) {
                callCardHeight = (int) mPrimaryCallCardContainer.getTag(
                        R.id.view_tag_callcard_actual_height);
            } else {
                callCardHeight = mPrimaryCallCardContainer.getHeight();
            }
            return getView().getHeight() - callCardHeight;
        }
    }

    @Override
    public void setPrimaryName(String name, boolean nameIsNumber) {
        if (TextUtils.isEmpty(name)) {
            mPrimaryName.setText(null);
        } else {
            mPrimaryName.setText(nameIsNumber
                    ? PhoneNumberUtils.createTtsSpannable(name)
                    : name);

            // Set direction of the name field
            int nameDirection = View.TEXT_DIRECTION_INHERIT;
            if (nameIsNumber) {
                nameDirection = View.TEXT_DIRECTION_LTR;
            }
            mPrimaryName.setTextDirection(nameDirection);
        }
    }

    /**
     * Sets the primary image for the contact photo.
     *
     * @param image The drawable to set.
     * @param isVisible Whether the contact photo should be visible after being set.
     */
    @Override
    public void setPrimaryImage(Drawable image, boolean isVisible) {
        if (image != null) {
            setDrawableToImageView(mPhoto, image, isVisible);
        }
    }

    @Override
    public void setPrimaryPhoneNumber(String number) {
        // Set the number
        if (TextUtils.isEmpty(number)) {
            mPhoneNumber.setText(null);
            mPhoneNumber.setVisibility(View.GONE);
        } else {
            mPhoneNumber.setText(PhoneNumberUtils.createTtsSpannable(number));
            mPhoneNumber.setVisibility(View.VISIBLE);
            mPhoneNumber.setTextDirection(View.TEXT_DIRECTION_LTR);
        }
    }

    @Override
    public void setPrimaryLabel(String label) {
        if (!TextUtils.isEmpty(label)) {
            mNumberLabel.setText(label);
            mNumberLabel.setVisibility(View.VISIBLE);
        } else {
            mNumberLabel.setVisibility(View.GONE);
        }

    }

    /**
     * Sets the primary caller information.
     *
     * @param number The caller phone number.
     * @param name The caller name.
     * @param nameIsNumber {@code true} if the name should be shown in place of the phone number.
     * @param label The label.
     * @param photo The contact photo drawable.
     * @param isSipCall {@code true} if this is a SIP call.
     * @param isContactPhotoShown {@code true} if the contact photo should be shown (it will be
     *      updated even if it is not shown).
     */
    @Override
    public void setPrimary(String number, String name, boolean nameIsNumber, String label,
            Drawable photo, boolean isSipCall, boolean isContactPhotoShown) {
        Log.d(this, "Setting primary call");
        // set the name field.
        setPrimaryName(name, nameIsNumber);

        if (TextUtils.isEmpty(number) && TextUtils.isEmpty(label)) {
            mCallNumberAndLabel.setVisibility(View.GONE);
            mElapsedTime.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        } else {
            mCallNumberAndLabel.setVisibility(View.VISIBLE);
            mElapsedTime.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        }

        setPrimaryPhoneNumber(number);

        // Set the label (Mobile, Work, etc)
        setPrimaryLabel(label);

        showInternetCallLabel(isSipCall);

        /* Begin add for RCS */
        if (mRcsRichScreen != null && isRcsAvailable()) {
            String rcsnumber = null;
            if(!nameIsNumber){
                rcsnumber = number;
            } else {
                rcsnumber = name;
            }
            mRcsRichScreen.setNumber(rcsnumber);
        }
        /* End add for RCS */
        setDrawableToImageView(mPhoto, photo, isContactPhotoShown);
    }

    @Override
    public void setSecondary(boolean show, String name, boolean nameIsNumber, String label,
            String providerLabel, boolean isConference, boolean isVideoCall, boolean isFullscreen) {

        if (show) {
            mHasSecondaryCallInfo = true;
            boolean hasProvider = !TextUtils.isEmpty(providerLabel);
            initializeSecondaryCallInfo(hasProvider);

            // Do not show the secondary caller info in fullscreen mode, but ensure it is populated
            // in case fullscreen mode is exited in the future.
            setSecondaryInfoVisible(!isFullscreen);

            mSecondaryCallConferenceCallIcon.setVisibility(isConference ? View.VISIBLE : View.GONE);
            mSecondaryCallVideoCallIcon.setVisibility(isVideoCall ? View.VISIBLE : View.GONE);

            mSecondaryCallName.setText(nameIsNumber
                    ? PhoneNumberUtils.createTtsSpannable(name)
                    : name);
            if (hasProvider) {
                mSecondaryCallProviderLabel.setText(providerLabel);
            }

            int nameDirection = View.TEXT_DIRECTION_INHERIT;
            if (nameIsNumber) {
                nameDirection = View.TEXT_DIRECTION_LTR;
            }
            mSecondaryCallName.setTextDirection(nameDirection);
        } else {
            mHasSecondaryCallInfo = false;
            setSecondaryInfoVisible(false);
        }
    }

    /**
     * Sets the visibility of the secondary caller info box.  Note, if the {@code visible} parameter
     * is passed in {@code true}, and there is no secondary caller info populated (as determined by
     * {@code mHasSecondaryCallInfo}, the secondary caller info box will not be shown.
     *
     * @param visible {@code true} if the secondary caller info should be shown, {@code false}
     *      otherwise.
     */
    @Override
    public void setSecondaryInfoVisible(final boolean visible) {
        boolean wasVisible = mSecondaryCallInfo.isShown();
        final boolean isVisible = visible && mHasSecondaryCallInfo;
        // If hide request is coming when InCallUI is in background, force the view to hide.
        final boolean needForceHide = !isVisible &&
                mSecondaryCallInfo.getVisibility() == View.VISIBLE &&
                (!InCallPresenter.getInstance().isShowingInCallUi() ||
                InCallPresenter.getInstance().isShowingManageConferenceUi());
        Log.v(this, "setSecondaryInfoVisible: wasVisible = " + wasVisible + " isVisible = "
                + isVisible + " isFg = " + InCallPresenter.getInstance().isShowingInCallUi()
                + " view visibility = " + mSecondaryCallInfo.getVisibility());

        // If we are showing the secondary info, we need to show it before animating so that its
        // height will be determined on layout.
        if (isVisible) {
            mSecondaryCallInfo.setVisibility(View.VISIBLE);
        }

        // If secondary info visibility hasn't changed, don't animate. Return.
        if (wasVisible == isVisible && !needForceHide) {
            return;
        }

        // We need to translate the secondary caller info, but we need to know its position after
        // the layout has occurred so use a {@code ViewTreeObserver}.
        final ViewTreeObserver observer = getView().getViewTreeObserver();

        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // We don't want to continue getting called.
                getView().getViewTreeObserver().removeOnPreDrawListener(this);

                // Get the height of the secondary call info now, and then re-hide the view prior
                // to doing the actual animation.
                int secondaryHeight = mSecondaryCallInfo.getHeight();

                // Update floating end call button position onPreDraw
                updateFabPositionForSecondaryCallInfo();

                if (isVisible) {
                    mSecondaryCallInfo.setVisibility(View.GONE);
                }
                Log.v(this, "setSecondaryInfoVisible: secondaryHeight = " + secondaryHeight);

                // Set the position of the secondary call info card to its starting location.
                mSecondaryCallInfo.setTranslationY(visible ? secondaryHeight : 0);

                // Animate the secondary card info slide up/down as it appears and disappears.
                ViewPropertyAnimator secondaryInfoAnimator = mSecondaryCallInfo.animate()
                        .setInterpolator(AnimUtils.EASE_OUT_EASE_IN)
                        .setDuration(mVideoAnimationDuration)
                        .translationY(isVisible ? 0 : secondaryHeight)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                if (!isVisible) {
                                    mSecondaryCallInfo.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onAnimationStart(Animator animation) {
                                if (isVisible) {
                                    mSecondaryCallInfo.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                secondaryInfoAnimator.start();

                // Notify listeners of a change in the visibility of the secondary info. This is
                // important when in a video call so that the video call presenter can shift the
                // video preview up or down to accommodate the secondary caller info.
                InCallPresenter.getInstance().notifySecondaryCallerInfoVisibilityChanged(visible,
                        secondaryHeight);

                return true;
            }
        });
    }

    @Override
    public void setCallState(
            int state,
            int videoState,
            int sessionModificationState,
            DisconnectCause disconnectCause,
            String connectionLabel,
            Drawable callStateIcon,
            String gatewayNumber,
            boolean isWifi,
            boolean isConference) {
        boolean isGatewayCall = !TextUtils.isEmpty(gatewayNumber);
        CallStateLabel callStateLabel = getCallStateLabelFromState(state, videoState,
                sessionModificationState, disconnectCause, connectionLabel, isGatewayCall, isWifi,
                isConference);

        updateVbByCall(state);

        Log.v(this, "setCallState " + callStateLabel.getCallStateLabel());
        Log.v(this, "AutoDismiss " + callStateLabel.isAutoDismissing());
        Log.v(this, "DisconnectCause " + disconnectCause.toString());
        Log.v(this, "gateway " + connectionLabel + gatewayNumber);

        // Check for video state change and update the visibility of the contact photo.  The contact
        // photo is hidden when the incoming video surface is shown.
        // The contact photo visibility can also change in setPrimary().
        boolean showContactPhoto = !VideoCallPresenter.showIncomingVideo(videoState, state);
        mPhoto.setVisibility(showContactPhoto ? View.VISIBLE : View.GONE);

        // Check if the call subject is showing -- if it is, we want to bypass showing the call
        // state.
        boolean isSubjectShowing = mCallSubject.getVisibility() == View.VISIBLE;

        if (TextUtils.equals(callStateLabel.getCallStateLabel(), mCallStateLabel.getText()) &&
                !isSubjectShowing) {
            // Nothing to do if the labels are the same
            if (state == Call.State.ACTIVE || state == Call.State.CONFERENCED) {
                mCallStateLabel.clearAnimation();
                mCallStateIcon.clearAnimation();
            }
            return;
        }
        /* Begin add for RCS */
        if (mRcsRichScreen != null && isRcsAvailable()) {
           mRcsRichScreen.updateRichScreenByCallState(state,videoState);
        }
        /* End add for RCS */

        if (isSubjectShowing) {
            changeCallStateLabel(null);
            callStateIcon = null;
        } else {
            // Update the call state label and icon.
            setCallStateLabel(callStateLabel);
        }

        if (!TextUtils.isEmpty(callStateLabel.getCallStateLabel())) {
            if (state == Call.State.ACTIVE || state == Call.State.CONFERENCED) {
                mCallStateLabel.clearAnimation();
            } else {
                mCallStateLabel.startAnimation(mPulseAnimation);
            }
        } else {
            mCallStateLabel.clearAnimation();
        }

        if (callStateIcon != null) {
            mCallStateIcon.setVisibility(View.VISIBLE);
            // Invoke setAlpha(float) instead of setAlpha(int) to set the view's alpha. This is
            // needed because the pulse animation operates on the view alpha.
            mCallStateIcon.setAlpha(1.0f);
            mCallStateIcon.setImageDrawable(callStateIcon);

            MaterialPalette themeColors = InCallPresenter.getInstance().getThemeColors();
            if (themeColors != null) {
                // Change the alpha value in the 32 bit color of sim card, because the color of
                // call background changed with the color of sim card.
                // Set the tint mode to SCREEN to avoid the slot number in the sim icon be covered.
                int stateIconColor = (themeColors.mPrimaryColor & 0x00ffffff) | 0x7f000000;
                mCallStateIcon.setImageTintMode(PorterDuff.Mode.SCREEN);
                mCallStateIcon.setImageTintList(ColorStateList.valueOf(stateIconColor));
                Log.d(this, "Need to set tint of call state icon to " + stateIconColor);
            }

            if (state == Call.State.ACTIVE || state == Call.State.CONFERENCED
                    || TextUtils.isEmpty(callStateLabel.getCallStateLabel())) {
                mCallStateIcon.clearAnimation();
            } else {
                mCallStateIcon.startAnimation(mPulseAnimation);
            }

            if (callStateIcon instanceof AnimationDrawable) {
                ((AnimationDrawable) callStateIcon).start();
            }
        } else {
            mCallStateIcon.clearAnimation();

            // Invoke setAlpha(float) instead of setAlpha(int) to set the view's alpha. This is
            // needed because the pulse animation operates on the view alpha.
            mCallStateIcon.setAlpha(0.0f);
            mCallStateIcon.setVisibility(View.GONE);
        }

        if (CallUtils.isVideoCall(videoState)
                || (state == Call.State.ACTIVE && sessionModificationState
                        == Call.SessionModificationState.WAITING_FOR_RESPONSE)) {
            mCallStateVideoCallIcon.setVisibility(View.VISIBLE);
        } else {
            mCallStateVideoCallIcon.setVisibility(View.GONE);
        }
    }

    private void setCallStateLabel(CallStateLabel callStateLabel) {
        Log.v(this, "setCallStateLabel : label = " + callStateLabel.getCallStateLabel());

        if (callStateLabel.isAutoDismissing()) {
            mCallStateLabelResetPending = true;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.v(this, "restoringCallStateLabel : label = " +
                            mPostResetCallStateLabel);
                    changeCallStateLabel(mPostResetCallStateLabel);
                    mCallStateLabelResetPending = false;
                }
            }, CALL_STATE_LABEL_RESET_DELAY_MS);

            changeCallStateLabel(callStateLabel.getCallStateLabel());
        } else {
            // Keep track of the current call state label; used when resetting auto dismissing
            // call state labels.
            mPostResetCallStateLabel = callStateLabel.getCallStateLabel();

            if (!mCallStateLabelResetPending) {
                changeCallStateLabel(callStateLabel.getCallStateLabel());
            }
        }
    }

    private void changeCallStateLabel(CharSequence callStateLabel) {
        Log.v(this, "changeCallStateLabel : label = " + callStateLabel);
        if (!TextUtils.isEmpty(callStateLabel)) {
            mCallStateLabel.setText(callStateLabel);
            mCallStateLabel.setAlpha(1);
            mCallStateLabel.setVisibility(View.VISIBLE);
        } else {
            Animation callStateLabelAnimation = mCallStateLabel.getAnimation();
            if (callStateLabelAnimation != null) {
                callStateLabelAnimation.cancel();
            }
            mCallStateLabel.setText(null);
            mCallStateLabel.setAlpha(0);
            mCallStateLabel.setVisibility(View.GONE);
        }
    }

    @Override
    public void setCallbackNumber(String callbackNumber, boolean isEmergencyCall) {
        if (mInCallMessageLabel == null) {
            return;
        }

        if (TextUtils.isEmpty(callbackNumber)) {
            mInCallMessageLabel.setVisibility(View.GONE);
            return;
        }

        // TODO: The new Locale-specific methods don't seem to be working. Revisit this.
        callbackNumber = PhoneNumberUtils.formatNumber(callbackNumber);

        int stringResourceId = isEmergencyCall ? R.string.card_title_callback_number_emergency
                : R.string.card_title_callback_number;

        String text = getString(stringResourceId, callbackNumber);
        mInCallMessageLabel.setText(text);

        mInCallMessageLabel.setVisibility(View.VISIBLE);
    }

    /**
     * Sets and shows the call subject if it is not empty.  Hides the call subject otherwise.
     *
     * @param callSubject The call subject.
     */
    @Override
    public void setCallSubject(String callSubject) {
        boolean showSubject = !TextUtils.isEmpty(callSubject);

        mCallSubject.setVisibility(showSubject ? View.VISIBLE : View.GONE);
        if (showSubject) {
            mCallSubject.setText(callSubject);
        } else {
            mCallSubject.setText(null);
        }
    }

    public boolean isAnimating() {
        return mIsAnimating;
    }

    private void showInternetCallLabel(boolean show) {
        if (show) {
            final String label = getView().getContext().getString(
                    R.string.incall_call_type_label_sip);
            mCallTypeLabel.setVisibility(View.VISIBLE);
            mCallTypeLabel.setText(label);
        } else {
            mCallTypeLabel.setVisibility(View.GONE);
        }
    }

    @Override
    public void setPrimaryCallElapsedTime(boolean show, long duration) {
        if (show) {
            if (mElapsedTime.getVisibility() != View.VISIBLE) {
                AnimUtils.fadeIn(mElapsedTime, AnimUtils.DEFAULT_DURATION);
            }
            String callTimeElapsed = DateUtils.formatElapsedTime(duration / 1000);
            mElapsedTime.setText(callTimeElapsed);

            String durationDescription =
                    InCallDateUtils.formatDuration(getView().getContext(), duration);
            mElapsedTime.setContentDescription(
                    !TextUtils.isEmpty(durationDescription) ? durationDescription : null);
        } else {
            // hide() animation has no effect if it is already hidden.
            AnimUtils.fadeOut(mElapsedTime, AnimUtils.DEFAULT_DURATION);
        }
    }

    private void setDrawableToImageView(ImageView view, Drawable photo, boolean isVisible) {
        if (photo == null) {
            photo = ContactInfoCache.getInstance(
                    view.getContext()).getDefaultContactPhotoDrawable();
        }

        if (mPrimaryPhotoDrawable == photo) {
            return;
        }
        mPrimaryPhotoDrawable = photo;

        final Drawable current = view.getDrawable();
        if (current == null) {
            view.setImageDrawable(photo);
            if (isVisible) {
                AnimUtils.fadeIn(mElapsedTime, AnimUtils.DEFAULT_DURATION);
            }
        } else {
            // Cross fading is buggy and not noticable due to the multiple calls to this method
            // that switch drawables in the middle of the cross-fade animations. Just set the
            // photo directly instead.
            view.setImageDrawable(photo);
            view.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Gets the call state label based on the state of the call or cause of disconnect.
     *
     * Additional labels are applied as follows:
     *         1. All outgoing calls with display "Calling via [Provider]".
     *         2. Ongoing calls will display the name of the provider.
     *         3. Incoming calls will only display "Incoming via..." for accounts.
     *         4. Video calls, and session modification states (eg. requesting video).
     *         5. Incoming and active Wi-Fi calls will show label provided by hint.
     *
     * TODO: Move this to the CallCardPresenter.
     */
    private CallStateLabel getCallStateLabelFromState(int state, int videoState,
            int sessionModificationState, DisconnectCause disconnectCause, String label,
            boolean isGatewayCall, boolean isWifi, boolean isConference) {
        final Context context = getView().getContext();
        CharSequence callStateLabel = null;  // Label to display as part of the call banner

        boolean hasSuggestedLabel = label != null;
        boolean isAccount = hasSuggestedLabel && !isGatewayCall;
        boolean isAutoDismissing = false;

        switch  (state) {
            case Call.State.IDLE:
                // "Call state" is meaningless in this state.
                break;
            case Call.State.ACTIVE:
                // We normally don't show a "call state label" at all in this state
                // (but we can use the call state label to display the provider name).
                if (sessionModificationState
                        == Call.SessionModificationState.REQUEST_REJECTED) {
                    callStateLabel = context.getString(R.string.card_title_video_call_rejected);
                    isAutoDismissing = true;
                } else if (sessionModificationState
                        == Call.SessionModificationState.REQUEST_FAILED) {
                    callStateLabel = context.getString(R.string.card_title_video_call_error);
                    isAutoDismissing = true;
                } else if (sessionModificationState
                        == Call.SessionModificationState.WAITING_FOR_RESPONSE) {
                    callStateLabel = context.getString(R.string.card_title_video_call_requesting);
                } else if (sessionModificationState
                        == Call.SessionModificationState.RECEIVED_UPGRADE_TO_VIDEO_REQUEST) {
                    callStateLabel = context.getString(R.string.card_title_video_call_requesting);
                } else if (CallUtils.isVideoCall(videoState)) {
                    callStateLabel = context.getString(R.string.card_title_video_call);
                }

                if ((isAccount || isWifi || isConference) && hasSuggestedLabel) {
                   label += (callStateLabel != null) ? (" " + callStateLabel) : "";
                   callStateLabel = label;
                }
                break;
            case Call.State.ONHOLD:
                callStateLabel = context.getString(R.string.card_title_on_hold);
                break;
            case Call.State.CONNECTING:
            case Call.State.DIALING:
                if (hasSuggestedLabel && !isWifi) {
                    callStateLabel = context.getString(R.string.calling_via_template, label);
                } else {
                    callStateLabel = context.getString(R.string.card_title_dialing);
                }
                break;
            case Call.State.REDIALING:
                callStateLabel = context.getString(R.string.card_title_redialing);
                break;
            case Call.State.INCOMING:
            case Call.State.CALL_WAITING:
                if (isWifi && hasSuggestedLabel) {
                    callStateLabel = label;
                } else if (isAccount) {
                    callStateLabel = context.getString(R.string.incoming_via_template, label);
                } else if (VideoProfile.isTransmissionEnabled(videoState) ||
                        VideoProfile.isReceptionEnabled(videoState)) {
                    callStateLabel = context.getString(R.string.notification_incoming_video_call);
                } else {
                    callStateLabel = context.getString(R.string.card_title_incoming_call);
                }
                break;
            case Call.State.DISCONNECTING:
                // While in the DISCONNECTING state we display a "Hanging up"
                // message in order to make the UI feel more responsive.  (In
                // GSM it's normal to see a delay of a couple of seconds while
                // negotiating the disconnect with the network, so the "Hanging
                // up" state at least lets the user know that we're doing
                // something.  This state is currently not used with CDMA.)
                callStateLabel = context.getString(R.string.card_title_hanging_up);
                break;
            case Call.State.DISCONNECTED:
                callStateLabel = disconnectCause.getLabel();
                if (TextUtils.isEmpty(callStateLabel)) {
                    callStateLabel = context.getString(R.string.card_title_call_ended);
                }
                if (context.getResources().getBoolean(R.bool.def_incallui_clearcode_enabled)) {
                    String clearText = disconnectCause.getDescription() == null ? ""
                            : disconnectCause.getDescription().toString();
                    if (!TextUtils.isEmpty(clearText)) {
                        Toast.makeText(context, clearText, Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            case Call.State.CONFERENCED:
                callStateLabel = context.getString(R.string.card_title_conf_call);
                break;
            default:
                Log.wtf(this, "updateCallStateWidgets: unexpected call: " + state);
        }
        return new CallStateLabel(callStateLabel, isAutoDismissing);
    }

    private void initializeSecondaryCallInfo(boolean hasProvider) {
        // mSecondaryCallName is initialized here (vs. onViewCreated) because it is inaccessible
        // until mSecondaryCallInfo is inflated in the call above.
        if (mSecondaryCallName == null) {
            mSecondaryCallName = (TextView) getView().findViewById(R.id.secondaryCallName);
            mSecondaryCallConferenceCallIcon =
                    getView().findViewById(R.id.secondaryCallConferenceCallIcon);
            mSecondaryCallVideoCallIcon =
                    getView().findViewById(R.id.secondaryCallVideoCallIcon);
        }

        if (mSecondaryCallProviderLabel == null && hasProvider) {
            mSecondaryCallProviderInfo.setVisibility(View.VISIBLE);
            mSecondaryCallProviderLabel = (TextView) getView()
                    .findViewById(R.id.secondaryCallProviderLabel);
        }
    }

    public void dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            dispatchPopulateAccessibilityEvent(event, mCallStateLabel);
            dispatchPopulateAccessibilityEvent(event, mPrimaryName);
            dispatchPopulateAccessibilityEvent(event, mCallTypeLabel);
            dispatchPopulateAccessibilityEvent(event, mPhoneNumber);
            return;
        }
        dispatchPopulateAccessibilityEvent(event, mCallStateLabel);
        dispatchPopulateAccessibilityEvent(event, mPrimaryName);
        dispatchPopulateAccessibilityEvent(event, mPhoneNumber);
        dispatchPopulateAccessibilityEvent(event, mCallTypeLabel);
        dispatchPopulateAccessibilityEvent(event, mSecondaryCallName);
        dispatchPopulateAccessibilityEvent(event, mSecondaryCallProviderLabel);

        return;
    }

    @Override
    public void sendAccessibilityAnnouncement() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getView() != null && getView().getParent() != null) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(
                            AccessibilityEvent.TYPE_ANNOUNCEMENT);
                    dispatchPopulateAccessibilityEvent(event);
                    getView().getParent().requestSendAccessibilityEvent(getView(), event);
                }
            }
        }, ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS);
    }

    @Override
    public void setEndCallButtonEnabled(boolean enabled, boolean animate) {
        if (enabled != mFloatingActionButton.isEnabled()) {
            if (animate) {
                if (enabled) {
                    mFloatingActionButtonController.scaleIn(AnimUtils.NO_DELAY);
                } else {
                    mFloatingActionButtonController.scaleOut();
                }
            } else {
                if (enabled) {
                    mFloatingActionButtonContainer.setScaleX(1);
                    mFloatingActionButtonContainer.setScaleY(1);
                    mFloatingActionButtonContainer.setVisibility(View.VISIBLE);
                } else {
                    mFloatingActionButtonContainer.setVisibility(View.GONE);
                }
            }
            mFloatingActionButton.setEnabled(enabled);
            updateFabPosition();
        }
    }

    /**
     * Changes the visibility of the HD audio icon.
     *
     * @param visible {@code true} if the UI should show the HD audio icon.
     */
    @Override
    public void showHdAudioIndicator(boolean visible) {
        mHdAudioIcon.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Changes the visibility of the forward icon.
     *
     * @param visible {@code true} if the UI should show the forward icon.
     */
    @Override
    public void showForwardIndicator(boolean visible) {
        mForwardIcon.setVisibility(visible ? View.VISIBLE : View.GONE);
    }


    /**
     * Changes the visibility of the "manage conference call" button.
     *
     * @param visible Whether to set the button to be visible or not.
     */
    @Override
    public void showManageConferenceCallButton(boolean visible) {
        mManageConferenceCallButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Determines the current visibility of the manage conference button.
     *
     * @return {@code true} if the button is visible.
     */
    @Override
    public boolean isManageConferenceVisible() {
        return mManageConferenceCallButton.getVisibility() == View.VISIBLE;
    }

    /**
     * Determines the current visibility of the call subject.
     *
     * @return {@code true} if the subject is visible.
     */
    @Override
    public boolean isCallSubjectVisible() {
        return mCallSubject.getVisibility() == View.VISIBLE;
    }

    /**
     * Get the overall InCallUI background colors and apply to call card.
     */
    public void updateColors() {
        MaterialPalette themeColors = InCallPresenter.getInstance().getThemeColors();

        if (mCurrentThemeColors != null && mCurrentThemeColors.equals(themeColors)) {
            return;
        }

        if (getResources().getBoolean(R.bool.is_layout_landscape)) {
            final GradientDrawable drawable =
                    (GradientDrawable) mPrimaryCallCardContainer.getBackground();
            drawable.setColor(themeColors.mPrimaryColor);
        } else {
            mPrimaryCallCardContainer.setBackgroundColor(themeColors.mPrimaryColor);
        }
        mCallButtonsContainer.setBackgroundColor(themeColors.mPrimaryColor);
        mCallSubject.setTextColor(themeColors.mPrimaryColor);

        mCurrentThemeColors = themeColors;
    }

    private void dispatchPopulateAccessibilityEvent(AccessibilityEvent event, View view) {
        if (view == null) return;
        final List<CharSequence> eventText = event.getText();
        int size = eventText.size();
        view.dispatchPopulateAccessibilityEvent(event);
        // if no text added write null to keep relative position
        if (size == eventText.size()) {
            eventText.add(null);
        }
    }

    @Override
    public void animateForNewOutgoingCall() {
        final ViewGroup parent = (ViewGroup) mPrimaryCallCardContainer.getParent();

        final ViewTreeObserver observer = getView().getViewTreeObserver();

        mIsAnimating = true;

        observer.addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final ViewTreeObserver observer = getView().getViewTreeObserver();
                if (!observer.isAlive()) {
                    return;
                }
                observer.removeOnGlobalLayoutListener(this);

                final LayoutIgnoringListener listener = new LayoutIgnoringListener();
                mPrimaryCallCardContainer.addOnLayoutChangeListener(listener);

                // Prepare the state of views before the slide animation
                final int originalHeight = mPrimaryCallCardContainer.getHeight();
                mPrimaryCallCardContainer.setTag(R.id.view_tag_callcard_actual_height,
                        originalHeight);
                mPrimaryCallCardContainer.setBottom(parent.getHeight());

                // Set up FAB.
                mFloatingActionButtonContainer.setVisibility(View.GONE);
                mFloatingActionButtonController.setScreenWidth(parent.getWidth());

                mCallButtonsContainer.setAlpha(0);
                mCallStateLabel.setAlpha(0);
                mPrimaryName.setAlpha(0);
                mCallTypeLabel.setAlpha(0);
                mCallNumberAndLabel.setAlpha(0);

                assignTranslateAnimation(mCallStateLabel, 1);
                assignTranslateAnimation(mCallStateIcon, 1);
                assignTranslateAnimation(mPrimaryName, 2);
                assignTranslateAnimation(mCallNumberAndLabel, 3);
                assignTranslateAnimation(mCallTypeLabel, 4);
                assignTranslateAnimation(mCallButtonsContainer, 5);

                final Animator animator = getShrinkAnimator(parent.getHeight(), originalHeight);

                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPrimaryCallCardContainer.setTag(R.id.view_tag_callcard_actual_height,
                                null);
                        setViewStatePostAnimation(listener);
                        mIsAnimating = false;
                        InCallPresenter.getInstance().onShrinkAnimationComplete();
                    }
                });
                animator.start();
            }
        });
    }

    @Override
    public void showNoteSentToast() {
        Toast.makeText(getContext(), R.string.note_sent, Toast.LENGTH_LONG).show();
    }

    public void onDialpadVisibilityChange(boolean isShown) {
        mIsDialpadShowing = isShown;
        updateFabPosition();
    }

    private void updateFabPosition() {
        int offsetY = 0;
        if (!mIsDialpadShowing) {
            offsetY = mFloatingActionButtonVerticalOffset;
            if (mSecondaryCallInfo.isShown()) {
                offsetY -= mSecondaryCallInfo.getHeight();
            }
        }

        mFloatingActionButtonController.align(
                mIsLandscape ? FloatingActionButtonController.ALIGN_QUARTER_END
                        : FloatingActionButtonController.ALIGN_MIDDLE,
                0 /* offsetX */,
                offsetY,
                true);

        mFloatingActionButtonController.resize(
                mIsDialpadShowing ? mFabSmallDiameter : mFabNormalDiameter, true);
    }

    @Override
    public void onResume() {
        super.onResume();
        // If the previous launch animation is still running, cancel it so that we don't get
        // stuck in an intermediate animation state.
        if (mAnimatorSet != null && mAnimatorSet.isRunning()) {
            mAnimatorSet.cancel();
        }

        mIsLandscape = getResources().getBoolean(R.bool.is_layout_landscape);

        final ViewGroup parent = ((ViewGroup) mPrimaryCallCardContainer.getParent());
        final ViewTreeObserver observer = parent.getViewTreeObserver();
        parent.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver viewTreeObserver = observer;
                if (!viewTreeObserver.isAlive()) {
                    viewTreeObserver = parent.getViewTreeObserver();
                }
                viewTreeObserver.removeOnGlobalLayoutListener(this);
                mFloatingActionButtonController.setScreenWidth(parent.getWidth());
                updateFabPosition();
            }
        });
        /* Begin add for RCS */
        misEhanceScreenApkInstalled = isEnhanceScreenInstalled();
        /* End add for RCS */
        updateColors();
    }

    /**
     * Adds a global layout listener to update the FAB's positioning on the next layout. This allows
     * us to position the FAB after the secondary call info's height has been calculated.
     */
    private void updateFabPositionForSecondaryCallInfo() {
        mSecondaryCallInfo.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        final ViewTreeObserver observer = mSecondaryCallInfo.getViewTreeObserver();
                        if (!observer.isAlive()) {
                            return;
                        }
                        observer.removeOnGlobalLayoutListener(this);

                        onDialpadVisibilityChange(mIsDialpadShowing);
                    }
                });
    }

    /**
     * Animator that performs the upwards shrinking animation of the blue call card scrim.
     * At the start of the animation, each child view is moved downwards by a pre-specified amount
     * and then translated upwards together with the scrim.
     */
    private Animator getShrinkAnimator(int startHeight, int endHeight) {
        final ObjectAnimator shrinkAnimator =
                ObjectAnimator.ofInt(mPrimaryCallCardContainer, "bottom", startHeight, endHeight);
        shrinkAnimator.setDuration(mShrinkAnimationDuration);
        shrinkAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mFloatingActionButton.setEnabled(true);
            }
        });
        shrinkAnimator.setInterpolator(AnimUtils.EASE_IN);
        return shrinkAnimator;
    }

    private void assignTranslateAnimation(View view, int offset) {
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        view.buildLayer();
        view.setTranslationY(mTranslationOffset * offset);
        view.animate().translationY(0).alpha(1).withLayer()
                .setDuration(mShrinkAnimationDuration).setInterpolator(AnimUtils.EASE_IN);
    }

    private void setViewStatePostAnimation(View view) {
        view.setTranslationY(0);
        view.setAlpha(1);
    }

    private void setViewStatePostAnimation(OnLayoutChangeListener layoutChangeListener) {
        setViewStatePostAnimation(mCallButtonsContainer);
        setViewStatePostAnimation(mCallStateLabel);
        setViewStatePostAnimation(mPrimaryName);
        setViewStatePostAnimation(mCallTypeLabel);
        setViewStatePostAnimation(mCallNumberAndLabel);
        setViewStatePostAnimation(mCallStateIcon);

        mPrimaryCallCardContainer.removeOnLayoutChangeListener(layoutChangeListener);

        mFloatingActionButtonController.scaleIn(AnimUtils.NO_DELAY);
    }

    private final class LayoutIgnoringListener implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v,
                int left,
                int top,
                int right,
                int bottom,
                int oldLeft,
                int oldTop,
                int oldRight,
                int oldBottom) {
            v.setLeft(oldLeft);
            v.setRight(oldRight);
            v.setTop(oldTop);
            v.setBottom(oldBottom);
        }
    }

    private OnClickListener mVbListener = new OnClickListener() {
        @Override
        public void onClick(View arg0) {
            if (isVbAvailable()) {
                // Switch Volume Boost status
                setVolumeBoost(!isVolumeBoostOn());
            }

            updateVbButton();
            showVbNotify();
        }
    };

    private boolean isVbAvailable() {
        int mode = AudioModeProvider.getInstance().getAudioMode();
        final Activity activity = getActivity();
        final String PREFERRED_TTY_MODE = "preferred_tty_mode";

        int settingsTtyMode;

        if (activity != null) {
            settingsTtyMode = Settings.Secure.getInt(activity.getContentResolver(),
                                                 PREFERRED_TTY_MODE, TTY_MODE_OFF);
        } else {
            settingsTtyMode = TTY_MODE_OFF;
        }

        return (mode == CallAudioState.ROUTE_EARPIECE || mode == CallAudioState.ROUTE_SPEAKER
                || settingsTtyMode == TTY_MODE_HCO);
    }

    private void updateVbButton() {
        if (isVbAvailable()) {
           if (isVolumeBoostOn()) {
               mVbButton.setBackgroundResource(R.drawable.vb_active);
           } else {
               mVbButton.setBackgroundResource(R.drawable.vb_normal);
           }
        } else {
            mVbButton.setBackgroundResource(R.drawable.vb_disable);
        }
    }

    private void showVbNotify() {
        if (mVbNotify != null) {
            mVbNotify.cancel();
        }

        int resId = R.string.volume_boost_notify_unavailable;

        if (isVbAvailable()) {
            if (isVolumeBoostOn()) {
                resId = R.string.volume_boost_notify_enabled;
            } else {
                resId = R.string.volume_boost_notify_disabled;
            }
        }

        mVbNotify = Toast.makeText(getView().getContext(), resId, Toast.LENGTH_SHORT);
        mVbNotify.setGravity(Gravity.CENTER, 0, 0);
        mVbNotify.show();
    }

    private void updateVbByCall(int state) {
        updateVbButton();

        if (Call.State.ACTIVE == state &&
                getResources().getBoolean(R.bool.config_enable_volume_boost)) {
            mVbButton.setVisibility(View.VISIBLE);
        } else if (Call.State.DISCONNECTED == state) {
            if (!CallList.getInstance().hasLiveCall()
                    && isVolumeBoostOn()) {
                mVbButton.setVisibility(View.INVISIBLE);

                setVolumeBoost(false);
            }
        }
    }

    public void updateVbByAudioMode(int newMode) {
        if (!(newMode == CallAudioState.ROUTE_EARPIECE
                || newMode == CallAudioState.ROUTE_BLUETOOTH
                || newMode == CallAudioState.ROUTE_WIRED_HEADSET
                || newMode == CallAudioState.ROUTE_SPEAKER)) {
            return;
        }

        if (mAudioManager != null && isVolumeBoostOn()) {
            setVolumeBoost(false);
        }

        updateVbButton();
    }

    private void setVolumeBoost(boolean on){
        if (on)
            mAudioManager.setParameters(VOLUME_BOOST + "=on");
        else
            mAudioManager.setParameters(VOLUME_BOOST + "=off");
    }

    private boolean isVolumeBoostOn(){

        return mAudioManager.getParameters(VOLUME_BOOST).contains("=on");
    }

    /* Begin add for RCS */
    private boolean isRcsAvailable() {
        return SupportApi.getInstance().isRcsSupported()
                && isRcsOnLine() && misEhanceScreenApkInstalled;
    }

    private boolean isRcsOnLine() {
        try {
            return BasicApi.getInstance().isOnline();
        } catch (Exception e) {
            RcsLog.w("Exception:" + e);
            return false;
        }
    }
    private boolean isEnhanceScreenInstalled() {
        boolean installed = false;
        try {
            ApplicationInfo info = getActivity().getPackageManager().getApplicationInfo(
                ENHANCE_SCREEN_APK_NAME, PackageManager.GET_PROVIDERS);
            installed = (info != null);
        } catch (NameNotFoundException e) {
            RcsLog.w("NameNotFoundException:" + e);
        }
        return installed;
    }
    /* End add for RCS */
}
