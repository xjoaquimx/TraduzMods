package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import com.modtranslator.core.ModTextPolicy;
import java.util.*;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

/** Display-only integration. Does not change agent commands, decisions or Foreman state. */
public final class AgentCraftTranslation {
    private static final Pattern COMMAND=Pattern.compile("(?is)^(?:(?:Bash|Shell|PowerShell|Command|Comando):\\s*|(?:npm|pnpm|yarn|git|node|python|pip|bash|powershell|curl)\\s+|[>$]\\s+).*");
    private static final Pattern PORTUGUESE=Pattern.compile("(?is).*(?:[ãõçáéíóúâêô]|\\b(?:você|voce|para|com|sem|uma|não|nao|ajuda|neste|mensagem)\\b).*" );
    private static final Pattern ENGLISH=Pattern.compile("(?is).*\\b(?:highlight|publish|explore|draft|parser|module|help|output|install|permission|network|dependency|wants|worker|colors|with|without|the|your|for|from)\\b.*");
    private static final Pattern PROSE=Pattern.compile(".*[\\p{L}].*\\s+.*[\\p{L}].*",Pattern.DOTALL);
    private static final ClassValue<Method> TITLES=new ClassValue<>() {
        protected Method computeValue(Class<?> type) {
            try{Method m=type.getMethod("title");m.trySetAccessible();return m;}catch(ReflectiveOperationException absent){return null;}
        }
    };
    private static boolean foremanAbsent;
    private static Method foremanState;
    private static long nextNamesRefresh;
    private static void refreshNames() {
        long now=System.nanoTime();if(foremanAbsent||now<nextNamesRefresh)return;nextNamesRefresh=now+500_000_000L;
        try {
            if(foremanState==null)foremanState=Class.forName("dev.agentcraft.client.foreman.Foreman").getMethod("state");
            Object state=foremanState.invoke(null);if(state==null)return;
            Object agents=state.getClass().getMethod("agents").invoke(state);
            if(agents instanceof Map<?,?> map){
                Set<String> names=new HashSet<>();
                for(Object agent:map.values()){Object name=agent.getClass().getMethod("name").invoke(agent);if(name instanceof String text&&!text.isBlank())names.add(text);}
                ModTextPolicy.registerDisplayNames(names);
            }
        }catch(ClassNotFoundException absent){foremanAbsent=true;}
        catch(ReflectiveOperationException|RuntimeException unsupported){/* Optional API: retain existing protections. */}
    }
    private AgentCraftTranslation() {}
    public static String text(String source) {
        refreshNames();
        if(ModTextPolicy.isModName(source))return source;
        if(source==null||source.isBlank()||COMMAND.matcher(source.strip()).matches())return source;
        String canonical=TranslationService.translateLiteral(source);
        if(!source.equals(canonical))return canonical;
        if(PORTUGUESE.matcher(source).matches()&&!ENGLISH.matcher(source).matches())return source;
        // Trusted mod UI supplies provenance that generic literal heuristics cannot infer.
        return PROSE.matcher(source).matches()?TranslationService.translateModDocument(null,source):source;
    }
    public static String title(Object task) {
        try {Method method=TITLES.get(task.getClass());if(method==null)return null;
            String source=(String)method.invoke(task);
            return text(source).replace("`", "").replace("**", "").strip();
        } catch(ReflectiveOperationException|RuntimeException unsupported){return null;}
    }
}
