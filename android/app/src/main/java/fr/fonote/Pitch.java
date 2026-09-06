package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;

/**
 * The turf itself, drawn the same way wherever it appears.
 *
 * <p>Two surfaces now stand on it — the pitch that carries the lineup and the board a tactical
 * note is drawn on — and a schema that did not look exactly like the pitch would read as another
 * competition. So the grass, its lines and its light live here, and each surface only adds what
 * it puts on top.
 */
final class Pitch {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float density;
    /** Rebuilt whenever the surface changes size: a gradient is bound to the box it lights. */
    private Shader vignette;
    private int litWidth, litHeight;

    Pitch(Context context) { density = context.getResources().getDisplayMetrics().density; }

    /** The dark green the lines are drawn on, rounded like a card. */
    static GradientDrawable turf(int radius, float density) {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(31, 72, 57), Color.rgb(15, 43, 36)});
        background.setCornerRadius(radius * density);
        return background;
    }

    private float dp(float n) { return n * density; }

    void draw(Canvas canvas, int width, int height) {
        float w = width, h = height, pad = dp(14), radius = w * .14f;
        if (width != litWidth || height != litHeight) {
            litWidth = width; litHeight = height;
            // Light gathers at the centre circle and falls away at the corners, as under floodlights.
            vignette = width <= 0 || height <= 0 ? null
                : new RadialGradient(w/2f, h/2f, Math.max(w, h) * .72f,
                    new int[]{Color.argb(22, 214, 255, 226), Color.TRANSPARENT, Color.argb(85, 0, 18, 12)},
                    new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP);
        }
        // Mown stripes: light and dark bands, so the turf reads as turf and not as a backdrop.
        paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 12; i++) {
            paint.setColor(i % 2 == 0 ? Color.argb(12, 226, 255, 234) : Color.argb(16, 0, 22, 14));
            canvas.drawRect(0, i*h/12, w, (i+1)*h/12, paint);
        }
        if (vignette != null) {
            paint.setShader(vignette); canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.argb(115, 226, 246, 228));
        canvas.drawRect(pad, pad, w-pad, h-pad, paint);
        canvas.drawLine(pad, h/2, w-pad, h/2, paint);
        canvas.drawCircle(w/2, h/2, radius, paint);
        canvas.drawRect(w*.23f, pad, w*.77f, h*.14f, paint);
        canvas.drawRect(w*.36f, pad, w*.64f, h*.055f, paint);
        canvas.drawRect(w*.23f, h*.86f, w*.77f, h-pad, paint);
        canvas.drawRect(w*.36f, h*.945f, w*.64f, h-pad, paint);
        // The D: the stretch of the penalty arc that escapes its box.
        arc(canvas, w/2, h*.105f, radius, h*.14f - h*.105f, false);
        arc(canvas, w/2, h*.895f, radius, h*.895f - h*.86f, true);
        corner(canvas, pad, pad, 0); corner(canvas, w-pad, pad, 90);
        corner(canvas, w-pad, h-pad, 180); corner(canvas, pad, h-pad, 270);
        // Goal mouths: a thicker stroke where the net would be.
        paint.setStrokeWidth(dp(3.5f)); paint.setColor(Color.argb(160, 236, 250, 237));
        canvas.drawLine(w*.43f, pad, w*.57f, pad, paint);
        canvas.drawLine(w*.43f, h-pad, w*.57f, h-pad, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(115, 226, 246, 228));
        canvas.drawCircle(w/2, h/2, dp(2.5f), paint);
        canvas.drawCircle(w/2, h*.105f, dp(2f), paint);
        canvas.drawCircle(w/2, h*.895f, dp(2f), paint);
    }

    /** The stretch of a circle that clears a line lying dy away from its centre, above or below. */
    private void arc(Canvas canvas, float cx, float cy, float radius, float dy, boolean upward) {
        if (dy >= radius) return;
        float edge = (float)Math.toDegrees(Math.asin(dy / radius));
        oval.set(cx-radius, cy-radius, cx+radius, cy+radius);
        canvas.drawArc(oval, upward ? 180+edge : edge, 180 - 2*edge, false, paint);
    }

    /** The quarter circle a corner flag stands in. */
    private void corner(Canvas canvas, float x, float y, float from) {
        float r = dp(9f);
        oval.set(x-r, y-r, x+r, y+r);
        canvas.drawArc(oval, from, 90, false, paint);
    }
}
