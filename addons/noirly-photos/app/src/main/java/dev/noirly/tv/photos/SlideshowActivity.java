package dev.noirly.tv.photos;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** The slideshow full screen from the app: Left and Right step through it, Back leaves. */
public final class SlideshowActivity extends Activity {
    private Slideshow show;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show = new Slideshow(this);
        setContentView(show);
    }

    @Override
    protected void onResume() {
        super.onResume();
        show.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        show.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        show.release();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            show.skip(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            show.skip(-1);
            return true;
        }
        return super.onKeyDown(code, event);
    }
}
