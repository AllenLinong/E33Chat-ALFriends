package com.alinegames.alfriends.client.image;

import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Uploads image bytes to a file host and returns the resulting URL.
 *
 * Default host: Litterbox (litterbox.catbox.moe) — Catbox's sister domain.
 * catbox.moe itself was unreachable in the user's network (HTTP 000) while
 * litterbox.catbox.moe works, and 0x0.st has uploads disabled. Litterbox
 * files expire (default 72h); a custom host can be configured instead.
 *
 * Custom host config: POST url with multipart/form-data (file field), plus
 * optional extra key=value fields (comma-separated, e.g. "time=72h") and a
 * response mode: "text" (response body IS the URL) or "json:<field>"
 * (extract the URL from a JSON object field).
 */
public final class ImageUploader {
    private static final Logger LOGGER = LogManager.getLogger("alfriendschat");

    public static final String DEFAULT_URL = "https://litterbox.catbox.moe/resources/internals/api.php";
    public static final String DEFAULT_FIELD = "fileToUpload";
    // Litterbox requires reqtype=fileupload; omitting it returns 412 "No request type given"
    public static final String DEFAULT_EXTRA = "reqtype=fileupload,time=72h";
    public static final String DEFAULT_RESPONSE = "text";
    public static final String BACKUP_URL = "https://catbox.moe/user/api.php";
    public static final String BACKUP_FIELD = "fileToUpload";
    public static final String BACKUP_EXTRA = "reqtype=fileupload";
    public static final String UGUU_URL = "https://uguu.se/upload.php";
    public static final String UGUU_FIELD = "files[]";
    public static final String UGUU_EXTRA = "";
    public static final String TMPFILES_URL = "https://tmpfiles.org/api/v1/upload";
    public static final String TMPFILES_FIELD = "file";
    public static final String TMPFILES_EXTRA = "";
    public static final String FILEIO_URL = "https://file.io";
    public static final String FILEIO_FIELD = "file";
    public static final String FILEIO_EXTRA = "";

    private static final int MAX_UPLOAD_BYTES = 16 * 1024 * 1024;
    private static final long UPLOAD_TIMEOUT_SECONDS = 30;

    private ImageUploader() {}

