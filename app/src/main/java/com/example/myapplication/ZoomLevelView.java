package com.example.myapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;
//Create a custom ZoomLevelView to show levels and draw a dual‑color ring when selected.

public class ZoomLevelView extends AppCompatTextView {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean isSelected = false;

    public ZoomLevelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setSelectedState(boolean selected) {
        isSelected = selected;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isSelected) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - 19f;

            int rightColor = ColorUtils.setAlphaComponent(Color.parseColor("#1CF3FF"), 200);
            int leftColor = ColorUtils.setAlphaComponent(Color.parseColor("#FD2F55"), 200);

            int[] colors = {leftColor, rightColor, leftColor};
            float[] positions = {0f, 0.5f, 1f};

            Shader shader = new SweepGradient(cx, cy, colors, positions);

            ringPaint.setShader(shader);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(4f);

            canvas.drawCircle(cx, cy, radius, ringPaint);
        }
    }

}


