package git.artdeell.mojo.utils.plugins;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;

public class LibraryPlugin {
    private static final String TAG = "LibraryPlugin";

    // Known plugins constants
    public static final String ID_ANGLE_PLUGIN = "git.mojo.angle";
    public static final String ID_FFMPEG_PLUGIN = "git.mojo.ffmpeg";
    public static final String ID_ZINK_PLUGIN = "git.mojo.zink";
    public static final String ID_NATIVES_PLUGIN = "git.mojo.natives";
    public static final String ID_MESA_PLUGIN = "git.mojo.mesa";
    public static final String ID_MOBILEGLUES_PLUGIN = "com.fcl.plugin.mobileglues";

    private String appId;
    private String libraryPath;
    private LibraryPlugin(String app, String libraryPath){
        this.appId = app;
        this.libraryPath = libraryPath;
    }
    public static LibraryPlugin discoverPlugin(Context ctx, String appId){

        String libraryPath;
        try {
            PackageInfo pluginPackage = ctx.getPackageManager().getPackageInfo(appId, PackageManager.GET_SHARED_LIBRARY_FILES);
            libraryPath = pluginPackage.applicationInfo.nativeLibraryDir;

        } catch (Exception e){
            Log.e(TAG, "Plugin discover failed: " + e.getMessage());
            return null;
        }
       return new LibraryPlugin(appId, libraryPath);
    }

    /**
     * Cari plugin yang menyediakan library tertentu. Cek dulu id paket yang dikenal,
     * lalu pindai semua aplikasi terpasang yang punya file library itu di folder native-nya,
     * jadi build atau fork dengan nama paket lain ikut terdeteksi.
     */
    public static LibraryPlugin discoverAny(Context ctx, String library, String... knownIds) {
        for (String id : knownIds) {
            LibraryPlugin p = discoverPlugin(ctx, id);
            if (p != null && p.checkLibraries(library)) return p;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            String self = ctx.getPackageName();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(self) || ai.nativeLibraryDir == null) continue;
                if (new File(ai.nativeLibraryDir, library).exists()) {
                    Log.i(TAG, "Found " + library + " in " + ai.packageName);
                    return new LibraryPlugin(ai.packageName, ai.nativeLibraryDir);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Plugin scan failed: " + e.getMessage());
        }
        return null;
    }

    public String getId(){
        return appId;
    }

    public String getLibraryPath(){
        return libraryPath;
    }
    public String resolveAbsolutePath(String library) {
        return new File(libraryPath, library).getAbsolutePath();
    }
    public File resolve(String library) {
        return new File(libraryPath, library);
    }

    public boolean checkLibraries(String... libs){
        for(String lib : libs){
            if(!(new File(libraryPath, lib).exists())) return false;
        }
        return true;
    }
}
