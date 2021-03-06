/*
 * Copyright (C) 2015 Google Inc. All Rights Reserved.
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

package com.google.android.apps.santatracker.dasherdancer;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.LruCache;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.View;
import android.widget.ImageView;

import com.google.android.apps.santatracker.games.PlayGamesFragment;
import com.google.android.apps.santatracker.games.SignInListener;
import com.google.android.apps.santatracker.util.AnalyticsManager;
import com.google.android.apps.santatracker.util.ImmersiveModeHelper;
import com.google.android.apps.santatracker.util.MeasurementManager;
import com.google.android.gms.games.Games;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.squareup.seismic.ShakeDetector;
import com.squareup.seismic.ShakeDetector.Listener;

import java.util.HashSet;

public class DasherDancerActivity extends FragmentActivity implements
        OnGestureListener, OnScaleGestureListener, Handler.Callback, Listener, SensorEventListener,
        AnimatorListener, OnPageChangeListener, SignInListener {

    /**
     * Extra key used to pass back the character id that should be selected, set by the CharacterActivity.
     */
    public static final String EXTRA_CHARACTER_ID = "extra_character_id";

    //Character ids, which are also indices in the sCharacters array.
    public static final int CHARACTER_ID_SANTA = 0;
    public static final int CHARACTER_ID_ELF = 1;
    public static final int CHARACTER_ID_REINDEER = 2;
    public static final int CHARACTER_ID_SNOWMAN = 3;

    /**
     * Request code for calling CharacterActivity for result.
     */
    private static final int sCharacterRequestCode = 1;

    /**
     * Our array of playable characters.  Add more characters here an create new CHARACTER_ID_* static variables.
     */
    private static final Character[] sCharacters = new Character[]{
            new Santa(), new Elf(), new Reindeer(), new Snowman()
    };

    private boolean[] mCharacterInitialized = new boolean[] {
            false, false, false, false
    };

    private int[][] mSoundIds = new int[][]{
            {-1,-1,-1,-1,-1,-1,-1,-1,-1}, //santa
            {-1,-1,-1,-1,-1,-1,-1,-1,-1}, //elf
            {-1,-1,-1,-1,-1,-1,-1,-1,-1}, //reindeer
            {-1,-1,-1,-1,-1,-1,-1,-1,-1} //snowman
    };

    private LruCache<Integer, Bitmap> mMemoryCache;
    private NoSwipeViewPager mPager;
    private Handler mHandler;
    private ShakeDetector mDetector;
    private LoadBitmapsTask mLoadBitmapsTask;
    private LoadAllBitmapsTask mLoadAllBitmapsTask;
    private ObjectAnimator mAnimator;
    private boolean mPlayingRest = false;
    private boolean mAnimCanceled = false;
    private boolean mAnimPlaying = false;
    private boolean mScaling = false;
    private boolean mInitialized = false;
    private SoundPool mSoundPool;
    private int mSoundId = -1;
    private boolean mCanTouch = false;
    private ObjectAnimator mProgressAnimator;
    private ActivityManager mActivityManager;
    private FirebaseAnalytics mMeasurement;

    private PlayGamesFragment mGamesFragment;

    // For achievements
    private HashSet<Integer>[] mAchievements;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_dasher_dancer);

        mMeasurement = FirebaseAnalytics.getInstance(this);
        MeasurementManager.recordScreenView(mMeasurement,
                getString(R.string.analytics_screen_dasher));

        AnalyticsManager.initializeAnalyticsTracker(this);
        AnalyticsManager.sendScreenView(R.string.analytics_screen_dasher);

        mActivityManager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);

        mMemoryCache = new LruCache<Integer, Bitmap>(240) {
            protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
                if ((oldValue != null) && (oldValue != newValue)) {
                    oldValue.recycle();
                    oldValue = null;
                }
            }
        };

        CharacterAdapter adapter = new CharacterAdapter(sCharacters);
        mPager = (NoSwipeViewPager) findViewById(R.id.character_pager);
        mPager.setAdapter(adapter);
        mPager.setGestureDetectorListeners(this, this, this);
        mPager.setOnPageChangeListener(this);

        mHandler = new Handler(getMainLooper(), this);

        mDetector = new ShakeDetector(this);

        mSoundPool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
        mSoundIds[0][Character.ANIM_PINCH_IN] = mSoundPool.load(this, R.raw.santa_pinchin, 1);
        mSoundIds[0][Character.ANIM_PINCH_OUT] = mSoundPool.load(this, R.raw.santa_pinchout, 1);
        mSoundIds[0][Character.ANIM_SHAKE] = mSoundPool.load(this, R.raw.santa_shake, 1);
        mSoundIds[0][Character.ANIM_SWIPE_UP] = mSoundPool.load(this, R.raw.santa_swipeup, 1);
        mSoundIds[0][Character.ANIM_SWIPE_LEFT] = mSoundPool.load(this, R.raw.santa_swipeleft, 1);
        mSoundIds[0][Character.ANIM_SWIPE_RIGHT] = mSoundPool.load(this, R.raw.santa_swiperight, 1);
        mSoundIds[0][Character.ANIM_SWIPE_DOWN] = mSoundPool.load(this, R.raw.santa_swipedown, 1);
        mSoundIds[0][Character.ANIM_TAP] = mSoundPool.load(this, R.raw.santa_tap, 1);
        mSoundIds[1][Character.ANIM_PINCH_IN] = mSoundPool.load(this, R.raw.elf_pinchin_ball, 1);
        mSoundIds[1][Character.ANIM_PINCH_OUT] = mSoundPool.load(this, R.raw.elf_pinchout, 1);
        mSoundIds[1][Character.ANIM_SHAKE] = mSoundPool.load(this, R.raw.elf_shake2, 1);
        mSoundIds[1][Character.ANIM_SWIPE_DOWN] = mSoundPool.load(this, R.raw.elf_swipedown2, 1);
        mSoundIds[1][Character.ANIM_SWIPE_UP] = mSoundPool.load(this, R.raw.elf_swipeup2, 1);
        mSoundIds[1][Character.ANIM_SWIPE_LEFT] = mSoundPool.load(this, R.raw.elf_swipeleft, 1);
        mSoundIds[1][Character.ANIM_SWIPE_RIGHT] = mSoundPool.load(this, R.raw.elf_swiperight, 1);
        mSoundIds[1][Character.ANIM_TAP] = mSoundPool.load(this, R.raw.elf_tap3, 1);
        mSoundIds[2][Character.ANIM_PINCH_IN] = mSoundPool.load(this, R.raw.reindeer_pinchin, 1);
        mSoundIds[2][Character.ANIM_PINCH_OUT] = mSoundPool.load(this, R.raw.reindeer_pinchout, 1);
        mSoundIds[2][Character.ANIM_SHAKE] = mSoundPool.load(this, R.raw.reindeer_shake, 1);
        mSoundIds[2][Character.ANIM_SWIPE_UP] = mSoundPool.load(this, R.raw.reindeer_swipeup, 1);
        mSoundIds[2][Character.ANIM_SWIPE_DOWN] = mSoundPool.load(this, R.raw.reindeer_swipedown, 1);
        mSoundIds[2][Character.ANIM_SWIPE_LEFT] = mSoundPool.load(this, R.raw.reindeer_swipeleft, 1);
        mSoundIds[2][Character.ANIM_SWIPE_RIGHT] = mSoundPool.load(this, R.raw.reindeer_swiperight, 1);
        mSoundIds[2][Character.ANIM_TAP] = mSoundPool.load(this, R.raw.reindeer_tap2, 1);
        mSoundIds[3][Character.ANIM_PINCH_IN] = mSoundPool.load(this, R.raw.snowman_pinchin, 1);
        mSoundIds[3][Character.ANIM_PINCH_OUT] = mSoundPool.load(this, R.raw.snowman_pinchout, 1);
        mSoundIds[3][Character.ANIM_SHAKE] = mSoundPool.load(this, R.raw.snowman_shake, 1);
        mSoundIds[3][Character.ANIM_SWIPE_UP] = mSoundPool.load(this, R.raw.snowman_swipeup, 1);
        mSoundIds[3][Character.ANIM_SWIPE_DOWN] = mSoundPool.load(this, R.raw.snowman_swipedown, 1);
        mSoundIds[3][Character.ANIM_SWIPE_LEFT] = mSoundPool.load(this, R.raw.snowman_swipeleft, 1);
        mSoundIds[3][Character.ANIM_SWIPE_RIGHT] = mSoundPool.load(this, R.raw.snowman_swiperight, 1);
        mSoundIds[3][Character.ANIM_TAP] = mSoundPool.load(this, R.raw.snowman_tap, 1);

        mAchievements = new HashSet[4];
        mAchievements[0] = new HashSet<Integer>();
        mAchievements[1] = new HashSet<Integer>();
        mAchievements[2] = new HashSet<Integer>();
        mAchievements[3] = new HashSet<Integer>();

        mProgressAnimator = ObjectAnimator.ofFloat(findViewById(R.id.progress),"rotation",360f);
        mProgressAnimator.setDuration(4000);
        mProgressAnimator.start();

        mGamesFragment = PlayGamesFragment.getInstance(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ImmersiveModeHelper.setImmersiveSticky(getWindow());
            ImmersiveModeHelper.installSystemUiVisibilityChangeListener(getWindow());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        SensorManager manager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
        mDetector.start(manager);

        if(mInitialized) {
            //Start the animation for the first character.
            mPager.postDelayed(new Runnable() {

                @Override
                public void run() {
                    loadAnimation(true,
                            sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_IDLE),
                            sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_IDLE),
                            sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_IDLE));
                }

            }, 300);
        }
        else {
            if(mLoadAllBitmapsTask != null) {
                mLoadAllBitmapsTask.cancel(true);
            }
            mLoadAllBitmapsTask = new LoadAllBitmapsTask();
            mLoadAllBitmapsTask.execute(sCharacters[mPager.getCurrentItem()]);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        mDetector.stop();

        SensorManager manager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        manager.unregisterListener(this);;

        if(mAnimator != null) {
            mAnimator.cancel();
        }
        FrameAnimationView character = (FrameAnimationView) mPager.findViewWithTag(mPager.getCurrentItem());
        if (character != null) {
            character.setImageDrawable(null);
        }
    }

    /**
     * Finishes the activity.
     * @param view
     */
    public void onNavClick(View view) {
        if(mLoadBitmapsTask != null) {
            mLoadBitmapsTask.cancel(true);
        }
        if(mAnimator != null) {
            mAnimator.cancel();
        }
        finish();
    }

    /**
     * Starts the CharacterActivity for result.  That result is an integer that corresponds to 
     * the index of an entry in sCharacters.
     * @param view
     */
    public void onChangeClick(View view) {
        if(mLoadBitmapsTask != null) {
            mLoadBitmapsTask.cancel(true);
        }
        if(mLoadAllBitmapsTask != null) {
            mLoadAllBitmapsTask.cancel(true);
        }
        if(mAnimator != null) {
            mAnimator.cancel();
        }
        FrameAnimationView character = (FrameAnimationView) mPager.findViewWithTag(mPager.getCurrentItem());
        character.setImageDrawable(null);
        character.setFrames(null, null);
        character.invalidate();
        Intent intent = new Intent(this, CharacterActivity.class);
        startActivityForResult(intent, sCharacterRequestCode);
    }

    /**
     * Moves the view pager to the next character to the left of the current position.
     */
    public void onLeftClick(View view) {
        final int currentPosition = mPager.getCurrentItem();
        if(currentPosition != 0) {
            characterSelectedHelper(currentPosition - 1, true);
        }
    }

    /**
     * Moves the view pager to the next character to the right of the current position.
     */
    public void onRightClick(View view) {
        final int currentPosition = mPager.getCurrentItem();
        if(currentPosition != mPager.getAdapter().getCount()-1) {
            characterSelectedHelper(currentPosition + 1, true);
        }
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {
        //Ignore this.
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        if(!mAnimPlaying) {
            mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_TAP];
        }

        AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                sCharacters[mPager.getCurrentItem()].getCharacterName(),
                getString(R.string.analytics_action_tap));

        updateGestureAchievements(Character.ANIM_TAP);
        loadAnimation(false,
                sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_TAP),
                sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_TAP),
                sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_TAP));
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                            float distanceY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        //Ignore
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                           float velocityY) {
        float xDelta = Math.abs(e1.getX() - e2.getX());
        float yDelta = Math.abs(e1.getY() - e2.getY());
        if(xDelta > yDelta) {
            //Moving side to side.
            if(e1.getX() > e2.getX()) {
                //Moving left.
                if(!mAnimPlaying) {
                    mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_SWIPE_LEFT];
                }
                AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                        sCharacters[mPager.getCurrentItem()].getCharacterName(),
                        getString(R.string.analytics_action_swipe_left));
                updateGestureAchievements(Character.ANIM_SWIPE_LEFT);
                loadAnimation(false,
                        sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_SWIPE_LEFT),
                        sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_SWIPE_LEFT),
                        sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_SWIPE_LEFT));
            }
            else if(e2.getX() > e1.getX()) {
                //Moving right.
                if(!mAnimPlaying) {
                    mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_SWIPE_RIGHT];
                }
                AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                        sCharacters[mPager.getCurrentItem()].getCharacterName(),
                        getString(R.string.analytics_action_swipe_right));
                updateGestureAchievements(Character.ANIM_SWIPE_RIGHT);
                loadAnimation(false,
                        sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_SWIPE_RIGHT),
                        sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_SWIPE_RIGHT),
                        sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_SWIPE_RIGHT));
            }
        }
        else {
            //We are moving up and down
            if(e1.getY() > e2.getY()) {
                //Moving up.
                if(!mAnimPlaying) {
                    mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_SWIPE_UP];
                }
                AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                        sCharacters[mPager.getCurrentItem()].getCharacterName(),
                        getString(R.string.analytics_action_swipe_up));
                updateGestureAchievements(Character.ANIM_SWIPE_UP);
                loadAnimation(false,
                        sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_SWIPE_UP),
                        sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_SWIPE_UP),
                        sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_SWIPE_UP));
            }
            else if(e2.getY() > e1.getY()) {
                //Moving down.
                if(!mAnimPlaying) {
                    mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_SWIPE_DOWN];
                }
                AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                        sCharacters[mPager.getCurrentItem()].getCharacterName(),
                        getString(R.string.analytics_action_swipe_down));
                updateGestureAchievements(Character.ANIM_SWIPE_DOWN);
                loadAnimation(false,
                        sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_SWIPE_DOWN),
                        sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_SWIPE_DOWN),
                        sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_SWIPE_DOWN));
            }
        }
        return false;
    }

    @Override
    public boolean handleMessage(Message msg) {
        loadAnimation(true,
                sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_IDLE),
                sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_IDLE),
                sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_IDLE));
        return true;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {

        return false;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        mScaling = true;
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        mScaling = false;
        if(detector.getScaleFactor() > 1) {
            //Pinch in
            if(!mAnimPlaying) {
                mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_PINCH_IN];
            }
            AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                    sCharacters[mPager.getCurrentItem()].getCharacterName(),
                    getString(R.string.analytics_action_pinch_in));
            updateGestureAchievements(Character.ANIM_PINCH_IN);
            loadAnimation(false,
                    sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_PINCH_IN),
                    sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_PINCH_IN),
                    sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_PINCH_IN));
        }
        else if(detector.getScaleFactor() < 1) {
            //Pinch out
            if(!mAnimPlaying) {
                mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_PINCH_OUT];
            }
            AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                    sCharacters[mPager.getCurrentItem()].getCharacterName(),
                    getString(R.string.analytics_action_pinch_out));
            updateGestureAchievements(Character.ANIM_PINCH_OUT);
            loadAnimation(false,
                    sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_PINCH_OUT),
                    sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_PINCH_OUT),
                    sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_PINCH_OUT));
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        //Ignore this.
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        //Ignore this.
    }

    @Override
    public void hearShake() {
        if(!mAnimPlaying) {
            mSoundId = mSoundIds[mPager.getCurrentItem()][Character.ANIM_SHAKE];
        }
        AnalyticsManager.sendEvent(getString(R.string.analytics_category_interaction),
                sCharacters[mPager.getCurrentItem()].getCharacterName(),
                getString(R.string.analytics_action_shake));
        updateGestureAchievements(Character.ANIM_SHAKE);
        loadAnimation(false,
                sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_SHAKE),
                sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_SHAKE),
                sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_SHAKE));
    }

    /**
     * Helper method to load and start animations. Takes care of canceling any ongoing animations,
     * and will return without executing anything if mAnimPlaying is true or mScaling is true.
     * @param playingRest
     * @param animationTime
     * @param frameIndices
     * @param frameResourceIds
     */
    private void loadAnimation(boolean playingRest, long animationTime, int[] frameIndices, int[] frameResourceIds) {
        if((!playingRest && (mAnimPlaying || mScaling)) || !mCanTouch) {
            return;
        }
        if(playingRest) {
            mAnimPlaying = false;
        }
        else {
            mAnimPlaying = true;
        }
        mPlayingRest = playingRest;
        if(mLoadBitmapsTask != null) {
            mLoadBitmapsTask.cancel(true);
            mAnimator.cancel();
        }
        LoadBitmapsTask task = new LoadBitmapsTask(animationTime, frameIndices, frameResourceIds);
        task.execute();
    }

    @Override
    public void onSignInFailed() {

    }

    @Override
    public void onSignInSucceeded() {

    }

    private class LoadAllBitmapsTask extends AsyncTask<Character, Void, Void> {

        final BitmapFactory.Options mOptions = new BitmapFactory.Options();

        @Override
        protected Void doInBackground(Character... params) {
            mCanTouch = false;
            //See if we can free up any memory before we allocate some ourselves.
            //Request garbage collection.
            System.gc();
            Character c = params[0];

            mOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            mOptions.inSampleSize = getResources().getInteger(R.integer.res);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (mActivityManager.isLowRamDevice()) {
                    mOptions.inSampleSize *= 2;
                }
            }

                for (int resourceId : c.getFrames(Character.ANIM_IDLE)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_TAP)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_SHAKE)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_SWIPE_UP)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_SWIPE_DOWN)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_SWIPE_LEFT)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_SWIPE_RIGHT)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_PINCH_IN)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }
                for (int resourceId : c.getFrames(Character.ANIM_PINCH_OUT)) {
                    if(isCancelled()) {
                        break;
                    }
                    loadBitmapHelper(resourceId);
                }

            return null;
        }

        private void loadBitmapHelper(int resourceId) {
            if(mMemoryCache.get(resourceId) == null) {
                mMemoryCache.put(resourceId, BitmapFactory.decodeResource(
                        DasherDancerActivity.this.getResources(),
                        resourceId,
                        mOptions));
                if (isCancelled()) {
                    // Remove the BMP we just added
                    // The check and remove should be atomic so we synchronize
                    // (There could be an evict going on so make sure it's still there...
                    synchronized(mMemoryCache) {
                        if (mMemoryCache.get(resourceId) != null) {
                            mMemoryCache.remove(resourceId);
                        }
                    }
                }
            }
        }

        @Override
        public void onPostExecute(Void result) {
            if(isCancelled()) {
                return;
            }

            findViewById(R.id.progress).setVisibility(View.GONE);

            Bitmap[] frames = new Bitmap[sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_IDLE).length];
            for(int i=0; i<frames.length; i++) {
                frames[i] = mMemoryCache.get(sCharacters[mPager.getCurrentItem()].getFrames(Character.ANIM_IDLE)[i]);
            }

            FrameAnimationView character = (FrameAnimationView) mPager.findViewWithTag(mPager.getCurrentItem());
            character.setFrames(frames, sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_IDLE));

            mPlayingRest = true;
            mAnimator = ObjectAnimator.ofInt(character, "frameIndex", 0,
                    sCharacters[mPager.getCurrentItem()].getFrameIndices(Character.ANIM_IDLE).length-1);
            mAnimator.setDuration(sCharacters[mPager.getCurrentItem()].getDuration(Character.ANIM_IDLE));
            mAnimator.addListener(DasherDancerActivity.this);
            mAnimator.start();
            mInitialized = true;
            mCanTouch = true;
        }
    }

    /**
     * AsyncTask that loads bitmaps for animation and starts the animation upon completion.
     */
    private class LoadBitmapsTask extends AsyncTask<Void, Void, Bitmap[]> {

        private int[] mFrames;
        private int[] mFrameIndices;
        private long mDuration;

        public LoadBitmapsTask(long duration, int[] frameIndices, int[] frames) {
            mFrameIndices = frameIndices;
            mDuration = duration;
            mFrames = frames;
        }

        @Override
        protected Bitmap[] doInBackground(Void... params) {
            Bitmap[] bitmaps = new Bitmap[mFrames.length];
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = getResources().getInteger(R.integer.res);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (mActivityManager.isLowRamDevice()) {
                    options.inSampleSize *= 2;
                }
            }

            for(int i=0; i<mFrames.length && !isCancelled(); i++) {
                int id = mFrames[i];
                if(mMemoryCache.get(id) == null) {
                    bitmaps[i] = BitmapFactory.decodeResource(
                            DasherDancerActivity.this.getResources(),
                            id,
                            options);
                    mMemoryCache.put(id, bitmaps[i]);
                    if (isCancelled()) {
                        synchronized (mMemoryCache) {
                            if (mMemoryCache.get(id) != null) {
                                mMemoryCache.remove(id);
                            }
                        }
                    }
                }
                else {
                    bitmaps[i] = mMemoryCache.get(mFrames[i]);
                }
            }
            return bitmaps;
        }

        @Override
        public void onPostExecute(Bitmap[] result) {
            if(result == null || isCancelled()) {
                return;
            }
            FrameAnimationView character = (FrameAnimationView) mPager.findViewWithTag(mPager.getCurrentItem());
            character.setFrames(result, mFrameIndices);
            if(mAnimator != null) {
                mAnimator.cancel();
            }
            mAnimator = ObjectAnimator.ofInt(character, "frameIndex", 0, mFrameIndices.length-1);
            mAnimator.setDuration(mDuration);
            mAnimator.addListener(DasherDancerActivity.this);
            mAnimator.start();
            if(mSoundId != -1) {
                mSoundPool.play(mSoundId,1f,1f,0,0,1);
                mSoundId = -1;
            }
        }

    }

    @Override
    public void onAnimationStart(Animator animation) {
        mAnimCanceled = false;
        if(!mPlayingRest) {
            mAnimPlaying = true;
        }
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        if(mAnimCanceled) {
            return;
        }
        mAnimPlaying = false; //yoda
        if(mPlayingRest) {
            //We are at rest, so play the idle animation again.
            FrameAnimationView character = (FrameAnimationView) mPager.findViewWithTag(mPager.getCurrentItem());
            mAnimator = ObjectAnimator.ofInt(
                    character,
                    "frameIndex",
                    0, sCharacters[0].getFrameIndices(Character.ANIM_IDLE).length);
            mAnimator.setDuration(sCharacters[0].getDuration(Character.ANIM_IDLE));
            mAnimator.addListener(DasherDancerActivity.this);
            mAnimator.start();
        }
        else {
            //We finished an animation triggered by a gesture, so start the idle animation again.
            mHandler.sendEmptyMessage(1);
        }
    }

    @Override
    public void onAnimationCancel(Animator animation) {
        mAnimCanceled = true;
        mAnimPlaying = false;
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
        //Ignore
    }

    @Override
    public void onPageScrollStateChanged(int arg0) {
        //Ignore
    }

    @Override
    public void onPageScrolled(int arg0, float arg1, int arg2) {
        //Ignore
    }

    @Override
    public void onPageSelected(int arg0) {
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == sCharacterRequestCode) {
            if (resultCode == RESULT_OK) {
                mInitialized = false;
                if (mPager != null) {
                  FrameAnimationView character = (FrameAnimationView) mPager.findViewWithTag(mPager.getCurrentItem());
                  character.setImageDrawable(null);
                }
                if (data.getExtras() != null) {
                  //Based on the character id returned, move the view pager to that character.
                  final int position = data.getExtras().getInt(EXTRA_CHARACTER_ID);
                  characterSelectedHelper(position, false);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        //If we are backing out of the game, clear the cache to free memory.
        mSoundPool.release();
        mMemoryCache.evictAll();
        //Request garbage collection.
        System.gc();
        super.onBackPressed();
    }

    @Override
    public void onDestroy() {
        mSoundPool.release();
        mMemoryCache.evictAll();
        //Request garbage collection.
        System.gc();
        super.onDestroy();
    }

    private void characterSelectedHelper(final int position, final boolean smoothScroll) {
        if(mLoadBitmapsTask != null) {
            mLoadBitmapsTask.cancel(true);
        }
        if(mLoadAllBitmapsTask != null) {
            mLoadAllBitmapsTask.cancel(true);
        }
        if(mAnimator != null) {
            mAnimator.cancel();
        }

        if(position == 0) {
            findViewById(R.id.left_button).setVisibility(View.GONE);
            findViewById(R.id.right_button).setVisibility(View.VISIBLE);
        }
        else if(position+1 == mPager.getAdapter().getCount()) {
            findViewById(R.id.right_button).setVisibility(View.GONE);
            findViewById(R.id.left_button).setVisibility(View.VISIBLE);
        }
        else {
            findViewById(R.id.left_button).setVisibility(View.VISIBLE);
            findViewById(R.id.right_button).setVisibility(View.VISIBLE);
        }

        AnalyticsManager.sendEvent(R.string.analytics_category_character,
                R.string.analytics_action_character_change,
                sCharacters[position].getCharacterName());

        mPager.postDelayed(new Runnable() {
            @Override
            public void run() {
                //Show progress
                mPager.setCurrentItem(position, smoothScroll);
                findViewById(R.id.progress).setVisibility(View.VISIBLE);
                mProgressAnimator.start();
                ((ImageView) mPager.findViewWithTag(mPager.getCurrentItem())).setImageDrawable(null);
                mMemoryCache.evictAll();
                //Request garbage collection.
                System.gc();
                if (mLoadAllBitmapsTask != null) {
                    mLoadAllBitmapsTask.cancel(true);
                }
                mLoadAllBitmapsTask = new LoadAllBitmapsTask();
                mLoadAllBitmapsTask.execute(sCharacters[position]);
            }
        }, 100);
    }

    private void updateGestureAchievements(int type) {
        int character = mPager.getCurrentItem();
        mAchievements[character].add(type);
        if (mAchievements[character].size() == 8) {
            if (mGamesFragment.isSignedIn()) {
                if (character == CHARACTER_ID_SANTA) {
                    Games.Achievements.unlock(mGamesFragment.getGamesApiClient(), getString(R.string.achievement_santas_dance_party));
                    MeasurementManager.recordAchievement(mMeasurement,
                            getString(R.string.achievement_santas_dance_party),
                            getString(R.string.analytics_screen_dasher));
                } else if (character == CHARACTER_ID_ELF) {
                    Games.Achievements.unlock(mGamesFragment.getGamesApiClient(), getString(R.string.achievement_elfs_dance_party));
                    MeasurementManager.recordAchievement(mMeasurement,
                            getString(R.string.achievement_elfs_dance_party),
                            getString(R.string.analytics_screen_dasher));
                } else if (character == CHARACTER_ID_REINDEER) {
                    Games.Achievements.unlock(mGamesFragment.getGamesApiClient(), getString(R.string.achievement_rudolphs_dance_party));
                    MeasurementManager.recordAchievement(mMeasurement,
                            getString(R.string.achievement_rudolphs_dance_party),
                            getString(R.string.analytics_screen_dasher));
                } else if (character == CHARACTER_ID_SNOWMAN) {
                    Games.Achievements.unlock(mGamesFragment.getGamesApiClient(), getString(R.string.achievement_snowmans_dance_party));
                    MeasurementManager.recordAchievement(mMeasurement,
                            getString(R.string.achievement_snowmans_dance_party),
                            getString(R.string.analytics_screen_dasher));
                }
            }
        }
    }
}
