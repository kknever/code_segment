
public class StrokeTextView extends AppCompatTextView {
    private static final int HORIZONTAL = 0;
    private static final int VERTICAL = 1;

    private int[] mGradientColor;
    private int mStrokeWidth = 0;
    private int mStrokeColor = Color.BLACK;
    private LinearGradient mGradient;
    private boolean gradientChanged = false;
    private int mGradientOrientation = HORIZONTAL;
    private String mFontAssetPath;

    // ========== 跑马灯相关 ==========
    private boolean marqueeEnabled = false;
    private float marqueeSpeed = 2f; // 每帧移动的像素数
    private float marqueeOffset = 0f;
    private float textWidth = 0f;
    private boolean isMarqueeRunning = false;
    private final Runnable marqueeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMarqueeRunning) return;
            marqueeOffset += marqueeSpeed;
            if (textWidth > 0 && marqueeOffset > textWidth + 50) { // 50为间隔
                marqueeOffset = 0f;
            }
            invalidate();
            postDelayed(this, 16); // 约60fps
        }
    };
    // ========== 跑马灯相关 ==========

    public StrokeTextView(Context context) {
        super(context);
        init(context, null);
    }

    public StrokeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public StrokeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.StrokeTextView);
            mStrokeColor = a.getColor(R.styleable.StrokeTextView_strokeColor, Color.BLACK);
            mStrokeWidth = a.getDimensionPixelSize(R.styleable.StrokeTextView_strokeWidth, 0);
            mGradientOrientation = a.getInt(R.styleable.StrokeTextView_gradientOrientation, HORIZONTAL);

            // 渐变色
            int gradientResId = a.getResourceId(R.styleable.StrokeTextView_gradientColors, 0);
            if (gradientResId != 0) {
                mGradientColor = getResources().getIntArray(gradientResId);
            }

            // 字体
            mFontAssetPath = a.getString(R.styleable.StrokeTextView_fontAsset);
            if (!TextUtils.isEmpty(mFontAssetPath)) {
                setFontAsset(mFontAssetPath);
            }

            a.recycle();
        }
    }

    /**
     * 设置字体（assets目录下的ttf/otf文件路径，如 "fonts/xxx.ttf"）
     */
    public void setFontAsset(String assetPath) {
        if (!TextUtils.isEmpty(assetPath)) {
            try {
                Typeface typeface = Typeface.createFromAsset(getContext().getAssets(), assetPath);
                setTypeface(typeface);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setGradientOrientation(int orientation) {
        if (mGradientOrientation != orientation) {
            mGradientOrientation = orientation;
            gradientChanged = true;
            invalidate();
        }
    }

    public void setGradientColor(int[] gradientColor) {
        if (gradientColor != null && !java.util.Arrays.equals(gradientColor, mGradientColor)) {
            mGradientColor = gradientColor;
            gradientChanged = true;
            invalidate();
        }
    }

    public void setStrokeColor(int color) {
        if (mStrokeColor != color) {
            mStrokeColor = color;
            invalidate();
        }
    }

    public void setStrokeWidth(int width) {
        if (mStrokeWidth != width) {
            mStrokeWidth = width;
            invalidate();
        }
    }

    // ========== 跑马灯相关方法 ==========
    /**
     * 启用跑马灯
     */
    public void startMarquee() {
        if (marqueeEnabled) return;
        marqueeEnabled = true;
        isMarqueeRunning = true;
        post(marqueeRunnable);
    }

    /**
     * 停止跑马灯
     */
    public void stopMarquee() {
        marqueeEnabled = false;
        isMarqueeRunning = false;
        removeCallbacks(marqueeRunnable);
        marqueeOffset = 0f;
        invalidate();
    }

    /**
     * 设置跑马灯速度（像素/帧，默认2）
     */
    public void setMarqueeSpeed(float speed) {
        this.marqueeSpeed = speed;
    }
    // ========== 跑马灯相关方法 ==========

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopMarquee();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        String text = getText() == null ? "" : getText().toString();
        if (TextUtils.isEmpty(text)) return;

        TextPaint paint = getPaint();
        paint.setTextAlign(Paint.Align.LEFT);

        // 计算文本基线
        Paint.FontMetricsInt fontMetrics = paint.getFontMetricsInt();
        int baseline = (getHeight() - fontMetrics.bottom + fontMetrics.top) / 2 - fontMetrics.top;
        float x = getPaddingLeft();
        float y = baseline;

        // 计算文本宽度
        textWidth = paint.measureText(text);

        // 跑马灯逻辑
        if (marqueeEnabled && textWidth > getWidth()) {
            canvas.save();
            // 向左平移
            canvas.translate(-marqueeOffset, 0);

            // 画第一遍
            drawStrokeText(canvas, text, x, y, paint);

            // 画第二遍（无缝衔接）
            canvas.translate(textWidth + 50, 0); // 50为间隔
            drawStrokeText(canvas, text, x, y, paint);

            canvas.restore();
        } else {
            // 处理单行省略
            int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            CharSequence ellipsized = TextUtils.ellipsize(
                    text, paint, availableWidth, TextUtils.TruncateAt.END
            );
            drawStrokeText(canvas, ellipsized, x, y, paint);
        }
    }

    private void drawStrokeText(Canvas canvas, CharSequence text, float x, float y, TextPaint paint) {
        // 1. 画描边
        if (mStrokeWidth > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(mStrokeWidth);
            paint.setColor(mStrokeColor);
            paint.setShader(null);
            canvas.drawText(text, 0, text.length(), x, y, paint);
        }

        // 2. 画文字（支持渐变）
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0);

        if (mGradientColor != null && mGradientColor.length > 1) {
            if (gradientChanged || mGradient == null) {
                mGradient = getGradient();
                gradientChanged = false;
            }
            paint.setShader(mGradient);
        } else {
            paint.setShader(null);
            paint.setColor(getCurrentTextColor());
        }
        canvas.drawText(text, 0, text.length(), x, y, paint);
    }

    private LinearGradient getGradient() {
        if (mGradientColor == null || mGradientColor.length < 2) return null;
        if (mGradientOrientation == HORIZONTAL) {
            return new LinearGradient(0, 0, getWidth(), 0, mGradientColor, null, Shader.TileMode.CLAMP);
        } else {
            return new LinearGradient(0, 0, 0, getHeight(), mGradientColor, null, Shader.TileMode.CLAMP);
        }
    }
}

