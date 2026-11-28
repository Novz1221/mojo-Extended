package git.artdeell.mojo.social;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Avatar bulat: foto profil kalau ada, kalau tidak warna acak dengan huruf depan. Cincin hijau saat bicara. */
public class AvatarView extends View {
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Matrix mMatrix = new Matrix();
    private Bitmap mPhoto;
    private BitmapShader mShader;
    private int mColor = 0xFF888888;
    private String mInitial = "?";
    private boolean mSpeaking;
    private final float mDensity;

    public AvatarView(Context c) {
        super(c);
        mDensity = c.getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setColor(0xFFFFFFFF);
        mText.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** key dipakai untuk warna dan foto, name untuk huruf depan */
    public void setPerson(String key, String name) {
        mColor = Avatars.colorFor(key);
        mInitial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        setPhoto(Avatars.photo(key));
    }

    public void setPhoto(Bitmap b) {
        if (b == mPhoto) return;
        mPhoto = b;
        mShader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        invalidate();
    }

    public void setSpeaking(boolean s) {
        if (s == mSpeaking) return;
        mSpeaking = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float stroke = Math.max(2f, 3f * mDensity);
        float inset = stroke / 2f;
        mRect.set(inset, inset, w - inset, h - inset);
        float r = Math.min(mRect.width(), mRect.height()) / 2f;
        float cx = w / 2f, cy = h / 2f;
        if (mShader != null && mPhoto != null) {
            mMatrix.reset();
            float s = Math.max(mRect.width() / mPhoto.getWidth(), mRect.height() / mPhoto.getHeight());
            mMatrix.postScale(s, s);
            mMatrix.postTranslate(mRect.left, mRect.top);
            mShader.setLocalMatrix(mMatrix);
            mFill.setShader(mShader);
            canvas.drawCircle(cx, cy, r, mFill);
            mFill.setShader(null);
        } else {
            mFill.setColor(mColor);
            canvas.drawCircle(cx, cy, r, mFill);
            mText.setTextSize(r * 1.0f);
            canvas.drawText(mInitial, cx, cy - (mText.descent() + mText.ascent()) / 2f, mText);
        }
        mRing.setStrokeWidth(stroke);
        mRing.setColor(mSpeaking ? Avatars.RING_ON : Avatars.RING_OFF);
        canvas.drawCircle(cx, cy, r, mRing);
    }
}
