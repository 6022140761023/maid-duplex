package local.maidbridge.duplex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Direct DeepSeek compatibility without altering tool schemas, calls or results. */
public final class ProviderPolicy {
    private ProviderPolicy() {}
    public static String outgoing(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        if (body.has("messages")) for (var message : body.getAsJsonArray("messages")) {
            JsonObject m = message.getAsJsonObject();
            if (m.has("role") && "developer".equals(m.get("role").getAsString())) m.addProperty("role", "system");
        }
        // TLM does not persist reasoning_content across tool follow-ups.
        if (body.has("tools") && body.get("tools").isJsonArray() && !body.getAsJsonArray("tools").isEmpty()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }
        return body.toString();
    }
}
