package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.SystemClock;
import android.view.View;

/**
 * Paints the {@link Logo} in a skin's colours, at a given cap height, and says "Fonote". While
 * the {@link Kickoff} is on, each frame is its pose at that moment.
 */
final class Wordmark extends View {
    private final Path[] glyphs = new Path[Logo.GLYPHS.size()];
    private final Path letters = new Path(), spot = new Path();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Erases around each letter: the room the line keeps from it. */
    private final Paint clearance = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cap;
    private final Kickoff kickoff;

    Wordmark(Context context, Skin skin, float capPixels, Kickoff kickoff) {
        super(context);
        cap = capPixels;
        this.kickoff = kickoff;
        setContentDescription("Fonote");
        ink.setColor(skin.ink);
        accent.setColor(skin.accent);
        clearance.setStyle(Paint.Style.FILL_AND_STROKE);
        clearance.setStrokeWidth(2 * Logo.GAP);
        clearance.setStrokeJoin(Paint.Join.ROUND);
        clearance.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        // Every outline clockwise and every hole the other way, so that overlaps fill and holes cut.
        for (int i = 0; i < glyphs.length; i++) {
            glyphs[i] = new Path();
            for (Logo.Piece piece : Logo.GLYPHS.get(i).pieces) {
                float[] at = piece.at;
                if (piece.shape == Logo.OUTLINE) {
                    glyphs[i].moveTo(at[0], at[1]);
                    for (int j = 2; j < at.length; j += 2) glyphs[i].lineTo(at[j], at[j + 1]);
                    glyphs[i].close();
                } else {
                    glyphs[i].addCircle(at[0], at[1], at[2], Path.Direction.CW);
                    glyphs[i].addOval(at[0] - at[3], at[1] - at[4], at[0] + at[3], at[1] + at[4], Path.Direction.CCW);
                }
            }
            letters.addPath(glyphs[i]);
        }
    }

    private float scale() { return cap / 100; }
    private float height() { return (Logo.BOTTOM - Logo.TOP) * scale(); }

    /** Takes whatever room it is given, like a heading does, and draws from its start edge. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(
            resolveSize(Math.round(Logo.WIDTH * scale()) + getPaddingLeft() + getPaddingRight(), widthSpec),
            resolveSize(Math.round(height()) + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        long elapsed = kickoff.at(SystemClock.uptimeMillis());
        Kickoff.Pose pose = Kickoff.pose(elapsed);
        canvas.save();
        int top = getPaddingTop(), room = getHeight() - top - getPaddingBottom();
        canvas.translate(getPaddingLeft(), top + (room - height()) / 2);
        canvas.scale(scale(), scale());
        canvas.translate(0, -Logo.TOP);
        if (pose.lineTo > pose.lineFrom) {
            // The line on a layer of its own, cleared around the letters, so that what shows
            // through the gaps is the page itself, whatever it is painted with.
            int layer = canvas.saveLayer(0, Logo.TOP, Logo.WIDTH, Logo.BOTTOM, null);
            canvas.drawRect(pose.lineFrom, Logo.LINE[1], pose.lineTo, Logo.LINE[3], ink);
            canvas.drawPath(letters, clearance);
            canvas.restoreToCount(layer);
        }
        draw(canvas, Logo.F, pose.foot, 0);
        draw(canvas, Logo.FIRST_O, pose.foot, 0);
        draw(canvas, Logo.SECOND_O, pose.foot, -pose.shift);
        draw(canvas, Logo.T, pose.foot, -pose.shift);
        draw(canvas, Logo.E, pose.e, -pose.eShift);
        if (pose.n > 0) {
            float centre = Logo.GLYPHS.get(Logo.N).centre();
            canvas.save();
            canvas.translate(pose.nCentre, 0);
            canvas.scale(pose.nWidth, 1);
            canvas.translate(-centre, 0);
            draw(canvas, Logo.N, pose.n, 0);
            canvas.restore();
        }
        if (pose.spot > 0) {
            spot.rewind();
            spot.addCircle(Logo.SPOT[0], Logo.SPOT[1], Logo.SPOT[2] * pose.spot, Path.Direction.CW);
            canvas.drawPath(spot, accent);
        }
        canvas.restore();
        if (elapsed < Kickoff.DURATION) postInvalidateOnAnimation();
    }

    private void draw(Canvas canvas, int glyph, float opacity, float shift) {
        if (opacity <= 0) return;
        ink.setAlpha(Math.round(255 * opacity));
        canvas.save();
        canvas.translate(shift, 0);
        canvas.drawPath(glyphs[glyph], ink);
        canvas.restore();
        ink.setAlpha(255);
    }
}
