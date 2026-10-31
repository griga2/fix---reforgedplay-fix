import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.tree.analysis.*;
public class Patch {
 public static void main(String[] args) throws Exception {
  int patched=0, classes=0, methods=0, annotations=0;
  try(ZipFile in=new ZipFile(args[0]); ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
   Set<String> names=new HashSet<>();
   for(var entries=in.entries();entries.hasMoreElements();) {
    ZipEntry e=entries.nextElement();if(!names.add(e.getName()))throw new Exception("Duplicate "+e.getName());
    byte[] b=in.getInputStream(e).readAllBytes();
    if(e.getName().endsWith(".class")) {
     ClassNode c=new ClassNode();new ClassReader(b).accept(c,0);classes++;
     boolean changed=false;
     if(c.visibleAnnotations!=null) {
      for(var iter=c.visibleAnnotations.iterator();iter.hasNext();) {
       AnnotationNode a=iter.next();
       if(a.desc.equals("Lnet/neoforged/fml/common/Mod;") && c.name.equals("com/replaymod/core/ReForgedPlayModStub")) {
        iter.remove();annotations++;changed=true;
        System.out.println("Unregistered empty mod stub: "+c.name);
       } else if((a.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;") || (c.name.equals("com/replaymod/core/ReplayModBackend") && a.desc.equals("Lnet/neoforged/fml/common/Mod;")))) {
        for(int k=0;k<a.values.size();k+=2) {
         String key=(String)a.values.get(k);
         if((key.equals("modid") || key.equals("value")) && "replaymod".equals(a.values.get(k+1))) {
          a.values.set(k+1,"reforgedplaymod");annotations++;changed=true;
          System.out.println("Registered "+c.name+": "+a.desc+" "+key);
         }
        }
       }
      }
     }
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
  if(annotations!=5)throw new Exception("Expected five registration changes, got "+annotations);
  System.out.println("Registration changes="+annotations);
  if(patched!=3)throw new Exception("Expected three patches, got "+patched);
  System.out.println("Parsed classes="+classes+"; patched lookup="+patched+"; verified methods="+methods);
 }
}
