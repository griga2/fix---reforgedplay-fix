import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.tree.analysis.*;
public class Patch {
 public static void main(String[] args) throws Exception {
  int patched=0, classes=0, methods=0;
  try(ZipFile in=new ZipFile(args[0]); ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
   Set<String> names=new HashSet<>();
   for(var entries=in.entries();entries.hasMoreElements();) {
    ZipEntry e=entries.nextElement();if(!names.add(e.getName()))throw new Exception("Duplicate "+e.getName());
    byte[] b=in.getInputStream(e).readAllBytes();
    if(e.getName().endsWith(".class")) {
     ClassNode c=new ClassNode();new ClassReader(b).accept(c,0);classes++;
     boolean changed=false;
     for(MethodNode m:c.methods) {
      for(AbstractInsnNode ins:m.instructions.toArray()) {
       if(ins instanceof MethodInsnNode call && call.owner.startsWith("net/neoforged/") && Set.of("getModFileById","getModContainerById","isLoaded").contains(call.name)) {
        AbstractInsnNode prev=ins.getPrevious();while(prev!=null && prev.getOpcode()<0)prev=prev.getPrevious();
        System.out.println("LOOKUP "+c.name+"."+m.name+" "+call.owner+"."+call.name+" argument="+(prev instanceof LdcInsnNode l?l.cst:"dynamic"));
        if(Set.of("com/replaymod/core/versions/LangResourcePack", "com/replaymod/core/ReplayModBackend", "com/replaymod/render/capturer/IrisODSFrameCapturer").contains(c.name) && call.name.equals("getModFileById") && prev instanceof LdcInsnNode l && "replaymod".equals(l.cst)) {
         l.cst="reforgedplaymod";patched++;changed=true;
        }
       }
      }
      if(changed && m.name.equals("<init>"))for(AbstractInsnNode ins:m.instructions.toArray())if(ins instanceof LdcInsnNode l && "Could not find ReplayMod container for replaymod".equals(l.cst))l.cst="Could not find ReplayMod container for reforgedplaymod";
     }
     if(changed) {
      ClassWriter w=new ClassWriter(0);c.accept(w);b=w.toByteArray();
      ClassNode reread=new ClassNode();new ClassReader(b).accept(reread,ClassReader.EXPAND_FRAMES);
      for(MethodNode m:reread.methods)if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0){new Analyzer<BasicValue>(new BasicVerifier()).analyze(reread.name,m);methods++;}
     }
    }
    ZipEntry target=new ZipEntry(e.getName());target.setTime(e.getTime());out.putNextEntry(target);out.write(b);out.closeEntry();
   }
  }
  if(patched!=3)throw new Exception("Expected three patches, got "+patched);
  System.out.println("Parsed classes="+classes+"; patched lookup="+patched+"; verified methods="+methods);
 }
}
