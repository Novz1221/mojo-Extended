package git.artdeell.mojo.contentbrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import git.artdeell.mojo.modding.modpacks.api.ApiHandler;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Search and file lookup against Modrinth and CurseForge for mods, resource packs, shaders and saves. */
public final class ContentApi {
    public static final int PLATFORM_MODRINTH = 0;
    public static final int PLATFORM_CURSEFORGE = 1;

    public static final String TYPE_MODPACK = "modpack";
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_RESOURCEPACK = "resourcepack";
    public static final String TYPE_SHADER = "shader";
    public static final String TYPE_SAVES = "world";

    public static final int PAGE_SIZE = 20;

    private static final int CF_GAME_ID = 432;
    private static final int CF_CLASS_MOD = 6;
    private static final int CF_CLASS_MODPACK = 4471;
    private static final int CF_CLASS_RESOURCEPACK = 12;
    private static final int CF_CLASS_SHADER = 6552;
    private static final int CF_CLASS_WORLD = 17;

    /** Sort keys shared by the UI, the strings are in the same order. */
    public static final String[] SORT_KEYS = {"relevance", "downloads", "follows", "newest", "updated"};
    private static final int[] CF_SORT = {1, 6, 2, 11, 3};

    private static ApiHandler sModrinth;
    private static ApiHandler sCurseforge;
    private static String sCurseforgeKey;

    private ContentApi() {}

    public static class Item {
        public int platform;
        public String type;
        public String id;
        public String title;
        public String description;
        public String iconUrl;
        public String author;
        public long downloads;
        public long follows;
        public String tags;
    }

    public static class Page {
        public List<Item> items = new ArrayList<>();
        public int total;
    }

    public static final int RELEASE_STABLE = 0, RELEASE_BETA = 1, RELEASE_ALPHA = 2;

    public static class FileVersion {
        public String name;
        public String url;
        public String fileName;
        public String hash;
        public int releaseType = RELEASE_STABLE;
        public long downloads;
        /** Publish time in epoch milliseconds, 0 when unknown. */
        public long published;
        public List<String> gameVersions = new ArrayList<>();
        public List<String> loaders = new ArrayList<>();
    }

    public static class Links {
        public String page, source, issues, wiki, discord;
    }

    public static boolean hasCurseforgeKey(String key) {
        return key != null && !key.isEmpty() && !key.equals("DUMMY");
    }

    private static synchronized ApiHandler modrinth() {
        if (sModrinth == null) sModrinth = new ApiHandler("https://api.modrinth.com/v2");
        return sModrinth;
    }

    private static synchronized ApiHandler curseforge(String key) {
        if (sCurseforge == null || !key.equals(sCurseforgeKey)) {
            sCurseforge = new ApiHandler("https://api.curseforge.com/v1", key);
            sCurseforgeKey = key;
        }
        return sCurseforge;
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        return element.getAsString();
    }

