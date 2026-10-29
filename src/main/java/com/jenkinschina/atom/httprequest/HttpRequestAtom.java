package com.jenkinschina.atom.httprequest;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * http_request as an atom, written against the SDK.
 *
 * <p>Parameters, secrets and the result shape come from the SDK; what is left is the call itself and the rule that an
 * unexpected status is a failure with the actual code in the message.
 */
public class HttpRequestAtom implements AtomTask {

    private static final int MAX_OUTPUT_BODY = 256 * 1024;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String url = expand(context, context.getParam("url", ""));
        if (url.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "url is required");
        }
        String method = context.getParam("method", "GET").toUpperCase(Locale.ROOT);
        String headers = expand(context, context.getParam("headers", ""));
        String body = expand(context, context.getParam("body", ""));
        String expect = context.getParam("expectStatus", "2xx");
        String saveTo = context.getParam("saveTo", "");
        boolean printBody = context.getBooleanParam("printBody", true);
        int timeout = context.getIntParam("timeoutSeconds", 60);

        String token = orEmpty(context.getSecret("token"));
        String user = context.getParam("user", "");
        String password = orEmpty(context.getSecret("password"));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout));
        builder.method(
                method,
                body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        for (String line : headers.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                return AtomResult.error(ErrorType.USER, 40002, "malformed header line: " + trimmed);
            }
            builder.header(trimmed.substring(0, colon).trim(), expand(context, trimmed.substring(colon + 1).trim()));
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
            return AtomResult.error(ErrorType.THIRD_PART, 40003, method + " " + url + " failed: " + e);
        }
        long duration = System.currentTimeMillis() - started;

        String responseBody = response.body() == null ? "" : response.body();
        StringBuilder headerText = new StringBuilder();
        response.headers().map().forEach((name, values) -> values.forEach(value -> headerText
                .append(name)
                .append(": ")
                .append(value)
                .append('\n')));

        context.log("[http-request] " + method + " " + url + " -> " + response.statusCode() + " (" + duration + " ms, "
                + responseBody.length() + " bytes)");
        if (printBody && !responseBody.isEmpty()) {
            context.log(responseBody);
        }

        AtomResult result = AtomResult.success()
                .data("status", String.valueOf(response.statusCode()))
                .data(
                        "body",
                        responseBody.length() > MAX_OUTPUT_BODY
                                ? responseBody.substring(0, MAX_OUTPUT_BODY)
                                : responseBody)
                .data("headers", headerText.toString().strip())
                .data("durationMs", String.valueOf(duration));

        if (!saveTo.isBlank()) {
            Path workspace = context.getWorkspace();
            Path target = workspace.resolve(saveTo).normalize();
            if (!target.startsWith(workspace)) {
                return AtomResult.error(ErrorType.USER, 40004, "saveTo must stay inside the workspace: " + saveTo);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, responseBody, StandardCharsets.UTF_8);
            result.artifact("savedFile", target.toString());
        }

        if (!matches(expect, response.statusCode())) {
            return AtomResult.failure("expected " + expect + ", got " + response.statusCode())
                    .data("status", String.valueOf(response.statusCode()))
                    .data("body", responseBody.length() > MAX_OUTPUT_BODY
                            ? responseBody.substring(0, MAX_OUTPUT_BODY)
                            : responseBody);
        }
        return result;
    }

    /** {@code 200}, {@code 2xx}, {@code 200,201} or {@code 2xx,3xx}. */
    private static boolean matches(String expect, int status) {
        String wanted = expect.isBlank() ? "2xx" : expect;
        for (String part : wanted.split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
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

    private static String expand(AtomContext context, String template) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = context.getParam(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