<declare-styleable name="StrokeTextView">
    <attr name="strokeColor" format="color"/>
    <attr name="strokeWidth" format="dimension"/>
    <attr name="gradientOrientation">
        <enum name="horizontal" value="0"/>
        <enum name="vertical" value="1"/>
    </attr>
    <attr name="gradientColors" format="reference"/>
    <attr name="fontAsset" format="string"/>
</declare-styleable>

// usage example
 <com.xxx.widget.StrokeTextView
    android:id="@+id/stv_subtitle"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/dp_1"
    android:gravity="center"
    android:includeFontPadding="false"
    android:paddingHorizontal="@dimen/dp_2"
    android:text="@string/login_title_desc"
    android:textColor="#FFFFFF"
    android:textSize="@dimen/sp_12"
    app:fontAsset="fonts/SourceHanSansCN-Bold.otf"
    app:strokeColor="#FF40620F"
    app:strokeWidth="@dimen/dp_2"
    tools:ignore="HardcodedText" />

// 支持换行的
public class StrokeWrapTextViewWrap extends AppCompatTextView {
    private static final int HORIZONTAL = 0;
    private static final int VERTICAL = 1;

    private int[] mGradientColor;
    private int mStrokeWidth = 0;
    private int mStrokeColor = Color.BLACK;
    private LinearGradient mGradient;
    private boolean gradientChanged = false;
    private int mGradientOrientation = HORIZONTAL;
    private String mFontAssetPath;

    public StrokeWrapTextViewWrap(Context context) {
        super(context);
        init(context, null);
    }

