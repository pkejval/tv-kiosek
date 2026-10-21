package cz.kiosek.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/** Shared look of the D-pad screens: dark background, tiles with a clear focus state. */
final class TvUi {
    static final int BACKGROUND = 0xFF0E1116;
    static final int TILE = 0xFF1A1E26;
    static final int TILE_FOCUSED = 0xFF233A4D;
    static final int ACCENT = 0xFF40C4FF;
    static final int TEXT = 0xFFF2F4F7;
    static final int MUTED = 0xFF9AA4B2;
    static final int GOOD = 0xFF66BB6A;
    static final int WARNING = 0xFFFFB74D;

    private final Context context;
    private final float density;

    TvUi(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    int dp(int value) {
        return (int) (value * density);
    }

    TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    void makeFocusable(View view, View.OnClickListener action) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(action);
        view.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f).setDuration(120).start());
    }

    Drawable tileBackground(int normal, int focused) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable on = rounded(focused);
        on.setStroke(dp(3), ACCENT);
        states.addState(new int[] { android.R.attr.state_pressed }, on);
        states.addState(new int[] { android.R.attr.state_focused }, on);
        states.addState(new int[0], rounded(normal));
        return states;
    }

    GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }
}
