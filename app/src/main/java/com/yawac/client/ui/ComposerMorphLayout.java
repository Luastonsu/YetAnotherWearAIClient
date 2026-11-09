package com.yawac.client.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import com.yawac.client.R;

/** One continuous outline, with fixed child layout and interruptible frame-synchronised motion. */
public class ComposerMorphLayout extends FrameLayout {
    private final MorphSpring spring = new MorphSpring();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path(), part = new Path();
    private final RectF capsule = new RectF();
    private EditText input;
    private ImageButton button;
    private MorphingOptionsDrawable icon;
    private boolean bound, waiting, compactInput, suspended, framePosted;
    private int idleTint;
    private long previousFrame;
    private final Choreographer.FrameCallback frame = this::advanceFrame;

    public ComposerMorphLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    void bind(EditText input, ImageButton button) {
        this.input = input; this.button = button;
        // OEM button still owns input/press behaviour; the parent owns both backgrounds.
        input.setBackground(null);
        button.setBackground(null);
        button.setImageTintList(null);
        button.clearColorFilter();
        icon = new MorphingOptionsDrawable(getResources().getDisplayMetrics().density);
        button.setImageDrawable(icon);
        bound = true;
        updateFrame();
    }

    void setWaiting(boolean value, int idleTint, String description, boolean animate) {
        this.idleTint = idleTint;
        if (!bound) return;
        waiting = value;
        button.setContentDescription(description);
        spring.target(value);
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { settle(); return; }
        updateFrame();
        if (!suspended) postFrame();
    }

    void pauseMotion() {
        suspended = true;
        removeFrame();
    }

    void resumeMotion() {
        suspended = false;
        postFrame();
    }

    void settle() {
        removeFrame();
        spring.snap();
        updateFrame();
    }

    float morphProgress() { return (float) spring.position; }
    double morphVelocity() { return spring.velocity; }
    boolean isMorphing() { return spring.moving(); }

    private void postFrame() {
        if (!bound || suspended || framePosted || !spring.moving()) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void removeFrame() {
        Choreographer.getInstance().removeFrameCallback(frame);
        framePosted = false;
        previousFrame = 0;
    }

    private void advanceFrame(long time) {
        framePosted = false;
        if (suspended) return;
        // Start on a presented frame, so keyboard/window work cannot swallow the animation.
        if (previousFrame != 0) spring.advance((time - previousFrame) / 1_000_000_000d);
        previousFrame = time;
        updateFrame();
        if (spring.moving()) postFrame(); else previousFrame = 0;
    }

    private void updateFrame() {
        if (!bound) return;
        float p = morphProgress();
        boolean nextCompact = !spring.moving() && p == 1;
        if (compactInput != nextCompact) {
            compactInput = nextCompact;
            requestLayout(); // Only terminal layout changes; text is never reflowed per frame.
        }
        input.setAlpha(1 - smooth(.02f, .30f, p));
        boolean ready = !waiting && p < .18f;
        input.setEnabled(ready);
        input.setImportantForAccessibility(ready ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        button.setTranslationX(px(54.5f * smooth(0, .70f, p)));
        icon.frame(p, idleTint, getContext().getColor(R.color.brand_error));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!bound) return;
        float p = morphProgress(), cy = px(18), r = px(17.5f);
        float cx = px(18 + 54.5f * smooth(0, .70f, p));
        float left = px(41 + 13.5f * smooth(.18f, 1, p));
        float right = px(145 - 54.5f * smooth(.12f, 1, p));
        float halfHeight = px(15 + 3 * smooth(.10f, .85f, p));
        capsule.set(left + px(.5f), cy - halfHeight + px(.5f), right - px(.5f), cy + halfHeight - px(.5f));
        float capRadius = capsule.height() / 2, capCenter = capsule.left + capRadius;
        outline.rewind();
        outline.addCircle(cx, cy, r, Path.Direction.CW);
        part.rewind();
        part.addRoundRect(capsule, capRadius, capRadius, Path.Direction.CW);
        outline.op(part, Path.Op.UNION);

        // A widening curved neck joins the two surfaces before the capsule contracts.
        float angle = (float) (Math.PI / 2 * smooth(0, .35f, p));
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        if (sin > 0 && capCenter > cx) {
            float x1 = cx + r * cos, x2 = capCenter - capRadius * cos;
            float y1 = r * sin, y2 = capRadius * sin;
            float middle = (x1 + x2) / 2;
            float pinch = px(4) * (1 - smooth(.10f, .60f, p)) * sin;
            part.rewind();
            part.moveTo(x1, cy - y1);
            part.cubicTo(middle, cy - y1 + pinch, middle, cy - y2 + pinch, x2, cy - y2);
            part.lineTo(x2, cy + y2);
            part.cubicTo(middle, cy + y2 - pinch, middle, cy + y1 - pinch, x1, cy + y1);
            part.close();
            outline.op(part, Path.Op.UNION);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xD9202124);
        canvas.drawPath(outline, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(px(1));
        paint.setColor(0x66FFFFFF);
        canvas.drawPath(outline, paint);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (!bound) { super.onMeasure(widthSpec, heightSpec); return; }
        setMeasuredDimension(resolveSize(dp(145), widthSpec), resolveSize(dp(36), heightSpec));
        input.measure(MeasureSpec.makeMeasureSpec(dp(compactInput ? 36 : 104), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(compactInput ? 36 : 30), MeasureSpec.EXACTLY));
        button.measure(MeasureSpec.makeMeasureSpec(dp(36), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(36), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!bound) { super.onLayout(changed, left, top, right, bottom); return; }
        int fieldLeft = dp(compactInput ? 54.5f : 41), fieldTop = (getHeight() - input.getMeasuredHeight()) / 2;
        input.layout(fieldLeft, fieldTop, fieldLeft + input.getMeasuredWidth(), fieldTop + input.getMeasuredHeight());
        button.layout(0, 0, button.getMeasuredWidth(), button.getMeasuredHeight());
    }

    @Override protected void onDetachedFromWindow() { settle(); super.onDetachedFromWindow(); }
    private float px(float value) { return value * getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(px(value)); }
    static float smooth(float start, float end, float value) {
        float t = Math.max(0, Math.min(1, (value - start) / (end - start)));
        return t * t * (3 - 2 * t);
    }
}
