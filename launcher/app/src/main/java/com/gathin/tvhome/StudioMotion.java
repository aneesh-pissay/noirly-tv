package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.Fade;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Studio Home's motion: focus zooms, colour fades, the dock's spring and the popups' fades. Only scale,
 * translation, alpha and colour move, all on the GPU-friendly paths; nothing runs once the D-pad stops.
 * Every animation restarts from where the last one left off, so fast presses never queue up.
 * With animations off in Developer options (or a reduced-motion setting that sets the scale to 0), the
 * ValueAnimator-based ones jump to their end on their own, and the springs are skipped here.
 */
final class StudioMotion {
    /** Durations in ms, Apple TV-like: quick in, a little quicker out. */
    static final int FOCUS_IN = 180, FOCUS_OUT = 150, HERO_IN = 220, TAB = 200, GRID = 180, DOCK = 160,
            POPUP_IN = 220, POPUP_OUT = 150;
    /** Material's fast-out-slow-in, and a plain decelerate for things settling. */
    static final Interpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final Interpolator SETTLE = new PathInterpolator(0f, 0f, 0.2f, 1f);

    private StudioMotion() { }

    /** False when the person has turned animations off. */
    static boolean on(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    /** Scale and lift on focus; the previous zoom is replaced, not queued. */
    static void zoom(View v, boolean in, float scale, float liftPx, int inMs) {
        v.animate().cancel();
        v.animate().scaleX(in ? scale : 1f).scaleY(in ? scale : 1f).translationZ(in ? liftPx : 0f)
                .setDuration(in ? inMs : FOCUS_OUT).setInterpolator(EASE).start();
    }

    /** The dock's zoom: a soft spring that settles with barely a wobble, retargeted mid-flight. */
    static final class Spring {
        private final View v;
        private final SpringAnimation x, y;

        Spring(View v) {
            this.v = v;
            x = make(v, DynamicAnimation.SCALE_X);
            y = make(v, DynamicAnimation.SCALE_Y);
        }

        private static SpringAnimation make(View v, DynamicAnimation.ViewProperty prop) {
            SpringAnimation s = new SpringAnimation(v, prop, 1f);
            s.getSpring().setDampingRatio(0.72f).setStiffness(SpringForce.STIFFNESS_MEDIUM);
            return s;
        }

        void to(float scale, boolean motion) {
            if (!motion) {
                x.cancel();
                y.cancel();
                v.setScaleX(scale);
                v.setScaleY(scale);
                return;
            }
            x.animateToFinalPosition(scale);
            y.animateToFinalPosition(scale);
        }
    }

    /** A glow in the theme's accent under a lifted view (Android 9+ colours its shadow). */
    static void glow(View v, int accent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.setOutlineSpotShadowColor(accent);
            v.setOutlineAmbientShadowColor(accent);
        }
    }

    /** A set of colours that eases to new values, starting from wherever the last fade stopped. */
    static final class Colors {
        interface Apply { void to(int[] colors); }

        private static final ArgbEvaluator ARGB = new ArgbEvaluator();
        private final int[] now;
        private final Apply apply;
        private ValueAnimator anim;

        Colors(Apply apply, int... start) {
            this.apply = apply;
            now = start.clone();
            apply.to(now);
        }

        void to(int ms, int... target) {
            if (anim != null) anim.cancel();
            int[] from = now.clone();
            anim = ValueAnimator.ofFloat(0f, 1f).setDuration(ms);
            anim.setInterpolator(EASE);
            anim.addUpdateListener(va -> {
                float f = va.getAnimatedFraction();
                for (int i = 0; i < now.length; i++) now[i] = (Integer) ARGB.evaluate(f, from[i], target[i]);
                apply.to(now);
            });
            anim.start();
        }
    }

    /** Fades a popup in and out instead of popping it. */
    static void popup(PopupWindow w) {
        Fade in = new Fade(Fade.IN);
        in.setDuration(POPUP_IN);
        in.setInterpolator(SETTLE);
        Fade out = new Fade(Fade.OUT);
        out.setDuration(POPUP_OUT);
        w.setEnterTransition(in);
        w.setExitTransition(out);
    }

    /** After a popup shows: its panel drops the last few dp into place as it fades in. */
    static void drop(View panel, float px) {
        panel.setTranslationY(-px);
        panel.setScaleY(0.97f);
        panel.setPivotY(0);
        panel.animate().translationY(0).scaleY(1f).setDuration(POPUP_IN).setInterpolator(SETTLE).start();
    }

    /** New content, such as the grid after a tab change, slides up a touch as it fades in. */
    static void reveal(View v, float px, int ms) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(px);
        v.animate().alpha(1f).translationY(0).setDuration(ms).setInterpolator(SETTLE).withLayer().start();
    }

    /** A brief dip when a text changes (the clock's minute, the weather), so it does not just snap. */
    static void softText(TextView t) {
        t.addTextChangedListener(new TextWatcher() {
            private CharSequence before;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { before = s.toString(); }

            @Override
            public void onTextChanged(CharSequence s, int start, int b, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (before == null || before.length() == 0 || before.toString().contentEquals(s) || !t.isShown()) return;
                t.setAlpha(0.35f);
                t.animate().alpha(1f).setDuration(GRID * 2).setInterpolator(SETTLE).start();
            }
        });
    }
}
