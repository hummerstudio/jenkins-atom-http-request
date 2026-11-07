package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The http-request atom against a real HTTP server on an ephemeral port: no Jenkins, no external network.
 *
 * <p>The server answers {@code /ok.txt} with {@code endpoint-body} and everything else with 404, so the two failure
 * cases (a status the caller did not expect, and a refusal to connect) are real.
 */
class HttpRequestIT {

    private HttpServer server;
    private String base;
    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.txt", exchange -> reply(exchange, 200, "endpoint-body\n"));
        server.createContext("/", exchange -> reply(exchange, 404, "no such file\n"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a 200 puts the status and the body in the outputs and saves the body into the workspace")
    void ok() throws Exception {
        AtomResult run = atom.input("url", base + "/ok.txt?n=${BUILD_NUMBER}")
                .input("method", "GET")
                .input("headers", "X-Build: ${BUILD_NUMBER}")
                .input("body", "")
                .input("expectStatus", "200")
                .input("saveTo", "out/body.txt")
                .input("printBody", "true")
                .input("timeoutSeconds", "20")
                .input("BUILD_NUMBER", "7")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertTrue(run.field("body").startsWith("endpoint-body"),
                "body missing: " + run.document());
        assertEquals("200", run.field("status"));
        assertTrue(run.document().containsKey("savedFile") || run.field("savedFile") != null,
                "savedFile missing: " + run.document());
        assertEquals("endpoint-body", run.file("out/body.txt").stripTrailing(),
                "saved body is not the response");
    }

    @Test
    @DisplayName("a status the caller did not expect fails with the actual code in the message")
    void unexpectedStatus() throws Exception {
        AtomResult run = atom.input("url", base + "/missing.txt")
                .input("method", "GET")
                .input("headers", "")
                .input("body", "")
                .input("expectStatus", "2xx")
                .input("saveTo", "")
                .input("printBody", "false")
                .input("timeoutSeconds", "20")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status(), "404 not reported: " + run.document());
        assertTrue(String.valueOf(run.document().getOrDefault("message", "")).contains("expected 2xx, got 404"),
                "404 message is not readable: " + run.document());
    }

    @Test
    @DisplayName("a host that refuses the connection is an error, not a crash")
    void unreachable() throws Exception {
        AtomResult run = atom.input("url", "http://127.0.0.1:1/none")
                .input("method", "GET")
                .input("headers", "")
                .input("body", "")
                .input("expectStatus", "2xx")
                .input("saveTo", "")
                .input("printBody", "false")
                .input("timeoutSeconds", "3")
                .run();

        run.assertRefused();
        assertEquals("error", run.status(), "connection error not reported: " + run.document());
        assertFalse(run.console().contains("Exception in thread"),
                "the atom crashed instead of reporting:\n" + run.console());
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
