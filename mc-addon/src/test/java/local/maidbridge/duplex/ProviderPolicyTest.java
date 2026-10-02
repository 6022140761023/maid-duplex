package local.maidbridge.duplex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ProviderPolicyTest {
    public static void main(String[] args) {
        String source = """
                {"model":"test","messages":[
                {"role":"developer","content":"MC context"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"abc","type":"function","function":{"name":"switch_work_task","arguments":"{}"}}]},
                {"role":"tool","tool_call_id":"abc","content":"done"}],
                "tools":[{"type":"function","function":{"name":"switch_work_task","parameters":{"type":"object"}}}],
                "response_format":{"type":"text"},"temperature":0.8,"thinking":{"type":"enabled"}}
                """;
        JsonObject before = JsonParser.parseString(source).getAsJsonObject();
        JsonObject after = JsonParser.parseString(ProviderPolicy.outgoing(source)).getAsJsonObject();
        check(after.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString().equals("system"), "developer normalized");
        check(after.getAsJsonObject("thinking").get("type").getAsString().equals("disabled"), "thinking guard");
        check(after.get("tools").equals(before.get("tools")), "tool schemas retained");
        check(after.getAsJsonArray("messages").get(1).equals(before.getAsJsonArray("messages").get(1)), "tool calls retained");
        check(after.getAsJsonArray("messages").get(2).equals(before.getAsJsonArray("messages").get(2)), "tool results retained locally");
        check(after.get("model").equals(before.get("model")) && after.get("temperature").equals(before.get("temperature")), "model controls retained");
        check(after.get("response_format").equals(before.get("response_format")), "text contract retained");
        check(JsonParser.parseString(ProviderPolicy.outgoing(after.toString())).equals(after), "idempotent");
        String noTools = "{\"messages\":[],\"thinking\":{\"type\":\"enabled\"}}";
        check(JsonParser.parseString(ProviderPolicy.outgoing(noTools)).equals(JsonParser.parseString(noTools)), "no tools preserves thinking");
        System.out.println("ProviderPolicy: PASS 9 / FAIL 0");
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}
