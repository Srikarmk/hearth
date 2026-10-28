package com.gathin.tvhome;

import android.os.Bundle;

/**
 * The Fireplace tile: the clock screensaver as the fireplace clip, with its crackle, opened on demand. The scene
 * holds the screen on for a few hours so the TV's own screensaver does not cover it. Any key returns to Home.
 */
public class FireplaceActivity extends ClockActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scene.useFireplace(this, true);
    }
}
