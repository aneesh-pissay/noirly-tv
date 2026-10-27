package dev.noirly.tv.photos;

import android.service.dreams.DreamService;

/** The slideshow as the TV's screensaver. It reads only the photos already on the TV: no network, no sign-in. */
public final class PhotosDream extends DreamService {
    private Slideshow show;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        show = new Slideshow(this);
        setContentView(show);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        show.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        show.stop();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (show != null) show.release();
    }
}