    public StrokeWrapTextViewWrap(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public StrokeWrapTextViewWrap(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.StrokeTextView);
            mStrokeColor = a.getColor(R.styleable.StrokeTextView_strokeColor, Color.BLACK);
            mStrokeWidth = a.getDimensionPixelSize(R.styleable.StrokeTextView_strokeWidth, 0);
            mGradientOrientation = a.getInt(R.styleable.StrokeTextView_gradientOrientation, HORIZONTAL);

            // 渐变色
            int gradientResId = a.getResourceId(R.styleable.StrokeTextView_gradientColors, 0);
            if (gradientResId != 0) {
                mGradientColor = getResources().getIntArray(gradientResId);
            }

            // 字体
            mFontAssetPath = a.getString(R.styleable.StrokeTextView_fontAsset);
            if (!TextUtils.isEmpty(mFontAssetPath)) {
                setFontAsset(mFontAssetPath);
            }

            a.recycle();
        }
    }

    public void setFontAsset(String assetPath) {
        if (!TextUtils.isEmpty(assetPath)) {
            try {
                Typeface typeface = Typeface.createFromAsset(getContext().getAssets(), assetPath);
                setTypeface(typeface);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setGradientOrientation(int orientation) {
        if (mGradientOrientation != orientation) {
            mGradientOrientation = orientation;
            gradientChanged = true;
            invalidate();
        }
    }

    public void setGradientColor(int[] gradientColor) {
        if (gradientColor != null && !java.util.Arrays.equals(gradientColor, mGradientColor)) {
            mGradientColor = gradientColor;
            gradientChanged = true;
            invalidate();
        }
    }

    public void setStrokeColor(int color) {
        if (mStrokeColor != color) {
            mStrokeColor = color;
            invalidate();
        }
    }

    public void setStrokeWidth(int width) {
        if (mStrokeWidth != width) {
            mStrokeWidth = width;
            invalidate();
        }
    }

    // 用于缓存 StaticLayout，提升性能
    private StaticLayout mStrokeLayout;
    private StaticLayout mFillLayout;
    private int mLastWidth = -1;
    private CharSequence mLastText = null;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        int availableWidth = widthSize - getPaddingLeft() - getPaddingRight();

        CharSequence text = getText();
        if (TextUtils.isEmpty(text)) {
            setMeasuredDimension(widthSize, getPaddingTop() + getPaddingBottom());
            return;
        }

        // 用和 onDraw 一样的 TextPaint
        TextPaint fillPaint = new TextPaint(getPaint());
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStrokeWidth(0);

        StaticLayout fillLayout = StaticLayout.Builder.obtain(text, 0, text.length(), fillPaint, availableWidth)
                .setAlignment(getLayoutAlignment())
                .setMaxLines(getMaxLines())
                .setEllipsize(getEllipsize())
                .build();

        int desiredHeight = fillLayout.getHeight() + getPaddingTop() + getPaddingBottom();

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int measuredHeight;
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            measuredHeight = Math.min(desiredHeight, heightSize);
        } else {
            measuredHeight = desiredHeight;
        }

        setMeasuredDimension(widthSize, measuredHeight);

        // 缓存 layout，onDraw 复用
        mFillLayout = fillLayout;
        mLastWidth = availableWidth;
        mLastText = text;
        mStrokeLayout = null; // 让 onDraw 重新生成
    }

    @Override
    protected void onDraw(Canvas canvas) {
        CharSequence text = getText();
        if (TextUtils.isEmpty(text)) return;

        int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();

        // 1. 画描边
        if (mStrokeWidth > 0) {
            if (mStrokeLayout == null || mLastWidth != availableWidth || mLastText == null || !mLastText.equals(text)) {
                TextPaint strokePaint = new TextPaint(getPaint());
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(mStrokeWidth);
                strokePaint.setColor(mStrokeColor);
                strokePaint.setShader(null);

                mStrokeLayout = StaticLayout.Builder.obtain(text, 0, text.length(), strokePaint, availableWidth)
                        .setAlignment(getLayoutAlignment())
                        .setMaxLines(getMaxLines())
                        .setEllipsize(getEllipsize())
                        .build();
            }

            canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop());
            mStrokeLayout.draw(canvas);
            canvas.restore();
        }

        // 2. 画文字（支持渐变）
        if (mFillLayout == null || mLastWidth != availableWidth || mLastText == null || !mLastText.equals(text)) {
            TextPaint fillPaint = new TextPaint(getPaint());
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setStrokeWidth(0);

            if (mGradientColor != null && mGradientColor.length > 1) {
                if (gradientChanged || mGradient == null) {
                    mGradient = getGradient();
                    gradientChanged = false;
                }
                fillPaint.setShader(mGradient);
            } else {
                fillPaint.setShader(null);
                fillPaint.setColor(getCurrentTextColor());
            }

            mFillLayout = StaticLayout.Builder.obtain(text, 0, text.length(), fillPaint, availableWidth)
                    .setAlignment(getLayoutAlignment())
                    .setMaxLines(getMaxLines())
                    .setEllipsize(getEllipsize())
                    .build();
            mLastWidth = availableWidth;
            mLastText = text;
        }

        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        mFillLayout.draw(canvas);
        canvas.restore();
    }

    private Layout.Alignment getLayoutAlignment() {
        switch (getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) {
            case android.view.Gravity.CENTER_HORIZONTAL:
                return Layout.Alignment.ALIGN_CENTER;
            case android.view.Gravity.RIGHT:
            case android.view.Gravity.END:
                return Layout.Alignment.ALIGN_OPPOSITE;
            case android.view.Gravity.LEFT:
            case android.view.Gravity.START:
            default:
                return Layout.Alignment.ALIGN_NORMAL;
        }
    }

    private LinearGradient getGradient() {
        if (mGradientColor == null || mGradientColor.length < 2) return null;
        int w = getMeasuredWidth();
        int h = getMeasuredHeight();
        if (w == 0 || h == 0) return null;
        if (mGradientOrientation == HORIZONTAL) {
            return new LinearGradient(0, 0, w, 0, mGradientColor, null, Shader.TileMode.CLAMP);
        } else {
            return new LinearGradient(0, 0, 0, h, mGradientColor, null, Shader.TileMode.CLAMP);
        }
    }
}
