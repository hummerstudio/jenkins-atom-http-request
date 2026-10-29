import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP request as an atom: the port of the {@code http_request} plugin.
 *
 * <p>Reads {@code input.json} (plus {@code .secrets.json} for a bearer token or basic credentials), calls the endpoint
 * from the agent, and reports status/headers/body as outputs. Nothing about Jenkins is needed — this is exactly the
 * kind of plugin that should never have been a plugin.
 */
public class HttpRequestAtom {

    private static final int MAX_OUTPUT_BODY = 256 * 1024;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getenv("atom_data_dir"));
        String inputName = or(System.getenv("atom_data_input"), "input.json");
        String outputName = or(System.getenv("atom_data_output"), "output.json");
        String workspace = or(System.getenv("atom_workspace"), System.getProperty("user.dir"));
        String input = Files.readString(dir.resolve(inputName), StandardCharsets.UTF_8);
        Map<String, String> env = System.getenv();

        String url = expand(text(input, "url", ""), env);
        if (url.isBlank()) {
            fail(dir, outputName, "url is required", 40001);
            return;
        }
        String method = text(input, "method", "GET").toUpperCase(java.util.Locale.ROOT);
        String headers = text(input, "headers", "");
        String body = expand(text(input, "body", ""), env);
        String expect = text(input, "expectStatus", "2xx");
        String saveTo = text(input, "saveTo", "");
        boolean printBody = Boolean.parseBoolean(text(input, "printBody", "true"));
        int timeout = parseInt(text(input, "timeoutSeconds", "60"), 60);

        Map<String, String> secrets = secrets(dir, inputName);
        String token = secrets.getOrDefault("token", "");
        String user = text(input, "user", "");
        String password = secrets.getOrDefault("password", "");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout));
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);

        for (String line : headers.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                fail(dir, outputName, "malformed header line: " + trimmed, 40002);
                return;
            }
            builder.header(trimmed.substring(0, colon).trim(), expand(trimmed.substring(colon + 1).trim(), env));
        }
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        } else if (!user.isBlank()) {
            builder.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }

        long started = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, timeout)))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail(dir, outputName, method + " " + url + " failed: " + e, 40003);
            return;
        }
        long duration = System.currentTimeMillis() - started;

        String responseBody = response.body() == null ? "" : response.body();
        StringBuilder headerText = new StringBuilder();
        response.headers().map().forEach((name, values) -> values.forEach(value -> headerText
                .append(name)
                .append(": ")
                .append(value)
                .append('\n')));

        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("status", String.valueOf(response.statusCode()));
        outputs.put(
                "body",
                responseBody.length() > MAX_OUTPUT_BODY
                        ? responseBody.substring(0, MAX_OUTPUT_BODY)
                        : responseBody);
        outputs.put("headers", headerText.toString().strip());
        outputs.put("durationMs", String.valueOf(duration));

        String savedPath = "";
        if (!saveTo.isBlank()) {
            Path target = Path.of(workspace).resolve(saveTo).normalize();
            if (!target.startsWith(Path.of(workspace))) {
                fail(dir, outputName, "saveTo must stay inside the workspace: " + saveTo, 40004);
                return;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, responseBody, StandardCharsets.UTF_8);
            savedPath = target.toString();
        }

        System.out.println("[http-request] " + method + " " + url + " -> " + response.statusCode() + " ("
                + duration + " ms, " + responseBody.length() + " bytes)");
        if (printBody && !responseBody.isEmpty()) {
            System.out.println(responseBody);
        }

        boolean ok = matches(expect, response.statusCode());
        StringBuilder json = new StringBuilder("{\"status\":\"" + (ok ? "success" : "failure") + "\",");
        if (!ok) {
            json.append("\"errorType\":1,\"errorCode\":40005,\"message\":\"")
                    .append(escape("expected " + expect + ", got " + response.statusCode()))
                    .append("\",");
        }
        json.append("\"data\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":{\"type\":\"string\",\"value\":\"")
                    .append(escape(entry.getValue())).append("\"}");
        }
        if (!savedPath.isEmpty()) {
            json.append(",\"savedFile\":{\"type\":\"artifact\",\"value\":[\"")
                    .append(escape(savedPath))
                    .append("\"]}");
        }
        json.append("}}");
        Files.writeString(dir.resolve(outputName), json.toString(), StandardCharsets.UTF_8);
        if (!ok) {
            System.exit(1);
        }
    }

    /** {@code 200}, {@code 2xx}, {@code 200,201} or {@code 2xx,3xx}. */
    private static boolean matches(String expect, int status) {
        String wanted = expect.isBlank() ? "2xx" : expect;
        for (String part : wanted.split(",")) {
            String token = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (token.endsWith("xx") && token.length() == 3) {
                if (status / 100 == Character.getNumericValue(token.charAt(0))) {
                    return true;
                }
            } else if (token.equals(String.valueOf(status))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> secrets(Path dir, String inputName) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = dir.resolve(".secrets.json");
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            while (matcher.find()) {
                values.putIfAbsent(unescape(matcher.group(1)), unescape(matcher.group(2)));
            }
        } catch (Exception e) {
            System.out.println("[http-request] cannot read .secrets.json: " + e);
        }
        return values;
    }

    private static void fail(Path dir, String outputName, String message, int code) throws Exception {
        Files.writeString(
                dir.resolve(outputName),
                "{\"status\":\"error\",\"errorType\":1,\"errorCode\":" + code + ",\"message\":\"" + escape(message)
                        + "\"}",
                StandardCharsets.UTF_8);
        System.out.println("[http-request] ERROR " + message);
        System.exit(1);
    }

    private static String expand(String template, Map<String, String> env) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = env.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String text(String json, String key, String fallback) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : fallback;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
