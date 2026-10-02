package local.maidbridge.duplex.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import local.maidbridge.duplex.DuplexRuntime;
import local.maidbridge.duplex.ProviderPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.net.http.HttpRequest;

@Mixin(value = LLMOpenAIClient.class, remap = false)
public abstract class OpenAIClientMixin {
    @Inject(method = "chat(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)V", at = @At("HEAD"), cancellable = true)
    private void duplex$endpoint(LLMCallback callback, CallbackInfo ci) {
        if (DuplexRuntime.boundMaid(callback.getMaid()) && !DuplexRuntime.directEndpoint(callback.getMaid())) {
            callback.onFailure(null, new IllegalStateException("Maid Duplex refuses the legacy persona relay; select a direct HTTPS LLM endpoint"), 4);
            ci.cancel();
        }
    }
    @Redirect(method = "chat(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)V",
            at = @At(value = "INVOKE", target = "Ljava/net/http/HttpRequest$BodyPublishers;ofString(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;"))
    private HttpRequest.BodyPublisher duplex$provider(String json, LLMCallback callback) {
        return HttpRequest.BodyPublishers.ofString(DuplexRuntime.boundMaid(callback.getMaid()) ? ProviderPolicy.outgoing(json) : json);
    }
}
