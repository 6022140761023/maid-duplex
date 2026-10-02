package local.maidbridge.duplex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeClientTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> body = new AtomicReference<>("{\"persona\":\"测试人设\",\"context\":\"DSH: 你好\",\"personaVersion\":\"version-one\"}");
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        AtomicReference<JsonObject> message = new AtomicReference<>();
        server.createContext("/v1/context", exchange -> {
            try {
                check("Bearer synthetic-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "auth header");
                HashMap<String, String> query = new HashMap<>();
                for (String entry : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] pair = entry.split("=", 2);
                    query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
                check(query.equals(java.util.Map.of("binding", "session / 单独", "world", "world-1", "player", "player-1", "maid", "maid-1")), "binding tuple");
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable e) { handlerFailure.set(e); }
            finally { exchange.close(); }
        });
        server.createContext("/v1/messages", exchange -> {
            try {
                check("Bearer synthetic-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "publish auth");
                message.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                exchange.sendResponseHeaders(204, -1);
            } catch (Throwable e) { handlerFailure.set(e); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BridgeClient.Binding binding = new BridgeClient.Binding("session / 单独", "synthetic-test-token", "world-1", "player-1", "maid-1");
            var context = BridgeClient.contextAt(base, binding).get(10, TimeUnit.SECONDS);
            check(context.persona().equals("测试人设") && context.context().equals("DSH: 你好") && context.version().equals("version-one"), "UTF8 context");
            check(handlerFailure.get() == null, "GET contract");
            BridgeClient.publishAt(base, binding, "turn-1:user", "user", "切换战斗模式").get(10, TimeUnit.SECONDS);
            JsonObject sent = message.get();
            check(sent.keySet().equals(Set.of("binding", "world", "player", "maid", "id", "role", "text")), "only plain-text envelope");
            check(sent.get("binding").getAsString().equals(binding.id()) && sent.get("world").getAsString().equals(binding.world())
                    && sent.get("player").getAsString().equals(binding.player()) && sent.get("maid").getAsString().equals(binding.maid()), "POST binding");
            check(sent.get("id").getAsString().equals("turn-1:user") && sent.get("text").getAsString().equals("切换战斗模式"), "stable ID and UTF8 text");
            check(handlerFailure.get() == null, "POST auth contract");
            status.set(503);
            rejects(() -> BridgeClient.contextAt(base, binding).get(10, TimeUnit.SECONDS));
            status.set(200); body.set("{\"persona\":\"\",\"context\":\"\",\"personaVersion\":\"x\"}");
            rejects(() -> BridgeClient.contextAt(base, binding).get(10, TimeUnit.SECONDS));
            rejects(() -> BridgeClient.publishAt(base, binding, "turn-1:tool", "tool", "private result").get(10, TimeUnit.SECONDS));
            System.out.println("BridgeClient: PASS 9 / FAIL 0");
        } finally { server.stop(0); }
    }
    interface Attempt { void run() throws Exception; }
    private static void rejects(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (java.util.concurrent.ExecutionException expected) { return; }
        throw new AssertionError("Expected fail-closed rejection");
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}
