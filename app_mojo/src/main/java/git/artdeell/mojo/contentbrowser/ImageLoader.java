package git.artdeell.mojo.contentbrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.utils.DownloadUtils;

import java.io.ByteArrayOutputStream;

/** Minimal in-memory image loader for the thumbnails in the content lists. */
public final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(80);

    private ImageLoader() {}

    public static void load(ImageView view, String url, int targetPx) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DownloadUtils.download(url, bos);
                byte[] data = bos.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null) return;
                CACHE.put(url, bitmap);
                view.post(() -> {
                    if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // A missing thumbnail is not worth reporting
            }
        });
    }
}
