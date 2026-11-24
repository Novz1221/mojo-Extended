package git.artdeell.mojo.authenticator.accounts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Local skin storage for accounts, plus the previews shown in the account screens. */
public final class Wardrobe {
    private Wardrobe() {}

    private static File skinFile(Context context, Account account) {
        String key = account.username.replaceAll("[^A-Za-z0-9_]", "_") + "-" + account.authType.name();
        return new File(new File(context.getFilesDir(), "wardrobe"), key + ".png");
    }

    @Nullable
    public static Bitmap loadWardrobeSkin(Context context, Account account) {
        File file = skinFile(context, account);
        if (!file.isFile()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    /**
     * Stores the skin for the account. A copy is also placed where the CustomSkinLoader mod
     * looks for local skins, so the skin shows in game when that mod is installed.
     */
    public static void saveSkin(Context context, Account account, @NonNull Bitmap skin) throws IOException {
        File file = skinFile(context, account);
        FileUtils.ensureParentDirectory(file);
        try (FileOutputStream out = new FileOutputStream(file)) {
            skin.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        File loaderDir = new File(Instances.SHARED_DATA_DIRECTORY, "CustomSkinLoader/LocalSkin/skins");
        File loaderFile = new File(loaderDir, account.username + ".png");
        try {
            FileUtils.ensureParentDirectory(loaderFile);
            try (FileOutputStream out = new FileOutputStream(loaderFile)) {
                skin.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (IOException ignored) {
            // The launcher preview still works without the copy
        }
    }

    @Nullable
    public static Bitmap fullSkin(Context context, Account account) {
        Bitmap wardrobe = loadWardrobeSkin(context, account);
        if (wardrobe != null) return wardrobe;
        return account.getSkinFull();
    }

    public static Bitmap head(Context context, Account account, int size) {
        Bitmap skin = fullSkin(context, account);
        if (skin != null) return SkinPreview.head(skin, size);
        return SkinPreview.head(SkinPreview.defaultSkin(), size);
    }

    public static Bitmap body(Context context, @Nullable Account account) {
        Bitmap skin = account == null ? null : fullSkin(context, account);
        return SkinPreview.body(skin, 20);
    }

    public static String typeLabel(Account account) {
        switch (account.authType) {
            case MICROSOFT:
                return "Microsoft";
            case ELY_BY:
                return "Ely.by";
            default:
                return "Offline";
        }
    }

    /** The UUID used by the game for offline players. */
    public static String displayUuid(Account account) {
        if (account.authType == git.artdeell.mojo.authenticator.AuthType.LOCAL) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + account.username)
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
        return account.profileId;
    }
}
