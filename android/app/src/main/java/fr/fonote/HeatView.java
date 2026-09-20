package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One player's noted actions on the grass: the good in one colour, the bad in the other.
 *
 * <p>Each action is a soft patch rather than a cell of a grid. A player gets a handful of notes a
 * match, and a grid would stay mostly empty with a few lit squares; patches that overlap show
 * the zone he keeps coming back to. The exact point stays on top of its patch: filled when the
 * note put it there, a ring when it was read off a board, whose places are only as exact as the
 * drawing.
 *
 * <p>The frame is the pitch's own, home attacking downwards, so a spot shows where the finger
 * put it on the match screen.
 */
final class HeatView extends View {
    /** A patch reaches about ten metres across the width: a zone, not a point, and not a half. */
    private static final float REACH = .16f;
    /** Longer than wide, like the pitch, without asking a phone for the full 105 by 68. */
    private static final float LENGTH = 1.4f;
    /**
     * The heat's own two colours, the same under every theme, like the lawn they are painted on.
     * A theme's green is chosen for a page: on grass it is green on green, and a light theme's
     * one vanished there entirely. Lime and vermilion stand out by their light, not only their hue.
     */
    static final int GOOD = Color.rgb(186, 255, 92), BAD = Color.rgb(255, 84, 64);
    private final Pitch grass;
    private final Paint patch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint();
    private List<Heat.Spot> spots = new ArrayList<>();
    private Predicate<String> praised = action -> true;

    HeatView(Context context, int lawn, int lawnEnd) {
        super(context);
        grass = new Pitch(context);
        setBackground(Pitch.turf(lawn, lawnEnd, 20, getResources().getDisplayMetrics().density));
        setClipToOutline(true);
        // The lawn steps back a shade: both colours have to stand out on it, the green one too.
        veil.setColor(Color.argb(70, 0, 12, 8));
    }

    /** The spots to draw, and which actions count as good. */
    void show(List<Heat.Spot> spots, Predicate<String> praised) {
        this.spots = spots; this.praised = praised;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * LENGTH));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), reach = w * REACH;
        grass.draw(canvas, getWidth(), getHeight());
        canvas.drawRect(0, 0, w, h, veil);
        // The bad under the good would hide a mistake behind a success made on the same spot: the
        // two blend instead, each patch translucent all the way to its centre.
        for (Heat.Spot spot : spots) {
            float x = (float)spot.x * w, y = (float)spot.y * h;
            int tint = praised.test(spot.action) ? GOOD : BAD;
            patch.setShader(new RadialGradient(x, y, reach,
                new int[]{shade(tint, 170), shade(tint, 90), shade(tint, 0)},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, reach, patch);
        }
        float density = getResources().getDisplayMetrics().density, radius = 4.5f * density;
        for (Heat.Spot spot : spots) {
            float x = (float)spot.x * w, y = (float)spot.y * h;
            int tint = praised.test(spot.action) ? GOOD : BAD;
            boolean pinned = spot.source == Heat.Source.PINNED;
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(pinned ? tint : Color.argb(110, 0, 12, 8));
            canvas.drawCircle(x, y, radius, dot);
            dot.setStyle(Paint.Style.STROKE);
            dot.setStrokeWidth((pinned ? 1.5f : 2.5f) * density);
            dot.setColor(pinned ? Color.WHITE : tint);
            canvas.drawCircle(x, y, radius, dot);
        }
    }

    private static int shade(int colour, int alpha) {
        return Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour));
    }
}
