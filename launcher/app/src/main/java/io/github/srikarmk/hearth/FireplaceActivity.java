package io.github.srikarmk.hearth;

import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The Ambience tile: the clock over a scene (fire, rain, aquarium, snow, ocean) with its sound, opened on demand.
 * Left and Right change the scene; any other key returns to Home. The scene holds the screen on for a few hours so
 * the TV's own screensaver does not cover it.
 */
public class FireplaceActivity extends ClockActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scene.useFireplace(this, true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scene.nextScene(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
