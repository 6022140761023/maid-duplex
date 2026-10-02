package local.maidbridge.duplex;

public final class PromptPolicyTest {
    public static void main(String[] args) {
        String template = "## Character Setting\nOLD_PERSONA\n### Core Logic\nOLD_CORE\n"
                + "## World Context\nMinecraft. Master (Chinese is '主人')\n"
                + "## State & Sensing\nKEEP_SENSING\n## Execution Protocol\nKEEP_TOOLS\n"
                + "## Output Format Requirements\nKEEP_FORMAT\n---\n";
        String actual = PromptPolicy.replaceIdentity(template, "DSH_PERSONA", "shared user: hi");
        check(!actual.contains("OLD_PERSONA") && !actual.contains("OLD_CORE"), "identity removed");
        check(actual.contains("Minecraft.") && actual.contains("KEEP_SENSING"), "MC context retained");
        check(actual.contains("KEEP_TOOLS") && actual.contains("KEEP_FORMAT\n---"), "contracts retained");
        check(!actual.contains("Master (Chinese is '主人')"), "fixed address removed");
        check(actual.contains("DSH_PERSONA") && actual.contains("shared user: hi"), "bound context included");
        expectFailure(() -> PromptPolicy.replaceIdentity(template, "", ""));
        expectFailure(() -> PromptPolicy.replaceIdentity("unknown template", "DSH", ""));
        System.out.println("PromptPolicy: PASS 7 / FAIL 0");
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static void expectFailure(Runnable test) {
        try { test.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected fail-closed rejection");
    }
}
