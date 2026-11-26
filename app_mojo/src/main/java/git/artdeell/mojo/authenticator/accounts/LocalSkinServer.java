package git.artdeell.mojo.authenticator.accounts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal local Yggdrasil-style skin server for offline accounts. Used together with authlib-injector, so the
 * game loads the skin saved in the wardrobe without any mod. Only the player's own profile is answered,
 * every other profile is reported as unknown.
 */
public final class LocalSkinServer {
    private static final String PROFILE_PATH = "/sessionserver/session/minecraft/profile/";
    private static final String SKIN_PATH = "/textures/skin.png";
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private final ServerSocket mServer;
    private final String mName;
    private final String mPrimaryId;
    private final Set<String> mIds;
    private final byte[] mSkin;
    private final boolean mSlim;

    private LocalSkinServer(ServerSocket server, String name, Set<String> ids, byte[] skin, boolean slim) {
        mServer = server;
        mName = name;
        mIds = ids;
        mPrimaryId = ids.iterator().next();
        mSkin = skin;
        mSlim = slim;
    }

    /**
     * @param name player name
     * @param ids  profile UUIDs without dashes that get the skin, the first one is used in the profile response
     * @param skin PNG bytes of the skin
     * @param slim whether the skin uses the slim (3 pixel arms) model
     */
    public static LocalSkinServer start(String name, Set<String> ids, byte[] skin, boolean slim) throws IOException {
        ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        LocalSkinServer server = new LocalSkinServer(socket, name, ids, skin, slim);
        Thread acceptor = new Thread(server::acceptLoop, "LocalSkinServer");
        acceptor.setDaemon(true);
        acceptor.start();
        return server;
    }

    /** Address to hand to authlib-injector */
    public String url() {
        return "http://127.0.0.1:" + mServer.getLocalPort();
    }

    public void stop() {
        try {
            mServer.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (!mServer.isClosed()) {
            try {
                Socket client = mServer.accept();
                Thread handler = new Thread(() -> handle(client), "LocalSkinServer-client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(10000);
            InputStream in = socket.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            int contentLength = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(header.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            long toSkip = Math.min(contentLength, 1 << 20);
            while (toSkip > 0) {
                long skipped = in.skip(toSkip);
                if (skipped <= 0) {
                    if (in.read() == -1) break;
                    skipped = 1;
                }
                toSkip -= skipped;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            respond(socket.getOutputStream(), parts[0], parts[1]);
        } catch (IOException ignored) {
        }
    }

    private void respond(OutputStream out, String method, String target) throws IOException {
        int queryIndex = target.indexOf('?');
        String path = queryIndex == -1 ? target : target.substring(0, queryIndex);
        if (path.equals("/") || path.isEmpty()) {
            write(out, 200, "application/json; charset=utf-8", metadata().getBytes(StandardCharsets.UTF_8));
        } else if (path.startsWith(PROFILE_PATH)) {
            String id = path.substring(PROFILE_PATH.length()).replace("-", "").toLowerCase(Locale.ROOT);
            if (mIds.contains(id)) {
                write(out, 200, "application/json; charset=utf-8", profile().getBytes(StandardCharsets.UTF_8));
            } else {
                write(out, 204, null, null);
            }
        } else if (path.equals(SKIN_PATH)) {
            write(out, 200, "image/png", mSkin);
        } else if (path.endsWith("/session/minecraft/join")) {
            write(out, 204, null, null);
        } else if (path.endsWith("/session/minecraft/hasJoined")) {
            write(out, 204, null, null);
        } else if (method.equals("POST") && path.equals("/api/profiles/minecraft")) {
            write(out, 200, "application/json; charset=utf-8", "[]".getBytes(StandardCharsets.UTF_8));
        } else {
            write(out, 404, "application/json; charset=utf-8",
                    "{\"error\":\"NotFound\",\"errorMessage\":\"Not found\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String metadata() {
        return "{\"meta\":{\"serverName\":\"Mojo local skins\",\"implementationName\":\"mojo-local-skin\","
                + "\"implementationVersion\":\"1\"},\"skinDomains\":[\"127.0.0.1\"]}";
    }

    private String profile() {
        String texture = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + mPrimaryId + "\",\"profileName\":\"" + escape(mName)
                + "\",\"textures\":{\"SKIN\":{\"url\":\"" + url() + SKIN_PATH + "\""
                + (mSlim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        return "{\"id\":\"" + mPrimaryId + "\",\"name\":\"" + escape(mName)
                + "\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + base64(texture.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void write(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" : "Not Found";
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (body != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        } else {
            head.append("Content-Length: 0\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("Header line too long");
        }
        if (c == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String base64(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            out.append(BASE64.charAt(b0 >> 2));
            out.append(BASE64.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            out.append(i + 1 < data.length ? BASE64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            out.append(i + 2 < data.length ? BASE64.charAt(b2 & 63) : '=');
        }
        return out.toString();
    }
}
