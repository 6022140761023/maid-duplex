package local.maidbridge.duplex;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

public final class DuplexRuntime {
    private record Turn(BridgeClient.Binding binding, String id, LLMCallback callback, long startedAt) {}
    private static final ConcurrentHashMap<UUID, Turn> ACTIVE = new ConcurrentHashMap<>();
    private DuplexRuntime() {}
    public static boolean boundMaid(EntityMaid maid) {
        return MaidDuplex.ENABLED.get() && maid.getUUID().toString().equalsIgnoreCase(MaidDuplex.MAID.get());
    }
    public static boolean allowSpeaker(EntityMaid maid, ServerPlayer player) {
        if (!boundMaid(maid)) return true;
        expire(maid.getUUID());
        boolean valid = player.getUUID().toString().equalsIgnoreCase(MaidDuplex.PLAYER.get())
                && player.getUUID().equals(maid.getOwnerUUID());
        if (!valid) player.sendSystemMessage(Component.literal("This maid is bound to another player's private conversation."));
        else if (!directEndpoint(maid)) {
            player.sendSystemMessage(Component.literal("Maid Duplex requires a direct HTTPS LLM endpoint. The legacy persona relay is incompatible."));
            return false;
        } else if (ACTIVE.containsKey(maid.getUUID())) {
            player.sendSystemMessage(Component.literal("Maid Duplex: wait for the current reply before sending another message."));
            return false;
        }
        return valid;
    }
    public static boolean directEndpoint(EntityMaid maid) {
        if (!(maid.getAiChatManager().getLLMSite() instanceof LLMOpenAISite site)) return false;
        try {
            URI uri = URI.create(site.url());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getPort() != 8788
                    && !host.equalsIgnoreCase("localhost") && !host.startsWith("127.")
                    && !host.equals("[::1]") && !host.equals("::1");
        } catch (RuntimeException ex) { return false; }
    }
    public static void start(LLMClient client, LLMCallback callback, String rawText) {
        EntityMaid maid = callback.getMaid();
        if (!boundMaid(maid)) { client.chat(callback); return; }
        BridgeClient.Binding b = new BridgeClient.Binding(MaidDuplex.BINDING.get(), MaidDuplex.TOKEN.get(),
                MaidDuplex.WORLD.get(), MaidDuplex.PLAYER.get(), MaidDuplex.MAID.get());
        if (!directEndpoint(maid) || b.id().isBlank() || b.token().isBlank() || b.world().isBlank() || maid.getOwnerUUID() == null
                || !maid.getOwnerUUID().toString().equalsIgnoreCase(b.player())) {
            fail(callback); return;
        }
        expire(maid.getUUID());
        Turn turn = new Turn(b, UUID.randomUUID().toString(), callback, System.nanoTime());
        if (ACTIVE.putIfAbsent(maid.getUUID(), turn) != null) {
            // The existing turn must remain registered for tool follow-ups and its final response.
            LogUtils.getLogger().warn("Maid Duplex: concurrent turn refused");
            return;
        }
        var server = maid.getServer();
        if (server == null) { ACTIVE.remove(maid.getUUID(), turn); fail(callback); return; }
        // No HTTP join/get on the Minecraft server thread. No cached persona fallback.
        BridgeClient.context(b).whenComplete((context, error) -> server.execute(() -> {
            if (ACTIVE.get(maid.getUUID()) != turn) return;
            if (error != null || !boundMaid(maid) || !stillConfigured(b)) {
                ACTIVE.remove(maid.getUUID(), turn); fail(callback); return;
            }
            try {
                List<LLMMessage> messages = callback.getMessages();
                if (messages.isEmpty() || messages.get(0).role() != Role.SYSTEM) throw new IllegalStateException("No TLM system prompt");
                String replaced = PromptPolicy.replaceIdentity(messages.get(0).message(), context.persona(), context.context());
                messages.set(0, LLMMessage.systemChat(maid, replaced));
                BridgeClient.publish(b, turn.id() + ":user", "user", rawText).whenComplete((ignored, publishError) -> server.execute(() -> {
                    if (ACTIVE.get(maid.getUUID()) != turn) return;
                    if (publishError != null || !boundMaid(maid) || !stillConfigured(b)) {
                        ACTIVE.remove(maid.getUUID(), turn); fail(callback); return;
                    }
                    try { client.chat(callback); }
                    catch (RuntimeException ex) { ACTIVE.remove(maid.getUUID(), turn); fail(callback); }
                }));
            } catch (RuntimeException ex) { ACTIVE.remove(maid.getUUID(), turn); fail(callback); }
        }));
    }
    private static boolean stillConfigured(BridgeClient.Binding b) {
        return b.id().equals(MaidDuplex.BINDING.get()) && b.token().equals(MaidDuplex.TOKEN.get())
                && b.world().equals(MaidDuplex.WORLD.get()) && b.player().equals(MaidDuplex.PLAYER.get())
                && b.maid().equals(MaidDuplex.MAID.get());
    }
    public static void completed(LLMCallback callback, String text) {
        EntityMaid maid = callback.getMaid();
        Turn turn = ACTIVE.get(maid.getUUID());
        if (turn == null || turn.callback() != callback || !ACTIVE.remove(maid.getUUID(), turn)) return;
        if (text == null || text.isBlank()) return;
        if (!boundMaid(maid) || !stillConfigured(turn.binding())) return;
        // Idempotent retry: both attempts use the identical message ID.
        BridgeClient.publish(turn.binding(), turn.id() + ":assistant", "assistant", text)
                .exceptionallyCompose(e -> BridgeClient.publish(turn.binding(), turn.id() + ":assistant", "assistant", text))
                .exceptionally(e -> { LogUtils.getLogger().warn("Maid Duplex: final reply sync failed; no tool data was sent"); return null; });
    }
    public static void failed(LLMCallback callback) {
        UUID id = callback.getMaid().getUUID();
        Turn turn = ACTIVE.get(id);
        if (turn != null && turn.callback() == callback) ACTIVE.remove(id, turn);
    }
    /** Only the verified main continuation inherits ownership, never side/summary callbacks. */
    public static void continueMain(LLMCallback source, LLMCallback target) {
        UUID id = source.getMaid().getUUID();
        ACTIVE.computeIfPresent(id, (key, turn) -> turn.callback() == source && target.getMaid() == source.getMaid()
                ? new Turn(turn.binding(), turn.id(), target, turn.startedAt()) : turn);
    }
    private static void expire(UUID id) {
        Turn turn = ACTIVE.get(id);
        if (turn != null && System.nanoTime() - turn.startedAt() > java.time.Duration.ofMinutes(10).toNanos()) {
            ACTIVE.remove(id, turn);
        }
    }
    public static void clear() { ACTIVE.clear(); }
    private static void fail(LLMCallback callback) {
        // Controlled error only: never expose request headers, token, response body, or conversation.
        callback.onFailure(null, new IllegalStateException("Maid Duplex context unavailable; request not sent to model"), 4);
    }
}
