package local.maidbridge.duplex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class BridgeClient {
    public record Binding(String id, String token, String world, String player, String maid) {}
    public record Context(String persona, String context, String version) {}
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String BASE = "http://127.0.0.1:8790";
    private BridgeClient() {}
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static HttpRequest.Builder request(String base, String path, Binding b) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + b.token());
    }
    public static CompletableFuture<Context> context(Binding b) {
        return contextAt(BASE, b);
    }
    static CompletableFuture<Context> contextAt(String base, Binding b) {
        String query = "?binding=" + enc(b.id()) + "&world=" + enc(b.world())
                + "&player=" + enc(b.player()) + "&maid=" + enc(b.maid());
        return HTTP.sendAsync(request(base, "/v1/context" + query, b).GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() != 200 || r.body().length() > 262144) throw new IllegalStateException("Context unavailable");
                    JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
                    String persona = j.get("persona").getAsString();
                    String version = j.get("personaVersion").getAsString();
                    if (persona.isBlank() || version.isBlank()) throw new IllegalStateException("Bound persona unavailable");
                    return new Context(persona, j.get("context").getAsString(), version);
                });
    }
    public static CompletableFuture<Void> publish(Binding b, String id, String role, String text) {
        return publishAt(BASE, b, id, role, text);
    }
    static CompletableFuture<Void> publishAt(String base, Binding b, String id, String role, String text) {
        if (!(role.equals("user") || role.equals("assistant"))) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid role"));
        JsonObject j = new JsonObject();
        j.addProperty("binding", b.id()); j.addProperty("world", b.world());
        j.addProperty("player", b.player()); j.addProperty("maid", b.maid());
        j.addProperty("id", id); j.addProperty("role", role); j.addProperty("text", text);
        return HTTP.sendAsync(request(base, "/v1/messages", b).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(j.toString())).build(), HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> { if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IllegalStateException("Publish unavailable"); });
    }
}
