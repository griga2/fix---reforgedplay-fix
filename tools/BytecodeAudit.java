import java.util.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.tree.analysis.*;

/** Structural/data-flow audit without loading Minecraft classes. */
public class BytecodeAudit {
    record Result(int classes, int methods, Map<String,String> failures) {}

    static Result audit(String file) throws Exception {
        int classes=0, methods=0;
        Map<String,String> failures=new TreeMap<>();
        try (ZipFile zip=new ZipFile(file)) {
            for (var entries=zip.entries();entries.hasMoreElements();) {
                var entry=entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                ClassNode c=new ClassNode();
                new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(c, ClassReader.EXPAND_FRAMES);
                classes++;
                for (MethodNode m:c.methods) {
                    if ((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0) continue;
                    methods++;
                    try {
                        new Analyzer<BasicValue>(new BasicVerifier()).analyze(c.name,m);
                    } catch (AnalyzerException ex) {
                        failures.put(c.name+"."+m.name+m.desc,ex.getMessage());
                    }
                }
            }
        }
        return new Result(classes,methods,failures);
    }

    public static void main(String[] args) throws Exception {
        Result base=audit(args[0]), fixed=audit(args[1]);
        System.out.println("BASE: "+base.classes+" classes, "+base.methods+" methods, "+base.failures.size()+" analyzer failures");
        System.out.println("FIXED: "+fixed.classes+" classes, "+fixed.methods+" methods, "+fixed.failures.size()+" analyzer failures");
        if(base.classes!=fixed.classes || base.methods!=fixed.methods) throw new AssertionError("Membership changed");
        for(var failure:fixed.failures.entrySet()) {
            if(!failure.getValue().equals(base.failures.get(failure.getKey()))) {
                throw new AssertionError("New analyzer failure: "+failure);
            }
        }
        if (!base.failures.isEmpty()) System.out.println("Inherited analyzer failures: "+base.failures);
        System.out.println("PASS: no new structural/data-flow failures (no class hierarchy or runtime Mixin verification).");
    }
}
