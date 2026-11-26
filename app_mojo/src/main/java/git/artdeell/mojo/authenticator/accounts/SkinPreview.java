package git.artdeell.mojo.authenticator.accounts;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Base64;

/** Draws flat front-view previews (head and full body) from a Minecraft skin texture. */
public final class SkinPreview {
    private static Bitmap sDefaultSkin;

    /** The default Steve skin, a 64x32 PNG */
    private static final String STEVE_SKIN =
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAE6ElEQVRogeWX30/bVhTH/UjtxCVVp0Z2MKVJjQNBDoU9JFEX" +
            "KOVHp6rViJhaPJUKJkhDEVIEm9QKBVYEpJO6PUUKSjOpUqUIberD2u2hYo97yv/03cPNNdfGjilFDaOWvnLie3zj7+fec3zC" +
            "cR5HT4cfPR1+RIJtYD9Hgm3obBewOq43ldf8p/6ghvXO89A7z1sARIJtWB3XkRuKYyszaJ63MoNnCwA1r0q8K4BwOGxRbih+" +
            "NgDQ1benAb125gGwZlWJhyrxlnT4LAA4mfcCMKmpZwcANR4N+aBKPDrbhUMAnHRmAFDjrOjO+N+/BjWZhyYTU92ygGjIh2vh" +
            "dvRdJtIv+9GrkDqghQREgm1QJR69CgFA466F2xEN+dAtC1AlMqcm8/DqI1rt3wJAlXjEFBHRkA+9ih8DkQB6Fb+pgUgAN3ou" +
            "IN3zBWKKCC0kmDHRkA8xRTTnYQE06yNa7Z+LKSLUIFk1YlyEJguIBHkTxF/Fx9h/kce/pXXs/5zH/osV/P7DNAavXkA05EMk" +
            "yEOTBfQ24KkSDzUoIKaI8OojWu2f7ADJj74OkayqTFIhpojQrwSwtzaPt5tL2Fubxx/rS6jkv8P7Yh6VR7dReXQb+pUAYoqI" +
            "blmAJpMd1NchQpP80GQeXn1Eq/2bKUDSwIfwpXPYvpdEZW4USe0i3m4uAQAmrxfx7ukC3hfzmLxeBADsrc0jqV1EZW4U2/eS" +
            "CF86B1XygZ3Tq49otf8DAJK/sW0D2JzsR2lmGDvTCVTmxvDn01n8/VPefL39s/MYr3LjWM8MYmc6gdLMMDYn+xFTAlCDgrn6" +
            "bBF06yNa7Z+j276vQ8TsiI6XubvYnbtp/qH59cEISjND+PHGlxb98uArbGQSWB3XsZUZxO7cTbzM3cXsiE5SoJEOXn1Eq/1z" +
            "9N8bNV1ZmEBpZhjP76ewMqFjeTSGpZEebGQS2Mgk8OTOAJ5NpfBsKoUndwawMqHj+f0UfsveQmVhArvff03OjfkoSHsfQcG1" +
            "2v+h49tbdbDSNM0izwnqdfemqF4H9/Bhc3kdtRqZp14HV6shNxTHN/1XzfOpAODWGp8IAGq+oc8bQK1mMZ8bip8OAJ8yBT4a" +
            "QFzLI67loXYZiGt5jCVfW8SO2ceT/UVwhcKBDANcuQyuWsVGJoFXuXGzeHLVKhEbXyiQa7Xagd68IaLf3eJpjH2c3k91FABq" +
            "l3EsAGPJ1+RHs1miQuHgwRsQTPP0gdj4bNZqmhWdh42lBuk9FKh9/DgA3Ax6AnB6QPqQ1SrZEaxJNj6bdTbPzuEEmI1pOQDD" +
            "OHgAw7D+OAXAXnOLp3G1mvWz3SC7mz7VDmBjkv1Fi0wjVOWy1TT9TK+zsRSA3TRzf2lm2NT2VAp7a/MWbU+lLDH28WMBoObY" +
            "HeAKgDWTzVoBOBljgdEUcLqnsdWdAJSWDdMgvX6iAD64BtgBsKvPVmwWAFsDbKZZIKw5O4DSsuEIoLRsmDFHAsDqo98CXgDs" +
            "bwGnWBcA9hW2A3BKkRMB0Gy8aRGyvwHsAJyKlk2sOTcAzWqEJ4B0Oo10Ou0JgMYtLi5aZBqijQhdPfvquwFg4x3qAWvOKcdZ" +
            "AGwK0PORAbgZ9BpnU0TtMg4BNDtGBoC98TILKk2Tctkswm4AqEmnHdIyAE47yAKhXPZurctlyzX7ClsK4LLhWCM+BMB/aG3t" +
            "AxSVGg8AAAAASUVORK5CYII=";

    private SkinPreview() {}

    private static Bitmap normalize(Bitmap skin) {
        int width = skin.getWidth();
        int height = skin.getHeight();
        if (width == 64 && (height == 64 || height == 32)) return skin;
        if (width < 64) return null;
        int newHeight = Math.max(32, Math.round(height * 64f / width));
        newHeight = newHeight >= 48 ? 64 : 32;
        return Bitmap.createScaledBitmap(skin, 64, newHeight, false);
    }

