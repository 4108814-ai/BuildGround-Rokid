package com.buildground.nexus;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.parseColor("#1B1B1B"));

        TextView title = new TextView(this);
        title.setText("BUILDGROUND NEXUS");
        title.setTextColor(Color.parseColor("#FF7A00"));
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);

        TextView status = new TextView(this);
        status.setText("Independent Core\nOffline by default\nBuildGround trust only");
        status.setTextColor(Color.parseColor("#F5F5F5"));
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 28, 0, 0);

        root.addView(title);
        root.addView(status);
        setContentView(root);
    }
}
