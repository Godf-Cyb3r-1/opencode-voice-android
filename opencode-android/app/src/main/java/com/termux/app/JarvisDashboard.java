package com.termux.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Voice-first assistant surface; no chat composer or question-answer transcript. */
final class JarvisDashboard extends FrameLayout {
    interface Actions {
        void toggleListening();
        void openConsole();
        void openSettings();
        void stopSpeaking();
    }
    private final TextView state;
    private final TextView activity;
    private final TextView subtitle;
    private final Orb orb;
    private final Button speakButton;
    private final int accent = Color.rgb(56, 220, 242);
    private boolean listening;

    JarvisDashboard(Context context, Actions actions) {
        super(context);
        setBackgroundColor(Color.rgb(5, 12, 24));
        LinearLayout stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);
        stack.setPadding(dp(22), dp(24), dp(22), dp(20));
        addView(stack, new FrameLayout.LayoutParams(-1, -1));

        TextView heading = new TextView(context);
        heading.setText("J A R V I S");
        heading.setTextSize(26);
        heading.setLetterSpacing(0.26f);
        heading.setTextColor(accent);
        heading.setGravity(Gravity.CENTER);
        stack.addView(heading, new LinearLayout.LayoutParams(-1, dp(50)));

        subtitle = new TextView(context);
        subtitle.setText("PERSONAL INTELLIGENCE SYSTEM");
        subtitle.setTextColor(Color.rgb(126, 161, 190));
        subtitle.setTextSize(10);
        subtitle.setLetterSpacing(0.14f);
        subtitle.setGravity(Gravity.CENTER);
        stack.addView(subtitle);

        orb = new Orb(context);
        LinearLayout.LayoutParams orbParams = new LinearLayout.LayoutParams(dp(260), dp(260));
        orbParams.topMargin = dp(20);
        stack.addView(orb, orbParams);
        orb.setContentDescription("Jarvis voice status");
        orb.setOnClickListener(v -> actions.toggleListening());

        state = new TextView(context);
        state.setText("STANDBY");
        state.setTextColor(accent);
        state.setGravity(Gravity.CENTER);
        state.setTextSize(20);
        state.setLetterSpacing(0.12f);
        stack.addView(state, new LinearLayout.LayoutParams(-1, dp(40)));

        TextView hint = new TextView(context);
        hint.setText("Tap the core to begin a voice session");
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(Color.rgb(154, 176, 191));
        hint.setTextSize(12);
        stack.addView(hint);

        speakButton = new Button(context);
        speakButton.setText("ACTIVATE VOICE");
        speakButton.setContentDescription("Activate or stop Jarvis voice conversation");
        speakButton.setTextColor(Color.BLACK);
        speakButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(52));
        buttonParams.topMargin = dp(22);
        stack.addView(speakButton, buttonParams);
        speakButton.setOnClickListener(v -> actions.toggleListening());

        TextView timelineTitle = new TextView(context);
        timelineTitle.setText("ACTIVITY / OPERATIONS");
        timelineTitle.setTextSize(11);
        timelineTitle.setLetterSpacing(0.12f);
        timelineTitle.setTextColor(accent);
        LinearLayout.LayoutParams timelineParams = new LinearLayout.LayoutParams(-1, dp(33));
        timelineParams.topMargin = dp(19);
        stack.addView(timelineTitle, timelineParams);

        ScrollView scroll = new ScrollView(context);
        activity = new TextView(context);
        activity.setText("System initialized. Awaiting voice activation.");
        activity.setTextSize(13);
        activity.setTextColor(Color.rgb(193, 214, 228));
        activity.setLineSpacing(dp(6), 1f);
        activity.setPadding(dp(12), dp(10), dp(12), dp(10));
        activity.setBackgroundColor(Color.rgb(13, 29, 46));
        scroll.addView(activity);
        stack.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER);
        Button mute = utility(context, "SILENCE");
        mute.setOnClickListener(v -> actions.stopSpeaking());
        tools.addView(mute, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button settings = utility(context, "PHONE SETTINGS");
        settings.setOnClickListener(v -> actions.openSettings());
        tools.addView(settings, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button console = utility(context, "ENGINE");
        console.setOnClickListener(v -> actions.openConsole());
        tools.addView(console, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, dp(55));
        toolsParams.topMargin = dp(12);
        stack.addView(tools, toolsParams);
    }

    private Button utility(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(10);
        button.setTextColor(accent);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(17, 44, 63)));
        return button;
    }

    void setState(String raw) {
        String s = raw == null ? "standby" : raw;
        state.setText(s.toUpperCase(java.util.Locale.ROOT));
        listening = "listening".equals(s) || "hearing".equals(s);
        orb.setMode(s);
        speakButton.setText(listening || "thinking".equals(s) || "transcribing".equals(s) ? "END VOICE SESSION" : "ACTIVATE VOICE");
    }

    void addEvent(String message) {
        String old = activity.getText().toString();
        if (old.length() > 1200) old = old.substring(old.length() - 1200);
        activity.setText(message + "\n\n" + old);
    }

    private int dp(int v) { return (int)(getResources().getDisplayMetrics().density * v + 0.5f); }

    static final class Orb extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        String mode = "off";
        Orb(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        void setMode(String value) { mode = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth()/2f, cy=getHeight()/2f;
            float radius = Math.min(cx,cy)*0.67f;
            int primary = "hearing".equals(mode) || "listening".equals(mode) ? Color.rgb(26,244,220)
                : "thinking".equals(mode) ? Color.rgb(90,154,255) : Color.rgb(42,194,228);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx,cy,radius*1.5f,
                new int[]{0xAA0C738F,0x55417DA0,0x00000000},null,Shader.TileMode.CLAMP));
            canvas.drawCircle(cx,cy,radius*1.5f,paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(primary);
            paint.setStrokeWidth(dpStatic(2));
            for(int i=0;i<4;i++) {
                paint.setAlpha(215-i*43);
                canvas.drawCircle(cx,cy,radius*(0.72f+i*0.14f),paint);
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx,cy,radius*0.74f,
                new int[]{0xFF84F5FF,primary,0xFF05304A},null,Shader.TileMode.CLAMP));
            canvas.drawCircle(cx,cy,radius*0.72f,paint);
            paint.setShader(null);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.rgb(4,30,45));
            paint.setTextSize(radius*0.33f);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));
            canvas.drawText("J",cx,cy+radius*0.12f,paint);
        }
        private int dpStatic(int x) { return (int)(getResources().getDisplayMetrics().density*x+0.5f); }
    }
}