    private static void blit(Canvas canvas, Bitmap skin, Paint paint,
                             int sx, int sy, int sw, int sh, int dx, int dy, boolean mirror) {
        Rect src = new Rect(sx, sy, sx + sw, sy + sh);
        Rect dst = new Rect(dx, dy, dx + sw, dy + sh);
        if (!mirror) {
            canvas.drawBitmap(skin, src, dst, paint);
            return;
        }
        canvas.save();
        canvas.scale(-1f, 1f, dx + sw / 2f, 0f);
        canvas.drawBitmap(skin, src, dst, paint);
        canvas.restore();
    }

    /** The default Steve skin, used when an account has no skin of its own. */
    public static synchronized Bitmap defaultSkin() {
        if (sDefaultSkin != null) return sDefaultSkin;
        try {
            byte[] data = Base64.decode(STEVE_SKIN, Base64.DEFAULT);
            Bitmap steve = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (steve != null) {
                sDefaultSkin = steve;
                return steve;
            }
        } catch (IllegalArgumentException ignored) {
            // Falls back to the texture drawn in code
        }
        sDefaultSkin = drawFallbackSkin();
        return sDefaultSkin;
    }

    /** A Steve-like fallback texture drawn in code. */
    private static Bitmap drawFallbackSkin() {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        int skinTone = Color.parseColor("#B27D5E");
        int hair = Color.parseColor("#3B2616");
        int beard = Color.parseColor("#5C3B27");
        int shirt = Color.parseColor("#00A8A8");
        int pants = Color.parseColor("#3A3DA8");
        int shoes = Color.parseColor("#4A4A4A");

        fill(canvas, paint, skinTone, 8, 8, 8, 8);
        fill(canvas, paint, hair, 8, 8, 8, 2);
        fill(canvas, paint, hair, 8, 10, 1, 2);
        fill(canvas, paint, hair, 15, 10, 1, 2);
        fill(canvas, paint, Color.WHITE, 9, 12, 2, 1);
        fill(canvas, paint, Color.WHITE, 13, 12, 2, 1);
        fill(canvas, paint, Color.parseColor("#4B3C96"), 10, 12, 1, 1);
        fill(canvas, paint, Color.parseColor("#4B3C96"), 13, 12, 1, 1);
        fill(canvas, paint, beard, 10, 14, 4, 2);

        fill(canvas, paint, shirt, 20, 20, 8, 12);

        fill(canvas, paint, skinTone, 44, 20, 4, 12);
        fill(canvas, paint, shirt, 44, 20, 4, 4);
        fill(canvas, paint, skinTone, 36, 52, 4, 12);
        fill(canvas, paint, shirt, 36, 52, 4, 4);

        fill(canvas, paint, pants, 4, 20, 4, 12);
        fill(canvas, paint, shoes, 4, 30, 4, 2);
        fill(canvas, paint, pants, 20, 52, 4, 12);
        fill(canvas, paint, shoes, 20, 62, 4, 2);
        return bitmap;
    }

    private static void fill(Canvas canvas, Paint paint, int color, int x, int y, int w, int h) {
        paint.setColor(color);
        canvas.drawRect(x, y, x + w, y + h, paint);
    }

    /** Square head with the hat layer, scaled with hard pixel edges. */
    public static Bitmap head(Bitmap rawSkin, int size) {
        Bitmap skin = rawSkin == null ? null : normalize(rawSkin);
        if (skin == null) skin = defaultSkin();
        Bitmap small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        blit(canvas, skin, paint, 8, 8, 8, 8, 0, 0, false);
        blit(canvas, skin, paint, 40, 8, 8, 8, 0, 0, false);
        return Bitmap.createScaledBitmap(small, size, size, false);
    }

    /** Front view of the whole character, scale is the size of one skin pixel. */
    public static Bitmap body(Bitmap rawSkin, int scale) {
        Bitmap skin = rawSkin == null ? null : normalize(rawSkin);
        if (skin == null) skin = defaultSkin();
        boolean modern = skin.getHeight() >= 64;
        Bitmap small = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        Paint paint = new Paint();
        paint.setFilterBitmap(false);

        blit(canvas, skin, paint, 4, 20, 4, 12, 4, 20, false);
        if (modern) blit(canvas, skin, paint, 20, 52, 4, 12, 8, 20, false);
        else blit(canvas, skin, paint, 4, 20, 4, 12, 8, 20, true);

        blit(canvas, skin, paint, 20, 20, 8, 12, 4, 8, false);
        blit(canvas, skin, paint, 44, 20, 4, 12, 0, 8, false);
        if (modern) blit(canvas, skin, paint, 36, 52, 4, 12, 12, 8, false);
        else blit(canvas, skin, paint, 44, 20, 4, 12, 12, 8, true);

        blit(canvas, skin, paint, 8, 8, 8, 8, 4, 0, false);

        if (modern) {
            blit(canvas, skin, paint, 4, 36, 4, 12, 4, 20, false);
            blit(canvas, skin, paint, 4, 52, 4, 12, 8, 20, false);
            blit(canvas, skin, paint, 20, 36, 8, 12, 4, 8, false);
            blit(canvas, skin, paint, 44, 36, 4, 12, 0, 8, false);
            blit(canvas, skin, paint, 52, 52, 4, 12, 12, 8, false);
        }
        blit(canvas, skin, paint, 40, 8, 8, 8, 4, 0, false);
        return Bitmap.createScaledBitmap(small, 16 * scale, 32 * scale, false);
    }
}
