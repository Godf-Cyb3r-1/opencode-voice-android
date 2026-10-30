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
    private final int accent = Color.rgb(255, 156, 42);
    private boolean listening;

    JarvisDashboard(Context context, Actions actions) {
        super(context);
        setBackgroundColor(Color.rgb(15, 10, 7));
        LinearLayout stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);
        stack.setPadding(dp(22), dp(24), dp(22), dp(20));
        addView(stack, new FrameLayout.LayoutParams(-1, -1));

        TextView heading = new TextView(context);
        heading.setText("J A R V I S");
        heading.setTextSize(29);
        heading.setLetterSpacing(0.32f);
        heading.setTextColor(accent);
        heading.setGravity(Gravity.CENTER);
        stack.addView(heading, new LinearLayout.LayoutParams(-1, dp(50)));

        subtitle = new TextView(context);
        subtitle.setText("ADAPTIVE PERSONAL OPERATIONS SYSTEM");
        subtitle.setTextColor(Color.rgb(188, 151, 121));
        subtitle.setTextSize(10);
        subtitle.setLetterSpacing(0.14f);
        subtitle.setGravity(Gravity.CENTER);
        stack.addView(subtitle);

        TextView telemetry = new TextView(context);
        telemetry.setText("◈   CORE / ONLINE     •     VOICE / READY     •     AGENT / STANDBY");
        telemetry.setTextColor(Color.rgb(208, 112, 48));
        telemetry.setTextSize(9);
        telemetry.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams telemetryParams = new LinearLayout.LayoutParams(-1, dp(27));
        telemetryParams.topMargin = dp(9);
        stack.addView(telemetry, telemetryParams);

        orb = new Orb(context);
        LinearLayout.LayoutParams orbParams = new LinearLayout.LayoutParams(dp(260), dp(260));
        orbParams.topMargin = dp(4);
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
        hint.setText("Tap the reactor to activate your assistant");
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(Color.rgb(194, 159, 130));
        hint.setTextSize(12);
        stack.addView(hint);

        speakButton = new Button(context);
        speakButton.setText("ENGAGE ASSISTANT");
        speakButton.setContentDescription("Activate or stop Jarvis voice conversation");
        speakButton.setTextColor(Color.BLACK);
        speakButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(52));
        buttonParams.topMargin = dp(22);
        stack.addView(speakButton, buttonParams);
        speakButton.setOnClickListener(v -> actions.toggleListening());

        TextView timelineTitle = new TextView(context);
        timelineTitle.setText("LIVE OPERATIONS / EXECUTION LOG");
        timelineTitle.setTextSize(11);
        timelineTitle.setLetterSpacing(0.12f);
        timelineTitle.setTextColor(accent);
        LinearLayout.LayoutParams timelineParams = new LinearLayout.LayoutParams(-1, dp(33));
        timelineParams.topMargin = dp(19);
        stack.addView(timelineTitle, timelineParams);

        ScrollView scroll = new ScrollView(context);
        activity = new TextView(context);
        activity.setText("REACTOR READY   •   Awaiting voice activation.");
        activity.setTextSize(13);
        activity.setTextColor(Color.rgb(249, 218, 185));
        activity.setLineSpacing(dp(6), 1f);
        activity.setPadding(dp(12), dp(10), dp(12), dp(10));
        activity.setBackgroundColor(Color.rgb(40, 25, 15));
        scroll.addView(activity);
        stack.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER);
        Button mute = utility(context, "SILENCE");
        mute.setOnClickListener(v -> actions.stopSpeaking());
        tools.addView(mute, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button settings = utility(context, "VOICE STUDIO");
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
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(66, 35, 13)));
        return button;
    }

    void setState(String raw) {
        String s = raw == null ? "standby" : raw;
        state.setText(s.toUpperCase(java.util.Locale.ROOT));
        listening = "listening".equals(s) || "hearing".equals(s);
        orb.setMode(s);
        speakButton.setText(listening || "thinking".equals(s) || "transcribing".equals(s) ? "END VOICE SESSION" : "ENGAGE ASSISTANT");
    }

    void addEvent(String message) {
        String old = activity.getText().toString();
        if (old.length() > 1200) old = old.substring(old.length() - 1200);
        activity.setText(message + "\n\n" + old);
    }

    private int dp(int v) { return (int)(getResources().getDisplayMetrics().density * v + 0.5f); }

    /**
     * Original, animated holographic HUD rendered with Android Canvas.
     * No pre-rendered GIF or network dependency: every element responds to mode.
     */
    static final class Orb extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] noise = new float[72];
        private String mode = "standby";
        private long startMillis;
        private boolean running;
        private static final int AMBER = Color.rgb(255, 160, 45);
        private static final int LIGHT = Color.rgb(255, 214, 130);
        private static final int RED = Color.rgb(255, 95, 46);

        Orb(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setContentDescription("Animated Jarvis core. Tap to activate or stop a voice session.");
            java.util.Random seeded = new java.util.Random(4407);
            for (int i = 0; i < noise.length; i++) noise[i] = 0.25f + seeded.nextFloat() * 0.75f;
        }

        void setMode(String value) {
            mode = value == null ? "standby" : value;
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startMillis = android.os.SystemClock.uptimeMillis();
            running = true;
            postInvalidateDelayed(33);
        }

        @Override protected void onDetachedFromWindow() {
            running = false;
            super.onDetachedFromWindow();
        }

        @Override protected void onVisibilityChanged(View changed, int visibility) {
            super.onVisibilityChanged(changed, visibility);
            running = getVisibility() == VISIBLE && isAttachedToWindow();
            if (running) postInvalidateDelayed(33);
        }

        private void stroke(int color, float width, int alpha) {
            p.reset();
            p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(color);
            p.setAlpha(alpha);
            p.setStrokeWidth(width);
        }

        private void filled(int color, int alpha) {
            p.reset();
            p.setAntiAlias(true);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setAlpha(alpha);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth(), height = getHeight();
            float cx = width * .5f, cy = height * .5f;
            float r = Math.min(width, height) * .38f;
            if (r <= 0) return;
            float t = (android.os.SystemClock.uptimeMillis() - startMillis) / 1000f;
            boolean listening = "listening".equals(mode) || "hearing".equals(mode);
            boolean working = "thinking".equals(mode) || "transcribing".equals(mode);
            boolean speaking = "speaking".equals(mode);
            int main = working ? RED : AMBER;
            float activity = listening ? 1f : working ? .82f : speaking ? .67f : .35f;
            float breath = .5f + .5f * (float)Math.sin(t * (listening ? 5.4f : 2.2f));

            // Atmospheric bloom stays strictly inside this view.
            filled(main, 255);
            p.setShader(new RadialGradient(cx, cy, r * 1.51f,
                    new int[]{0x335D2C0F, 0x19763C12, 0x00110B08}, null,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 1.51f, p);
            p.setShader(null);

            // Concentric scaffolding: fixed precision rings with measured divisions.
            for (int ring = 0; ring < 5; ring++) {
                float rr = r * (.57f + ring * .165f);
                stroke(main, ring == 1 ? 1.9f : .9f, ring % 2 == 0 ? 85 : 130);
                canvas.drawCircle(cx, cy, rr, p);
            }
            stroke(LIGHT, 1f, 85);
            canvas.drawCircle(cx, cy, r * 1.29f, p);

            // Opposed segmented disks rotate on independent bearings.
            canvas.save();
            canvas.rotate(t * (working ? 64f : 17f), cx, cy);
            for (int i = 0; i < 24; i++) {
                float begin = i * 15f - 90f;
                stroke((i % 6 == 0) ? LIGHT : main, i % 4 == 0 ? 5.4f : 2.3f,
                        i % 6 == 0 ? 240 : 120);
                canvas.drawArc(cx - r * 1.14f, cy - r * 1.14f,
                        cx + r * 1.14f, cy + r * 1.14f, begin, 8.4f, false, p);
            }
            canvas.restore();

            canvas.save();
            canvas.rotate(-t * (listening ? 33f : 11f), cx, cy);
            for (int i = 0; i < 18; i++) {
                stroke(main, i % 3 == 0 ? 4.3f : 2f, i % 3 == 0 ? 200 : 115);
                canvas.drawArc(cx - r * .94f, cy - r * .94f,
                        cx + r * .94f, cy + r * .94f,
                        i * 20f - 90f, i % 3 == 0 ? 12f : 7f, false, p);
            }
            canvas.restore();

            // 72 radial data bars form a ring. Their lengths fluctuate by assistant state.
            for (int i = 0; i < noise.length; i++) {
                float theta = (float) (i * 2 * Math.PI / noise.length);
                float signal = (.5f + .5f * (float)Math.sin(t * (listening ? 13f : 3.1f)
                        + i * .64f)) * noise[i];
                float length = r * (.045f + activity * signal * .18f);
                float ri = r * 1.22f, ro = ri + length;
                stroke(main, i % 6 == 0 ? 2.2f : 1.15f,
                        75 + (int)(120 * signal));
                canvas.drawLine(cx + (float)Math.cos(theta)*ri,
                        cy + (float)Math.sin(theta)*ri,
                        cx + (float)Math.cos(theta)*ro,
                        cy + (float)Math.sin(theta)*ro, p);
            }

            // Sweeping inspection beam and opposing curved tracker.
            canvas.save();
            canvas.rotate(t * (working ? 92f : 31f), cx, cy);
            stroke(LIGHT, 4f, 210);
            canvas.drawArc(cx-r*.74f,cy-r*.74f,cx+r*.74f,cy+r*.74f,
                    -90, 36, false, p);
            stroke(main, 1.6f, 150);
            canvas.drawLine(cx, cy-r*.78f, cx, cy-r*.97f, p);
            canvas.restore();

            canvas.save();
            canvas.rotate(-t * 24f, cx, cy);
            stroke(main, 2.2f, 160);
            canvas.drawArc(cx-r*.49f,cy-r*.49f,cx+r*.49f,cy+r*.49f,
                    -45, 100, false, p);
            canvas.restore();

            // Three nested luminous cores, including a small breathing plasma center.
            filled(main, 255);
            p.setShader(new RadialGradient(cx, cy, r * .52f,
                    new int[]{0x55FFCE82, 0x33FF9A2B, 0x00190F08},
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * .52f, p);
            p.setShader(null);
            stroke(LIGHT, 1.7f, 160);
            canvas.drawCircle(cx, cy, r*.40f, p);
            stroke(main, 3f, 170);
            canvas.drawCircle(cx, cy, r*.32f + breath*r*.015f, p);
            filled(main, 50 + (int)(95*breath));
            canvas.drawCircle(cx, cy, r * (.23f + .035f*breath), p);
            filled(LIGHT, 245);
            canvas.drawCircle(cx,cy,r*.08f, p);

            // Fine registration ticks at the cardinal axes.
            for (int i=0; i<4; i++) {
                float a=(float)(Math.PI*i/2);
                stroke(LIGHT,2,180);
                canvas.drawLine(cx+(float)Math.cos(a)*r*1.38f,
                    cy+(float)Math.sin(a)*r*1.38f,
                    cx+(float)Math.cos(a)*r*1.46f,
                    cy+(float)Math.sin(a)*r*1.46f,p);
            }

            // A soft mode glyph follows the real recognition/agent state.
            filled(LIGHT,220);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD));
            p.setTextSize(r*.085f);
            canvas.drawText(working ? "PROCESSING" : listening ? "AUDIO LINK" :
                    speaking ? "VOICE OUT" : "CORE ONLINE",cx,cy+r*.62f,p);

            if (running && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE)
                postInvalidateDelayed(33); // ~30 FPS; no thread or GL surface
        }
    }
}
