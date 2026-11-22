package com.acimreminder.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The page Health Connect opens when you ask why this app wants access.
 * Android won't grant a health permission to an app that doesn't have one.
 */
public class HealthRationaleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);

        TextView text = new TextView(this);
        text.setPadding(pad, pad * 2, pad, pad);
        text.setTextSize(17);
        text.setText("Health Connect access\n\n"
                + "A Course in Miracles only writes to Health Connect. It never reads "
                + "anything from it.\n\n"
                + "When you turn on \"Save sittings to Health Connect\", each Workbook "
                + "sitting is saved as a meditation session: the time you started, the "
                + "time you finished, and the lesson. That is what counts towards your "
                + "mindful minutes.\n\n"
                + "Nothing is sent anywhere else. You can turn it off in the app's menu, "
                + "or take the access away in Health Connect, at any time.");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
