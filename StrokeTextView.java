
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

        // 处理单行省略
        int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        CharSequence ellipsized = TextUtils.ellipsize(
                text, paint, availableWidth, TextUtils.TruncateAt.END
        );

        // 1. 画描边
        if (mStrokeWidth > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(mStrokeWidth);
            paint.setColor(mStrokeColor);
            paint.setShader(null);
            canvas.drawText(ellipsized, 0, ellipsized.length(), x, y, paint);
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
        canvas.drawText(ellipsized, 0, ellipsized.length(), x, y, paint);
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
