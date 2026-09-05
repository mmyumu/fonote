package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Field graphics underneath accessible, individually clickable player controls. */
final class PitchView extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final JSONObject match;
    private final java.util.Map<String,Integer> colours = new java.util.HashMap<>();
    /** What each player carries right now: an action symbol while composing, a mark otherwise. */
    private java.util.Map<String,String> marks = new java.util.HashMap<>();
    private java.util.Map<String,Integer> tints = new java.util.HashMap<>();
    private String focus = "";
    private boolean selecting;
    PitchView(Context context, JSONObject match, Consumer<String> select, Consumer<String> pull) {
        super(context);
        this.match = match;
        setWillNotDraw(false);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(29, 67, 54), Color.rgb(17, 46, 39)});
        background.setCornerRadius(dp(20)); setBackground(background);
        setClipToOutline(true);
        // Team colours travel with the match: identity is data, not theme.
        JSONArray teams = match.optJSONArray("teams");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.optJSONObject(i);
            colours.put(team.optString("key"), Color.parseColor(team.optString("colour")));
        }
        JSONArray players = match.optJSONArray("players");
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            LinearLayout marker = new LinearLayout(context);
            marker.setOrientation(LinearLayout.VERTICAL); marker.setGravity(Gravity.CENTER);
            marker.setTag(player);
            marker.setContentDescription(player.optString("name") + ", numéro " + player.optInt("number")
                + ", " + player.optString("team")
                + ", ajouter à la note, appui long pour l’en retirer");
            marker.setFocusable(true);
            marker.setOnClickListener(v -> select.accept(player.optString("id")));
            // The pitch is the visual surface: taking someone out of a note belongs here too.
            marker.setOnLongClickListener(v -> { pull.accept(player.optString("id")); return true; });
            TextView shirt = new TextView(context);
            shirt.setText(String.valueOf(player.optInt("number"))); shirt.setTextSize(14);
            shirt.setTypeface(Typeface.DEFAULT, Typeface.BOLD); shirt.setGravity(Gravity.CENTER);
            marker.addView(shirt, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView name = new TextView(context);
            name.setText(player.optString("name")); name.setTextSize(11);
            name.setTextColor(Color.WHITE); name.setGravity(Gravity.CENTER);
            // A marked name is longer than a bare one and must not spill onto its neighbour.
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setShadowLayer(3, 0, 1, Color.BLACK);
            marker.addView(name, new LinearLayout.LayoutParams(-1, dp(18)));
            addView(marker, new FrameLayout.LayoutParams(dp(68), dp(52)));
        }
        refresh();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    /**
     * While composing, the pitch shows the note being built and lifts its players out of their
     * team colour. At rest it only badges them, so the standings never masquerade as a selection.
     */
    void setMarks(java.util.Map<String,String> marks, java.util.Map<String,Integer> tints,
                  String focus, boolean selecting) {
        this.marks = marks; this.tints = tints; this.focus = focus; this.selecting = selecting;
        refresh();
    }
    private void refresh() {
        for (int i=0; i<getChildCount(); i++) {
            LinearLayout marker = (LinearLayout)getChildAt(i);
            JSONObject player = (JSONObject)marker.getTag();
            String id = player.optString("id");
            boolean marked = marks.containsKey(id), active = selecting && id.equals(focus);
            boolean lifted = marked && selecting;
            String mark = marked ? marks.get(id) : "";
            Integer tint = tints.get(id);
            GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
            circle.setColor(lifted ? Color.WHITE : colours.get(player.optString("team")));
            circle.setStroke(dp(active ? 3 : lifted ? 2 : 1), active ? Color.rgb(213, 255, 170)
                : lifted ? Color.rgb(207, 240, 160) : Color.argb(110, 255, 255, 255));
            marker.getChildAt(0).setBackground(circle);
            ((TextView)marker.getChildAt(0)).setTextColor(Color.rgb(15, 35, 33));
            // The mark rides on the shirt itself, so the pitch alone tells the story.
            TextView name = (TextView)marker.getChildAt(1);
            name.setText(mark.isEmpty() ? player.optString("name") : mark + " " + player.optString("name"));
            name.setTextColor(marked && tint != null ? tint : Color.WHITE);
            marker.setSelected(lifted);
        }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        for (int i=0; i<getChildCount(); i++) {
            android.view.View marker = getChildAt(i);
            JSONObject player = (JSONObject)marker.getTag();
            // Shirt numbers repeat across teams, so each player carries its own spot.
            float x = (float)player.optDouble("x"), y = (float)player.optDouble("y");
            // Spots are authored for the home side attacking downwards; mirror the away side.
            if (!"home".equals(player.optString("team"))) { x = 1-x; y = 1-y; }
            int width = dp(68), height = dp(52);
            int left = Math.round(x * (r-l) - width/2f), top = Math.round(y * (b-t) - height/2f);
            // Goalkeepers sit on the goal line: keep their marker whole instead of cropped by the edge.
            left = Math.max(0, Math.min(r-l-width, left));
            top = Math.max(0, Math.min(b-t-height, top));
            marker.layout(left, top, left+width, top+height);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), pad = dp(12);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(10, 220, 255, 229));
        for (int i=0; i<10; i+=2) canvas.drawRect(0, i*h/10, w, (i+1)*h/10, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(85, 219, 244, 221));
        canvas.drawRect(pad, pad, w-pad, h-pad, paint);
        canvas.drawLine(pad,h/2,w-pad,h/2,paint);
        canvas.drawCircle(w/2,h/2,w*.14f,paint);
        canvas.drawRect(w*.23f,pad,w*.77f,h*.14f,paint);
        canvas.drawRect(w*.36f,pad,w*.64f,h*.055f,paint);
        canvas.drawRect(w*.23f,h*.86f,w*.77f,h-pad,paint);
        canvas.drawRect(w*.36f,h*.945f,w*.64f,h-pad,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(w/2,h/2,dp(2),paint);
        canvas.drawCircle(w/2,h*.105f,dp(2),paint);
        canvas.drawCircle(w/2,h*.895f,dp(2),paint);
    }
}
