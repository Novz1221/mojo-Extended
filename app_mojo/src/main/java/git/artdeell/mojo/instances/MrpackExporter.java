package git.artdeell.mojo.instances;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an instance as a Modrinth modpack (.mrpack). Everything goes under "overrides/":
 * icon.webp, options.txt and the config, mods, resourcepacks and shaderpacks folders.
 */
public final class MrpackExporter {
    private static final String OVERRIDES = "overrides/";
    private static final String[] FILES = {"options.txt"};
    private static final String[] FOLDERS = {"config", "mods", "resourcepacks", "shaderpacks"};

    private static final Pattern FABRIC_LIKE = Pattern.compile("^(fabric|quilt)-loader-([^-]+)-(.+)$");
    private static final Pattern FORGE = Pattern.compile("^(.+?)-forge-(.+)$");
    private static final Pattern NEOFORGE = Pattern.compile("^neoforge-(\\d+)\\.(\\d+)\\..+$");

    private MrpackExporter() {
    }

    public static String suggestedFileName(String displayName) {
        String safe = displayName == null ? "" : displayName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (safe.isEmpty()) safe = "modpack";
        return safe + ".mrpack";
    }

    public static void export(Instance instance, String displayName, OutputStream target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];

            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(buildIndex(instance, displayName).getBytes("UTF-8"));
            zip.closeEntry();

            File icon = instance.getInstanceIconLocation();
            if (icon.isFile()) addFile(zip, icon, OVERRIDES + "icon.webp", buffer);

            File gameDir = instance.getGameDirectory();
            for (String name : FILES) {
                File file = new File(gameDir, name);
                if (file.isFile()) addFile(zip, file, OVERRIDES + name, buffer);
            }
            for (String name : FOLDERS) {
                File dir = new File(gameDir, name);
                if (dir.isDirectory()) addDirectory(zip, dir, OVERRIDES + name, buffer);
            }
        }
    }

    private static void addDirectory(ZipOutputStream zip, File dir, String entryPath, byte[] buffer) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String childPath = entryPath + "/" + child.getName();
            if (child.isDirectory()) addDirectory(zip, child, childPath, buffer);
            else if (child.isFile()) addFile(zip, child, childPath, buffer);
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String entryPath, byte[] buffer) throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private static String buildIndex(Instance instance, String displayName) throws IOException {
        try {
            JSONObject dependencies = new JSONObject();
            fillDependencies(dependencies, instance.versionId);

            JSONObject index = new JSONObject();
            index.put("formatVersion", 1);
            index.put("game", "minecraft");
            index.put("versionId", "1.0.0");
            index.put("name", displayName);
            index.put("summary", "");
            index.put("files", new JSONArray());
            index.put("dependencies", dependencies);
            return index.toString(2);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static void fillDependencies(JSONObject dependencies, String versionId) throws JSONException {
        if (versionId == null) return;
        Matcher fabricLike = FABRIC_LIKE.matcher(versionId);
        if (fabricLike.matches()) {
            dependencies.put("minecraft", fabricLike.group(3));
            dependencies.put(fabricLike.group(1) + "-loader", fabricLike.group(2));
            return;
        }
        Matcher forge = FORGE.matcher(versionId);
        if (forge.matches()) {
            dependencies.put("minecraft", forge.group(1));
            dependencies.put("forge", forge.group(2));
            return;
        }
        Matcher neoforge = NEOFORGE.matcher(versionId);
        if (neoforge.matches()) {
            String minor = neoforge.group(2);
            dependencies.put("minecraft", "1." + neoforge.group(1) + ("0".equals(minor) ? "" : "." + minor));
            dependencies.put("neoforge", versionId.substring("neoforge-".length()));
            return;
        }
        dependencies.put("minecraft", versionId);
    }
}
