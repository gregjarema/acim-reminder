package com.acimreminder.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * A WebView that keeps its video playing when the phone is locked.
 *
 * By default a WebView pauses HTML5 media the instant its window stops being
 * visible — which is exactly what happens when the screen turns off. Because
 * these sessions can only play on Vimeo's own page inside a WebView (there's no
 * stream to hand to a real media player), that default silences Marianne the
 * moment you lock the phone, even with the foreground media service running.
 *
 * So we lie to the WebView about visibility: unless the view is genuinely being
 * removed (GONE), we report it as still VISIBLE. The framework's INVISIBLE on
 * screen-off never reaches the media layer, so the audio keeps going. A real
 * GONE — switching tabs tears the player down, closing the app removes it — is
 * still passed through, so nothing keeps playing when it truly shouldn't.
 */
public class BackgroundWebView extends WebView {

    public BackgroundWebView(Context context) {
        super(context);
    }

    public BackgroundWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackgroundWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility == View.GONE) {
            super.onWindowVisibilityChanged(visibility);
        } else {
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }
}
