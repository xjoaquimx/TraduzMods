package com.modtranslator.client;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Optional integrations must never prevent a different mod/version from starting.
 * Request transformed bytecode: NeoForge FML explicitly rejects untransformed lookups. */
public final class ModTranslatorMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String name) {}
    public String getRefMapperConfig() {return null;}
    public boolean shouldApplyMixin(String target,String mixin) {
        if(mixin.contains(".AgentCraft")) {
            try {
                ClassNode type=MixinService.getService().getBytecodeProvider().getClassNode(target,true);
                if(mixin.endsWith("BoardMixin"))return type.fields.stream().anyMatch(f->f.name.equals("taskSeq")&&f.desc.equals("J"))&&type.methods.stream().anyMatch(m->m.name.equals("sync"))&&type.methods.stream().anyMatch(m->m.name.equals("titleText"));
                if(mixin.endsWith("ScreenMixin"))return type.fields.stream().anyMatch(f->f.name.equals("revision")&&f.desc.equals("J"))&&type.methods.stream().anyMatch(m->m.name.equals("refresh"));
                if(mixin.endsWith("WorldTextMixin"))return type.methods.stream().anyMatch(m->m.name.equals("submitText")&&m.desc.contains("FormattedCharSequence;"));
                return java.util.List.of("ellipsize","wrap","wrapPlain").stream().allMatch(name->type.methods.stream().anyMatch(m->m.name.equals(name)));
            }catch(Exception absent){return false;}
        }

        if(mixin.endsWith(".GuideMeDocumentMixin")) {
            try {
                ClassNode type=MixinService.getService().getBytecodeProvider().getClassNode(target,true);
                return type.methods.stream().anyMatch(m->m.name.equals("ensureDocumentLayout")&&m.desc.equals("()V"))
                        &&type.methods.stream().anyMatch(m->m.name.equals("getDocument")&&m.desc.equals("()Lguideme/document/block/LytDocument;"))
                        &&type.fields.stream().anyMatch(f->f.name.equals("documentLayoutInvalid")&&f.desc.equals("Z"));
            } catch(Exception absent) {
                System.getLogger("TraduzMods/Mixin").log(System.Logger.Level.WARNING,"Optional integration unavailable: "+mixin+" -> "+target,absent);
                return false;
            }
        }
        if(mixin.endsWith(".AetherDocumentMixin")) {
            try {
                ClassNode type=MixinService.getService().getBytecodeProvider().getClassNode(target,true);
                return type.methods.stream().anyMatch(m->m.name.equals("createPages")&&m.desc.equals("("+org.objectweb.asm.Type.getDescriptor(net.minecraft.network.chat.Component.class)+")V"))
                        &&type.fields.stream().anyMatch(f->f.name.equals("pages")&&f.desc.equals("Ljava/util/Map;"))
                        &&type.fields.stream().anyMatch(f->f.name.equals("currentPageNumber")&&f.desc.equals("I"));
            } catch(Exception absent) {
                System.getLogger("TraduzMods/Mixin").log(System.Logger.Level.WARNING,"Optional integration unavailable: "+mixin+" -> "+target,absent);
                return false;
            }
        }
        if(!mixin.endsWith(".FancyMenuTextMixin"))return true;
        try {
            ClassNode type=MixinService.getService().getBytecodeProvider().getClassNode(target,true);
            return type.methods.stream().anyMatch(m->m.name.equals("buildRenderText")&&m.desc.equals("()Ljava/lang/String;"))
                    &&type.methods.stream().anyMatch(m->m.name.equals("tick")&&m.desc.equals("()V"))
                    &&type.fields.stream().anyMatch(f->f.name.equals("renderText")&&f.desc.equals("Ljava/lang/String;"))
                    &&type.fields.stream().anyMatch(f->f.name.equals("text")&&f.desc.equals("Ljava/lang/String;"));
        } catch(Exception absent) {
                System.getLogger("TraduzMods/Mixin").log(System.Logger.Level.WARNING,"Optional integration unavailable: "+mixin+" -> "+target,absent);
                return false;
            }
    }
    public void acceptTargets(Set<String> own,Set<String> other) {}
    public List<String> getMixins() {return null;}
    public void preApply(String name,ClassNode node,String mixin,IMixinInfo info) {}
    public void postApply(String name,ClassNode node,String mixin,IMixinInfo info) {}
}
