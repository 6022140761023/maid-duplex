package local.maidbridge.duplex.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import local.maidbridge.duplex.DuplexRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.net.http.HttpRequest;

@Mixin(value = LLMCallback.class, remap = false)
public abstract class CallbackMixin {
    // After TLM validates nonblank chat/tts; never hook onFunctionCall or onSuccess(Object).
    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;addAssistantHistory(Ljava/lang/String;)V"))
    private void duplex$final(ResponseChat response, CallbackInfo ci) {
        DuplexRuntime.completed((LLMCallback)(Object)this, response.getChatText());
    }
    @Inject(method = "onFailure(Ljava/net/http/HttpRequest;Ljava/lang/Throwable;I)V", at = @At("HEAD"))
    private void duplex$error(HttpRequest request, Throwable error, int code, CallbackInfo ci) {
        DuplexRuntime.failed((LLMCallback)(Object)this);
    }
    // Bytecode ordinal 0 dispatches sideCallbacks; ordinal 1 dispatches nextCallback.
    @Redirect(method = "lambda$onFunctionCall$2",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;chat(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)V", ordinal = 1))
    private void duplex$mainContinuation(LLMClient client, LLMCallback next) {
        DuplexRuntime.continueMain((LLMCallback)(Object)this, next);
        client.chat(next);
    }
}
