package com.modtranslator.client;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Optional integrations must never prevent a different mod/version from starting. */
public final class ModTranslatorMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String name) {}
    public String getRefMapperConfig() {return null;}
    public boolean shouldApplyMixin(String target,String mixin) {
        if(!mixin.endsWith(".FancyMenuTextMixin"))return true;
        try {
            ClassNode type=MixinService.getService().getBytecodeProvider().getClassNode(target,false);
            return type.methods.stream().anyMatch(m->m.name.equals("buildRenderText")&&m.desc.equals("()Ljava/lang/String;"))
                    &&type.methods.stream().anyMatch(m->m.name.equals("tick")&&m.desc.equals("()V"))
                    &&type.fields.stream().anyMatch(f->f.name.equals("renderText")&&f.desc.equals("Ljava/lang/String;"))
                    &&type.fields.stream().anyMatch(f->f.name.equals("text")&&f.desc.equals("Ljava/lang/String;"));
        } catch(Exception absent) {return false;}
    }
    public void acceptTargets(Set<String> own,Set<String> other) {}
    public List<String> getMixins() {return null;}
    public void preApply(String name,ClassNode node,String mixin,IMixinInfo info) {}
    public void postApply(String name,ClassNode node,String mixin,IMixinInfo info) {}
}
