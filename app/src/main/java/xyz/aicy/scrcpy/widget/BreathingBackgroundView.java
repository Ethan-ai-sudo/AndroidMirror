package xyz.aicy.scrcpy.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * 动感科技蓝黑背景：纯代码绘制深蓝→黑渐变背景 + 顶部辉光，叠加 5 个随机分布的动态呼吸光斑。
 * <p>
 * - 背景：左上→右下深蓝渐变（#0B1F3A → #01040A），顶部叠加一层蓝色辉光增加科技纵深感。
 * - 光斑：5 个，位置在每次尺寸变化时随机分布（保证"随机分布"），半径与透明度随时间做呼吸动画，
 *   并叠加缓慢往复漂移（保证"动态"）。各光斑呼吸周期、相位、漂移方向均随机，互不同步。
 * <p>
 * 性能：每个光斑的径向渐变缓存为 Bitmap，onDraw 仅做 drawBitmap + alpha 调制，避免逐帧创建 Shader。
 */
public class BreathingBackgroundView extends View {

    // 背景渐变色（动感科技蓝黑）
    private static final int BG_TOP = Color.parseColor("#0B1F3A");
    private static final int BG_MID = Color.parseColor("#071526");
    private static final int BG_BOTTOM = Color.parseColor("#01040A");

    // 顶部辉光
    private static final int GLOW_TOP = Color.parseColor("#224A7A");
    private static final int GLOW_END = Color.parseColor("#00000000");

    // 呼吸光斑可选颜色（亮蓝系）
    private static final int[] SPOT_COLORS = {
            Color.parseColor("#2E7BD6"),
            Color.parseColor("#1F5FB0"),
            Color.parseColor("#3A8FE0"),
            Color.parseColor("#1A4F9A"),
            Color.parseColor("#2E7BD6"),
    };

    private static final int SPOT_COUNT = 5;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient bgGradient;
    private LinearGradient glowGradient;

    private final Spot[] spots = new Spot[SPOT_COUNT];
    private final RectF tmpRect = new RectF();

    private ValueAnimator animator;
    private float density;
    private int lastW = -1, lastH = -1;

    public BreathingBackgroundView(Context context) {
        super(context);
        init();
    }

    public BreathingBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BreathingBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        spotPaint.setFilterBitmap(true);
        spotPaint.setDither(true);

        Random rnd = new Random();
        for (int i = 0; i < SPOT_COUNT; i++) {
            Spot s = new Spot();
            s.color = SPOT_COLORS[i % SPOT_COLORS.length];
            // 呼吸周期 3.5~6s，随机相位，保证光斑不同步
            s.cycleSec = 3.5f + rnd.nextFloat() * 2.5f;
            s.phase = rnd.nextFloat() * 2f * (float) Math.PI;
            // 基础半径 80~160 dp，呼吸幅度 40~90 dp
            s.baseRadiusDp = 80f + rnd.nextFloat() * 80f;
            s.maxExtraDp = 40f + rnd.nextFloat() * 50f;
            // 透明度呼吸区间
            s.minAlpha = 30 + rnd.nextInt(20);
            s.maxAlpha = 110 + rnd.nextInt(40);
            // 缓慢往复漂移
            s.driftAmpDp = 10f + rnd.nextFloat() * 16f;
            s.driftSpeed = 0.18f + rnd.nextFloat() * 0.35f;
            s.driftPhase = rnd.nextFloat() * 2f * (float) Math.PI;
            spots[i] = s;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0 || (w == lastW && h == lastH)) return;
        lastW = w;
        lastH = h;

        // 背景对角渐变
        bgGradient = new LinearGradient(0, 0, w, h,
                new int[]{BG_TOP, BG_MID, BG_BOTTOM},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        bgPaint.setShader(bgGradient);

        // 顶部辉光（覆盖顶部约 35% 高度）
        float glowH = h * 0.35f;
        glowGradient = new LinearGradient(0, 0, 0, glowH,
                new int[]{GLOW_TOP, GLOW_END},
                null,
                Shader.TileMode.CLAMP);
        glowPaint.setShader(glowGradient);

        // 光斑随机分布位置（避开边缘）
        Random rnd = new Random();
        for (Spot s : spots) {
            s.cxRatio = 0.12f + rnd.nextFloat() * 0.76f;
            s.cyRatio = 0.10f + rnd.nextFloat() * 0.80f;
        }
        rebuildSpotBitmaps();
    }

    /** 为每个光斑预渲染最大半径的径向渐变 Bitmap，避免逐帧创建 Shader。 */
    private void rebuildSpotBitmaps() {
        for (Spot s : spots) {
            float maxR = (s.baseRadiusDp + s.maxExtraDp) * density;
            int size = (int) (maxR * 2f + 4f);
            if (size <= 0) continue;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float cx = size / 2f;
            RadialGradient rg = new RadialGradient(cx, cx, maxR,
                    new int[]{
                            Color.argb(255, Color.red(s.color), Color.green(s.color), Color.blue(s.color)),
                            Color.argb(0, Color.red(s.color), Color.green(s.color), Color.blue(s.color))
                    },
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            p.setShader(rg);
            c.drawCircle(cx, cx, maxR, p);
            if (s.bitmap != null) {
                s.bitmap.recycle();
            }
            s.bitmap = bmp;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /** 仅作为 60fps 心跳触发重绘；动画时间取自 SystemClock，避免 RESTART 跳变。 */
    private void startAnimation() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> invalidate());
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        // 1. 背景渐变
        canvas.drawRect(0, 0, w, h, bgPaint);
        // 2. 顶部辉光
        if (glowGradient != null) {
            canvas.drawRect(0, 0, w, h * 0.35f, glowPaint);
        }
        // 3. 呼吸光斑
        float timeSec = SystemClock.uptimeMillis() / 1000f;
        for (Spot s : spots) {
            if (s.bitmap == null) continue;
            // 呼吸比例 0~1（cos 缓动，起停平缓）
            float breath = 0.5f - 0.5f * (float) Math.cos(
                    2f * (float) Math.PI * (timeSec / s.cycleSec) + s.phase);
            float curR = (s.baseRadiusDp + breath * s.maxExtraDp) * density;
            int alpha = (int) (s.minAlpha + breath * (s.maxAlpha - s.minAlpha));

            // 缓慢往复漂移
            float dx = (float) Math.sin(2f * (float) Math.PI * (timeSec * s.driftSpeed) + s.driftPhase)
                    * s.driftAmpDp * density;
            float dy = (float) Math.cos(2f * (float) Math.PI * (timeSec * s.driftSpeed * 0.8f) + s.driftPhase)
                    * s.driftAmpDp * density;
            float cx = s.cxRatio * w + dx;
            float cy = s.cyRatio * h + dy;

            spotPaint.setShader(null);
            spotPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
            tmpRect.set(cx - curR, cy - curR, cx + curR, cy + curR);
            canvas.drawBitmap(s.bitmap, null, tmpRect, spotPaint);
        }
    }

    private static final class Spot {
        int color;
        float cycleSec;
        float phase;
        float baseRadiusDp;
        float maxExtraDp;
        int minAlpha;
        int maxAlpha;
        float cxRatio;
        float cyRatio;
        float driftAmpDp;
        float driftSpeed;
        float driftPhase;
        Bitmap bitmap;
    }
}