    private static long num(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return 0;
        return element.getAsLong();
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).replace('-', ' ');
    }

    public static String compact(long value) {
        if (value >= 1_000_000_000L) return (value / 1_000_000_000L) + "B";
        if (value >= 1_000_000L) return (value / 1_000_000L) + "M";
        if (value >= 1_000L) return (value / 1_000L) + "K";
        return String.valueOf(value);
    }

    /** @return the page, or null on a network or parse failure */
    public static Page search(String curseforgeKey, int platform, String type, String query,
                              String gameVersion, int sort, int page) {
        try {
            if (platform == PLATFORM_CURSEFORGE) {
                return searchCurseforge(curseforgeKey, type, query, gameVersion, sort, page);
            }
            return searchModrinth(type, query, gameVersion, sort, page);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Page searchModrinth(String type, String query, String gameVersion, int sort, int page) {
        StringBuilder facets = new StringBuilder("[[\"project_type:").append(type).append("\"]");
        if (gameVersion != null && !gameVersion.isEmpty()) {
            facets.append(",[\"versions:").append(gameVersion).append("\"]");
        }
        facets.append("]");
        HashMap<String, Object> params = new HashMap<>();
        params.put("facets", facets.toString());
        params.put("query", query == null ? "" : query);
        params.put("limit", PAGE_SIZE);
        params.put("index", SORT_KEYS[Math.max(0, Math.min(sort, SORT_KEYS.length - 1))]);
        params.put("offset", Math.max(0, page - 1) * PAGE_SIZE);
        JsonObject response = modrinth().get("search", params, JsonObject.class);
        if (response == null) return null;
        JsonArray hits = response.getAsJsonArray("hits");
        if (hits == null) return null;
        Page result = new Page();
        result.total = (int) num(response, "total_hits");
        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            Item item = new Item();
            item.platform = PLATFORM_MODRINTH;
            item.type = type;
            item.id = str(hit, "project_id");
            item.title = str(hit, "title");
            item.description = str(hit, "description");
            item.iconUrl = str(hit, "icon_url");
            item.author = str(hit, "author");
            item.downloads = num(hit, "downloads");
            item.follows = num(hit, "follows");
            StringBuilder tags = new StringBuilder();
            JsonArray categories = hit.getAsJsonArray("display_categories");
            if (categories == null) categories = hit.getAsJsonArray("categories");
            if (categories != null) {
                for (JsonElement category : categories) {
                    if (tags.length() > 0) tags.append("   ");
                    tags.append(capitalize(category.getAsString()));
                }
            }
            item.tags = tags.toString();
            result.items.add(item);
        }
        return result;
    }

    private static int curseforgeClass(String type) {
        switch (type) {
            case TYPE_MODPACK:
                return CF_CLASS_MODPACK;
            case TYPE_RESOURCEPACK:
                return CF_CLASS_RESOURCEPACK;
            case TYPE_SHADER:
                return CF_CLASS_SHADER;
            case TYPE_SAVES:
                return CF_CLASS_WORLD;
            default:
                return CF_CLASS_MOD;
        }
    }

    private static Page searchCurseforge(String key, String type, String query, String gameVersion,
                                         int sort, int page) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CF_GAME_ID);
        params.put("classId", curseforgeClass(type));
        params.put("searchFilter", query == null ? "" : query);
        params.put("sortField", CF_SORT[Math.max(0, Math.min(sort, CF_SORT.length - 1))]);
        params.put("sortOrder", "desc");
        params.put("pageSize", PAGE_SIZE);
        params.put("index", Math.max(0, page - 1) * PAGE_SIZE);
        if (gameVersion != null && !gameVersion.isEmpty()) params.put("gameVersion", gameVersion);
        JsonObject response = curseforge(key).get("mods/search", params, JsonObject.class);
        if (response == null) return null;
        JsonArray data = response.getAsJsonArray("data");
        if (data == null) return null;
        Page result = new Page();
        JsonObject pagination = response.getAsJsonObject("pagination");
        result.total = pagination == null ? data.size() : (int) Math.min(num(pagination, "totalCount"), 10000L);
        for (JsonElement element : data) {
            JsonObject mod = element.getAsJsonObject();
            Item item = new Item();
            item.platform = PLATFORM_CURSEFORGE;
            item.type = type;
            item.id = str(mod, "id");
            item.title = str(mod, "name");
            item.description = str(mod, "summary");
            JsonElement logo = mod.get("logo");
            item.iconUrl = (logo != null && logo.isJsonObject()) ? str(logo.getAsJsonObject(), "thumbnailUrl") : "";
            JsonArray authors = mod.getAsJsonArray("authors");
            item.author = (authors != null && authors.size() > 0) ? str(authors.get(0).getAsJsonObject(), "name") : "";
            item.downloads = num(mod, "downloadCount");
            item.follows = num(mod, "thumbsUpCount");
            StringBuilder tags = new StringBuilder();
            JsonArray categories = mod.getAsJsonArray("categories");
            if (categories != null) {
                int count = 0;
                for (JsonElement category : categories) {
                    if (count++ >= 4) break;
                    if (tags.length() > 0) tags.append("   ");
                    tags.append(str(category.getAsJsonObject(), "name"));
                }
            }
            item.tags = tags.toString();
            result.items.add(item);
        }
        return result;
    }

    private static JsonObject obj(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return (element != null && element.isJsonObject()) ? element.getAsJsonObject() : null;
    }

    private static String link(JsonObject object, String key) {
        String value = str(object, key);
        return value.isEmpty() ? null : value;
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> result = new ArrayList<>();
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonNull()) result.add(entry.getAsString());
        }
        return result;
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(iso.substring(0, 19));
            return date == null ? 0 : date.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /** @return the project links, never null; fields stay null when the platform has none */
    public static Links links(String curseforgeKey, Item item) {
        Links links = new Links();
        try {
            if (item.platform == PLATFORM_CURSEFORGE) {
                JsonObject response = curseforge(curseforgeKey).get("mods/" + item.id, JsonObject.class);
                JsonObject data = response == null ? null : obj(response, "data");
                JsonObject found = data == null ? null : obj(data, "links");
                if (found != null) {
                    links.page = link(found, "websiteUrl");
                    links.source = link(found, "sourceUrl");
                    links.issues = link(found, "issuesUrl");
                    links.wiki = link(found, "wikiUrl");
                }
            } else {
                JsonObject project = modrinth().get("project/" + item.id, JsonObject.class);
                if (project != null) {
                    String slug = str(project, "slug");
                    String kind = str(project, "project_type");
                    if (kind.isEmpty()) kind = item.type;
                    if (!slug.isEmpty()) links.page = "https://modrinth.com/" + kind + "/" + slug;
                    links.source = link(project, "source_url");
                    links.issues = link(project, "issues_url");
                    links.wiki = link(project, "wiki_url");
                    links.discord = link(project, "discord_url");
                }
            }
        } catch (RuntimeException ignored) {
            // The links are optional extras for the detail card
        }
        return links;
    }

    /**
     * Lists every published file of a project with its metadata, without any version or loader filter.
     * @return the files, or null on a network failure
     */
    public static List<FileVersion> allVersions(String curseforgeKey, Item item) {
        try {
            if (item.platform == PLATFORM_CURSEFORGE) return allCurseforgeFiles(curseforgeKey, item);
            return allModrinthFiles(item);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<FileVersion> allModrinthFiles(Item item) {
        JsonArray response = modrinth().get("project/" + item.id + "/version", JsonArray.class);
        if (response == null) return null;
        List<FileVersion> result = new ArrayList<>();
        for (JsonElement element : response) {
            JsonObject version = element.getAsJsonObject();
            JsonArray files = version.getAsJsonArray("files");
            if (files == null || files.size() == 0) continue;
            JsonObject file = files.get(0).getAsJsonObject();
            for (JsonElement candidate : files) {
                JsonObject candidateObject = candidate.getAsJsonObject();
                JsonElement primary = candidateObject.get("primary");
                if (primary != null && !primary.isJsonNull() && primary.getAsBoolean()) {
                    file = candidateObject;
                    break;
                }
            }
            FileVersion fileVersion = new FileVersion();
            fileVersion.url = str(file, "url");
            if (fileVersion.url.isEmpty()) continue;
            fileVersion.fileName = str(file, "filename");
            JsonObject hashes = obj(file, "hashes");
            if (hashes != null) fileVersion.hash = link(hashes, "sha1");
            String name = str(version, "name");
            if (name.isEmpty()) name = str(version, "version_number");
            fileVersion.name = name;
            String type = str(version, "version_type");
            fileVersion.releaseType = type.equals("alpha") ? RELEASE_ALPHA
                    : type.equals("beta") ? RELEASE_BETA : RELEASE_STABLE;
            fileVersion.downloads = num(version, "downloads");
            fileVersion.published = parseTime(str(version, "date_published"));
            fileVersion.gameVersions = strings(version, "game_versions");
            fileVersion.loaders = strings(version, "loaders");
            result.add(fileVersion);
        }
        return result;
    }

    private static boolean isCurseforgeLoader(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "forge":
            case "fabric":
            case "quilt":
            case "neoforge":
                return true;
            default:
                return false;
        }
    }

    private static List<FileVersion> allCurseforgeFiles(String key, Item item) {
        List<FileVersion> result = new ArrayList<>();
        int index = 0;
        for (int page = 0; page < 20; page++) {
            HashMap<String, Object> params = new HashMap<>();
            params.put("pageSize", 50);
            params.put("index", index);
            JsonObject response = curseforge(key).get("mods/" + item.id + "/files", params, JsonObject.class);
            if (response == null) return page == 0 ? null : result;
            JsonArray data = response.getAsJsonArray("data");
            if (data == null || data.size() == 0) break;
            for (JsonElement element : data) {
                JsonObject file = element.getAsJsonObject();
                FileVersion fileVersion = new FileVersion();
                fileVersion.fileName = str(file, "fileName");
                String url = str(file, "downloadUrl");
                long fileId = num(file, "id");
                if (url.isEmpty() && fileId > 0 && !fileVersion.fileName.isEmpty()) {
                    url = "https://edge.forgecdn.net/files/" + (fileId / 1000) + "/" + (fileId % 1000) + "/" + fileVersion.fileName;
                }
                if (url.isEmpty()) continue;
                fileVersion.url = url;
                String name = str(file, "displayName");
                fileVersion.name = name.isEmpty() ? fileVersion.fileName : name;
                long releaseType = num(file, "releaseType");
                fileVersion.releaseType = releaseType == 3 ? RELEASE_ALPHA
                        : releaseType == 2 ? RELEASE_BETA : RELEASE_STABLE;
                fileVersion.downloads = num(file, "downloadCount");
                fileVersion.published = parseTime(str(file, "fileDate"));
                for (String entry : strings(file, "gameVersions")) {
                    if (entry.isEmpty()) continue;
                    if (Character.isDigit(entry.charAt(0))) fileVersion.gameVersions.add(entry);
                    else if (isCurseforgeLoader(entry)) fileVersion.loaders.add(entry.toLowerCase(Locale.ROOT));
                }
                JsonArray hashes = file.getAsJsonArray("hashes");
                if (hashes != null) {
                    for (JsonElement hash : hashes) {
                        JsonObject hashObject = hash.getAsJsonObject();
                        if (num(hashObject, "algo") == 1) {
                            fileVersion.hash = link(hashObject, "value");
                            break;
                        }
                    }
                }
                result.add(fileVersion);
            }
            index += data.size();
            JsonObject pagination = obj(response, "pagination");
            long total = pagination == null ? 0 : num(pagination, "totalCount");
            if (index >= total) break;
        }
        return result;
    }

    /**
     * Lists downloadable files for a project.
     * @param loader a mod loader name (fabric, forge, quilt, neoforge) or null for no loader filter
     * @return the files, or null on a network failure
     */
    public static List<FileVersion> versions(String curseforgeKey, Item item, String gameVersion, String loader) {
        try {
            if (item.platform == PLATFORM_CURSEFORGE) {
                return curseforgeFiles(curseforgeKey, item, gameVersion, loader);
            }
            return modrinthFiles(item, gameVersion, loader);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<FileVersion> modrinthFiles(Item item, String gameVersion, String loader) {
        HashMap<String, Object> params = new HashMap<>();
        if (gameVersion != null && !gameVersion.isEmpty()) params.put("game_versions", "[\"" + gameVersion + "\"]");
        if (loader != null && TYPE_MOD.equals(item.type)) params.put("loaders", "[\"" + loader + "\"]");
        JsonArray response = params.isEmpty()
                ? modrinth().get("project/" + item.id + "/version", JsonArray.class)
                : modrinth().get("project/" + item.id + "/version", params, JsonArray.class);
        if (response == null) return null;
        List<FileVersion> result = new ArrayList<>();
        for (JsonElement element : response) {
            JsonObject version = element.getAsJsonObject();
            JsonArray files = version.getAsJsonArray("files");
            if (files == null || files.size() == 0) continue;
            JsonObject file = files.get(0).getAsJsonObject();
            for (JsonElement candidate : files) {
                JsonObject candidateObject = candidate.getAsJsonObject();
                JsonElement primary = candidateObject.get("primary");
                if (primary != null && !primary.isJsonNull() && primary.getAsBoolean()) {
                    file = candidateObject;
                    break;
                }
            }
            FileVersion fileVersion = new FileVersion();
            fileVersion.url = str(file, "url");
            fileVersion.fileName = str(file, "filename");
            StringBuilder name = new StringBuilder(str(version, "name"));
            JsonArray gameVersions = version.getAsJsonArray("game_versions");
            if (gameVersions != null && gameVersions.size() > 0) {
                name.append("  (").append(gameVersions.get(0).getAsString());
                if (gameVersions.size() > 1) name.append("+");
                name.append(")");
            }
            fileVersion.name = name.toString();
            if (!fileVersion.url.isEmpty()) result.add(fileVersion);
        }
        return result;
    }

    private static int curseforgeLoader(String loader) {
        if (loader == null) return 0;
        switch (loader) {
            case "forge":
                return 1;
            case "fabric":
                return 4;
            case "quilt":
                return 5;
            case "neoforge":
                return 6;
            default:
                return 0;
        }
    }

    private static List<FileVersion> curseforgeFiles(String key, Item item, String gameVersion, String loader) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("pageSize", 50);
        if (gameVersion != null && !gameVersion.isEmpty()) params.put("gameVersion", gameVersion);
        int loaderType = TYPE_MOD.equals(item.type) ? curseforgeLoader(loader) : 0;
        if (loaderType != 0) params.put("modLoaderType", loaderType);
        JsonObject response = curseforge(key).get("mods/" + item.id + "/files", params, JsonObject.class);
        if (response == null) return null;
        JsonArray data = response.getAsJsonArray("data");
        if (data == null) return null;
        List<FileVersion> result = new ArrayList<>();
        for (JsonElement element : data) {
            JsonObject file = element.getAsJsonObject();
            FileVersion fileVersion = new FileVersion();
            fileVersion.fileName = str(file, "fileName");
            String url = str(file, "downloadUrl");
            long fileId = num(file, "id");
            if (url.isEmpty() && fileId > 0 && !fileVersion.fileName.isEmpty()) {
                url = "https://edge.forgecdn.net/files/" + (fileId / 1000) + "/" + (fileId % 1000) + "/" + fileVersion.fileName;
            }
            StringBuilder name = new StringBuilder(str(file, "displayName"));
            JsonArray gameVersions = file.getAsJsonArray("gameVersions");
            if (gameVersions != null) {
                for (JsonElement gv : gameVersions) {
                    String text = gv.getAsString();
                    if (!text.isEmpty() && Character.isDigit(text.charAt(0))) {
                        name.append("  (").append(text).append(")");
                        break;
                    }
                }
            }
            fileVersion.name = name.toString();
            fileVersion.url = url;
            if (!url.isEmpty()) result.add(fileVersion);
        }
        return result;
    }
}