    /** Synchronous upload (call on a worker thread). Tries every HTTP host before failing. */
    public static String upload(byte[] bytes, String fileName,
                                String url, String field, String extra, String responseMode) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) {
            LOGGER.info("[alfriendschat] upload skipped: {} bytes", bytes == null ? 0 : bytes.length);
            return null;
        }

        String endpoint = (url == null || url.isBlank()) ? DEFAULT_URL : url.trim();
        String fld = (field == null || field.isBlank()) ? DEFAULT_FIELD : field.trim();
        String extraFields = (extra == null || extra.isBlank()) ? DEFAULT_EXTRA : extra;
        if (endpoint.equals(DEFAULT_URL) && !extraFields.contains("reqtype")) {
            extraFields = "reqtype=fileupload," + extraFields;
        }
        String mode = (responseMode == null || responseMode.isBlank()) ? DEFAULT_RESPONSE : responseMode.trim();

        UploadTarget[] targets = {
            new UploadTarget(endpoint, fld, extraFields, mode),
            new UploadTarget(BACKUP_URL, BACKUP_FIELD, BACKUP_EXTRA, DEFAULT_RESPONSE),
            new UploadTarget(UGUU_URL, UGUU_FIELD, UGUU_EXTRA, "json:files.0.url"),
            new UploadTarget(TMPFILES_URL, TMPFILES_FIELD, TMPFILES_EXTRA, "json:data.url"),
            new UploadTarget(FILEIO_URL, FILEIO_FIELD, FILEIO_EXTRA, "json:link")
        };
        java.util.Set<String> attempted = new java.util.HashSet<>();
        for (UploadTarget target : targets) {
            if (!attempted.add(target.endpoint())) continue;
            LOGGER.info("[alfriendschat] trying HTTP image host {}", target.endpoint());
            String result = uploadToEndpoint(bytes, fileName, target.endpoint(), target.field(),
                target.extra(), target.responseMode());
            if (result != null) return result;
            LOGGER.info("[alfriendschat] HTTP image host failed; continuing to next backup");
        }
        LOGGER.info("[alfriendschat] all HTTP image hosts failed");
        return null;
    }

    private record UploadTarget(String endpoint, String field, String extra, String responseMode) {}

    private static String uploadToEndpoint(byte[] bytes, String fileName, String endpoint,
                                           String field, String extra, String responseMode) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String boundary = "----alfriendschat" + UUID.randomUUID().toString().replace("-", "");
                byte[] body = buildMultipart(bytes, fileName, field, extra, boundary);
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                    .header("User-Agent", "E33Chat-ALFriends/0.1")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
                HttpResponse<String> resp = ImageLoader.client().send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String out = extractUrl(resp.body(), responseMode);
                    if (out != null) return out;
                    LOGGER.info("[alfriendschat] upload {} -> unparsable response: {}", endpoint, resp.body());
                    if (attempt == 0) Thread.sleep(1000L);
                    continue;
                }
                boolean retry = resp.statusCode() >= 500 && attempt == 0;
                LOGGER.info("[alfriendschat] upload {} -> HTTP {}{}: {}", endpoint, resp.statusCode(),
                    retry ? " (retrying)" : "", resp.body());
                if (retry) Thread.sleep(1000L);
                else break;
            }
        } catch (Throwable t) {
            LOGGER.info("[alfriendschat] upload {} -> exception: {}", endpoint, t.toString());
        }
        return null;
    }

    /** multipart/form-data body: extra key=value parts first, then the file part. */
    static byte[] buildMultipart(byte[] fileBytes, String fileName, String field,
                                 String extra, String boundary) {
        StringBuilder head = new StringBuilder();
        if (extra != null && !extra.isBlank()) {
            for (String kv : extra.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                if (k.isEmpty() || v.isEmpty()) continue;
                head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                    .append(v).append("\r\n");
            }
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(field)
            .append("\"; filename=\"").append(sanitizeFileName(fileName)).append("\"\r\n")
            .append("Content-Type: application/octet-stream\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headBytes.length + fileBytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(fileBytes, 0, out, headBytes.length, fileBytes.length);
        System.arraycopy(tailBytes, 0, out, headBytes.length + fileBytes.length, tailBytes.length);
        return out;
    }

    /** Response → URL. text: the body is the URL. json:<field>: extract from a JSON object. */
    public static String extractUrl(String responseBody, String responseMode) {
        if (responseBody == null) return null;
        String body = responseBody.trim();
        if (body.isEmpty()) return null;
        String mode = (responseMode == null || responseMode.isBlank()) ? DEFAULT_RESPONSE : responseMode.trim();
        if (mode.startsWith("json:")) {
            String field = mode.substring(5).trim();
            try {
                var el = JsonParser.parseString(body);
                for (String part : field.split("\\.")) {
                    if (part.matches("\\d+") && el.isJsonArray()) {
                        int index = Integer.parseInt(part);
                        if (index < 0 || index >= el.getAsJsonArray().size()) return null;
                        el = el.getAsJsonArray().get(index);
                    } else if (el.isJsonObject() && el.getAsJsonObject().has(part)) {
                        el = el.getAsJsonObject().get(part);
                    } else {
                        return null;
                    }
                }
                if (el.isJsonPrimitive()) {
                    String v = el.getAsString().trim();
                    return isHttpUrl(v) ? v : null;
                }
            } catch (Throwable t) {
                return null;
            }
            return null;
        }
        // text mode: Litterbox/Catbox reply with the bare URL
        return isHttpUrl(body) ? body : null;
    }

    private static boolean isHttpUrl(String s) {
        String lower = s.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        if (s.length() >= 2048) return false;
        try {
            return new URI(s).getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "image.png";
        String n = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.length() > 64 ? n.substring(n.length() - 64) : n;
    }
}
