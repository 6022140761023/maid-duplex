package local.maidbridge.duplex.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import local.maidbridge.duplex.DuplexRuntime;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MaidChatMixin {
    @Inject(method = "chat(Ljava/lang/String;Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/ChatClientInfo;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void duplex$checkSpeaker(String text, ChatClientInfo info, ServerPlayer player, CallbackInfo ci) {
        if (!DuplexRuntime.allowSpeaker(((MaidAIChatManager)(Object)this).getMaid(), player)) ci.cancel();
    }
    @Redirect(method = "normalChat(Ljava/lang/String;Ljava/util/List;Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;)V",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;chat(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)V"))
    private void duplex$prepare(LLMClient receiver, LLMCallback callback, String text, List<LLMMessage> messages, LLMClient originalClient) {
        DuplexRuntime.start(receiver, callback, text);
    }
    @Inject(method = "onSettingIsEmpty(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/ChatClientInfo;Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;)V", at = @At("HEAD"), cancellable = true)
    private void duplex$noAutoPersona(ChatClientInfo info, LLMClient client, CallbackInfo ci) {
        if (DuplexRuntime.boundMaid(((MaidAIChatManager)(Object)this).getMaid())) ci.cancel();
    }
}
