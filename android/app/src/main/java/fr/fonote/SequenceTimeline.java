package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Chronologie compacte : le corps déplace le mouvement, le bord droit règle sa durée. */
final class SequenceTimeline extends View {
    interface Listener {
        void select(String id);
        void preview(String id, int start, int duration);
        void finish(boolean commit);
    }
    private Sequence sequence;
    private final Listener listener;
    private final java.util.function.Function<String, String> names;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> actors = new ArrayList<>();
    private final int extent, ink, accent;
    private Sequence.Motion dragging;
    private float down;
    private int start, duration;
    private boolean resize, moved;
    private double currentTime;
    private final java.util.Map<String,Integer> originalTimes = new java.util.HashMap<>();
    private float dp(float n) { return n*getResources().getDisplayMetrics().density; }
    SequenceTimeline(Context context, Diagram diagram, int ink, int accent,
                     java.util.function.Function<String, String> names, Listener listener) {
        super(context); this.sequence = new Sequence(diagram); this.listener = listener;
        this.names = names; this.ink = ink; this.accent = accent;
        for (Sequence.Motion motion : sequence.motions()) {
            if (!actors.contains(motion.actor)) actors.add(motion.actor);
            originalTimes.put(motion.start.id,motion.start.time); originalTimes.put(motion.end.id,motion.end.time);
        }
        extent = Math.min(Track.END, Math.max(50, diagram.duration()+20));
        setMinimumHeight((int)dp(24+Math.max(1, actors.size())*48));
        setContentDescription("Chronologie des mouvements. Glisser un bloc change son départ, son bord droit change sa durée. Les réglages sont aussi accessibles par le menu Mouvements.");
    }
    void setTime(double time) { currentTime = time; invalidate(); }
    void setDiagram(Diagram diagram) { sequence = new Sequence(diagram); invalidate(); }
    private float x(double time) { return (float)(dp(85)+(getWidth()-dp(95))*time/extent); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); pen.setTextSize(dp(10)); pen.setColor(ink);
        for (int i = 0; i <= 4; i++) {
            int time = extent*i/4;
            String label = String.format(java.util.Locale.FRANCE,"%.1f",time/10.0);
            canvas.drawText(label, Math.min(x(time),getWidth()-pen.measureText(label)), dp(16), pen);
        }
        pen.setTextSize(dp(11));
        if (actors.isEmpty()) { pen.setColor(ink); canvas.drawText("Tracez une course ou une passe", dp(8), dp(28), pen); }
        List<Sequence.Motion> motions = sequence.motions();
        for (int row = 0; row < actors.size(); row++) {
            float y = dp(24+row*48); pen.setColor(ink);
            String name = names.apply(actors.get(row)); if (name.length()>12) name = name.substring(0,11)+"…";
            canvas.drawText(name, dp(2), y+dp(28), pen);
            pen.setColor(0x33888888); canvas.drawLine(dp(85), y+dp(40), getWidth(), y+dp(40), pen);
            for (Sequence.Motion motion : motions) if (motion.actor.equals(actors.get(row))) {
                boolean changed = !java.util.Objects.equals(originalTimes.get(motion.start.id),motion.start.time)
                    || !java.util.Objects.equals(originalTimes.get(motion.end.id),motion.end.time);
                pen.setColor(changed ? 0xffff9800 : accent); canvas.drawRoundRect(x(motion.start.time), y+dp(7),
                    Math.max(x(motion.start.time)+dp(5), x(motion.end.time)), y+dp(37), dp(3), dp(3), pen);
                pen.setColor(ink); canvas.drawLine(x(motion.end.time)-dp(3), y+dp(11), x(motion.end.time)-dp(3), y+dp(33), pen);
            }
        }
        pen.setColor(ink); pen.setStrokeWidth(dp(2));
        canvas.drawLine(x(currentTime),dp(22),x(currentTime),getHeight(),pen);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int row = (int)Math.floor((event.getY()-dp(24))/dp(48)); dragging = null;
            if (row < 0 || row >= actors.size()) return false;
            for (Sequence.Motion motion : sequence.motions()) if (motion.actor.equals(actors.get(row))
                && event.getX() >= x(motion.start.time)-dp(5) && event.getX() <= x(motion.end.time)+dp(5)) dragging = motion;
            if (dragging == null) return false;
            down = event.getX(); start = dragging.start.time; duration = dragging.duration(); moved = false;
            resize = Math.abs(down-x(dragging.end.time)) <= dp(12);
            getParent().requestDisallowInterceptTouchEvent(true); return true;
        }
        if (dragging == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getX()-down)>dp(5)) moved = true;
            if (moved) {
                int delta = Math.round((event.getX()-down)*extent/Math.max(1, getWidth()-dp(95)));
                listener.preview(dragging.id(), resize ? start : start+delta, resize ? duration+delta : duration);
                invalidate();
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (moved) listener.finish(true); else { performClick(); listener.select(dragging.id()); }
            dragging = null;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (moved) listener.finish(false); dragging = null;
        }
        return true;
    }
}
