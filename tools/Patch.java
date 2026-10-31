import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.tree.analysis.*;
public class Patch {
 static final String PACKET_MIXIN="com/replaymod/replay/mixin/Mixin_SuppressReplayClientPackets";
 static final String REPLAY_PIPELINE="ReplayModReplay_packetHandler";

 static byte[] createPacketMixin() {
  ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
  w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_SUPER,PACKET_MIXIN,null,"java/lang/Object",null);
  AnnotationVisitor mixin=w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;",false);
  AnnotationVisitor targets=mixin.visitArray("value");
  targets.visit(null,Type.getObjectType("net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl"));targets.visitEnd();
  mixin.visit("remap",false);mixin.visitEnd();
  FieldVisitor connection=w.visitField(Opcodes.ACC_PROTECTED,"connection","Lnet/minecraft/network/Connection;",null,null);
  connection.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;",true).visitEnd();
  connection.visitAnnotation("Lorg/spongepowered/asm/mixin/Final;",true).visitEnd();connection.visitEnd();
  MethodVisitor ctor=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
  ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);
  ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
  ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();

  MethodVisitor send=w.visitMethod(Opcodes.ACC_PRIVATE,"reforgedplayfix$dropReplayOutgoing",
   "(Lnet/minecraft/network/protocol/Packet;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",null,null);
  AnnotationVisitor inject=send.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;",true);
  AnnotationVisitor methods=inject.visitArray("method");
  methods.visit(null,"send(Lnet/minecraft/network/protocol/Packet;)V");methods.visitEnd();
  AnnotationVisitor ats=inject.visitArray("at");
  AnnotationVisitor head=ats.visitAnnotation(null,"Lorg/spongepowered/asm/mixin/injection/At;");
  head.visit("value","HEAD");head.visitEnd();ats.visitEnd();
  inject.visit("cancellable",true);inject.visit("require",1);inject.visit("remap",false);inject.visitEnd();
  send.visitCode();send.visitVarInsn(Opcodes.ALOAD,0);
  send.visitFieldInsn(Opcodes.GETFIELD,PACKET_MIXIN,"connection","Lnet/minecraft/network/Connection;");
  send.visitTypeInsn(Opcodes.CHECKCAST,"com/replaymod/recording/mixin/NetworkManagerAccessor");
  send.visitMethodInsn(Opcodes.INVOKEINTERFACE,"com/replaymod/recording/mixin/NetworkManagerAccessor","getChannel","()Lio/netty/channel/Channel;",true);
  send.visitVarInsn(Opcodes.ASTORE,3);
  Label done=new Label();
  send.visitVarInsn(Opcodes.ALOAD,3);send.visitTypeInsn(Opcodes.INSTANCEOF,"io/netty/channel/embedded/EmbeddedChannel");
  send.visitJumpInsn(Opcodes.IFEQ,done);
  send.visitVarInsn(Opcodes.ALOAD,3);
  send.visitMethodInsn(Opcodes.INVOKEINTERFACE,"io/netty/channel/Channel","pipeline","()Lio/netty/channel/ChannelPipeline;",true);
  send.visitLdcInsn(REPLAY_PIPELINE);
  send.visitMethodInsn(Opcodes.INVOKEINTERFACE,"io/netty/channel/ChannelPipeline","get","(Ljava/lang/String;)Lio/netty/channel/ChannelHandler;",true);
  send.visitJumpInsn(Opcodes.IFNULL,done);
  send.visitVarInsn(Opcodes.ALOAD,2);
  send.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"org/spongepowered/asm/mixin/injection/callback/CallbackInfo","cancel","()V",false);
  send.visitLabel(done);send.visitInsn(Opcodes.RETURN);send.visitMaxs(0,0);send.visitEnd();w.visitEnd();
  return w.toByteArray();
 }
 static final String READER="com/replaymod/replaystudio/protocol/Packet$Reader";
 static final String COMPOUND="Lcom/github/steveice10/opennbt/tag/builtin/CompoundTag;";
 static final String TAG="Lcom/github/steveice10/opennbt/tag/builtin/Tag;";

 static MethodNode compileTagDecoder(Path input) throws Exception {
  Path dir=Files.createTempDirectory("replay-tag-decoder-");
  Path dependency=dir.resolve("opennbt.jar");
  try(ZipFile zip=new ZipFile(input.toFile())) {
   Files.write(dependency,zip.getInputStream(zip.getEntry("META-INF/jars/opennbt-0a02214.jar")).readAllBytes());
  }
  Path source=dir.resolve("RegistryTagDecoder.java");
  Files.writeString(source,"""
   import java.io.*;
   import com.github.steveice10.opennbt.tag.TagRegistry;
   import com.github.steveice10.opennbt.tag.TagCreateException;
   import com.github.steveice10.opennbt.tag.builtin.Tag;
   final class RegistryTagDecoder {
    private static Tag readAnyTag(InputStream input) throws IOException {
     DataInputStream data=new DataInputStream(input);
     int type=data.readUnsignedByte();
     if(type==0)return null;
     data.readUTF(); // Reader$1 supplies an empty name for the 1.20.2+ network format.
     Tag tag;
     try {tag=TagRegistry.createInstance(type);}
     catch(TagCreateException error) {throw new IOException("Failed to create registry NBT tag",error);}
     tag.read(data);
     return tag;
    }
   }
   """);
  if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",dependency.toString(),"-d",dir.toString(),source.toString())!=0)
   throw new IllegalStateException("Could not compile generic NBT decoder");
  ClassNode helper=new ClassNode();new ClassReader(Files.readAllBytes(dir.resolve("RegistryTagDecoder.class"))).accept(helper,0);
  return helper.methods.stream().filter(m->m.name.equals("readAnyTag")).findFirst().orElseThrow();
 }

 static void addGenericNbtReader(ClassNode c,MethodNode decoder) {
  List<MethodNode> added=new ArrayList<>();
  for(MethodNode m:c.methods)if(m.name.equals("readNBT")) {
   MethodNode copy=new MethodNode(m.access,m.name,m.desc,m.signature,m.exceptions.toArray(String[]::new));m.accept(copy);
   copy.name="readAnyNBT";copy.desc=m.desc.replace(COMPOUND,TAG);
   int nbtioCalls=0;
   for(AbstractInsnNode ins:copy.instructions.toArray())if(ins instanceof MethodInsnNode call) {
    if(call.owner.equals(READER) && call.name.equals("readNBT")) {
     call.name="readAnyNBT";call.desc=call.desc.replace(COMPOUND,TAG);
    } else if(call.owner.equals("com/github/steveice10/opennbt/NBTIO") && call.name.equals("readTag") && ++nbtioCalls==1) {
     call.owner=READER;call.name="readAnyTag";call.desc="(Ljava/io/InputStream;)"+TAG;
    }
   }
   added.add(copy);
  }
  if(added.size()!=2)throw new IllegalStateException("Expected two NBT reader overloads");
  c.methods.addAll(added);c.methods.add(decoder);
 }

 static int patchRegistryCallsites(ClassNode c) {
  String targetMethod=switch(c.name) {
   case "com/replaymod/replaystudio/protocol/packets/PacketConfigRegistries" -> "lambda$read$0";
   case "com/replaymod/replaystudio/protocol/packets/PacketEnabledPacksData" -> "lambda$null$0";
   case "com/replaymod/replaystudio/protocol/registry/Registries" -> "lambda$null$3";
   default -> "";
  };
  int count=0;
  for(MethodNode m:c.methods)if(m.name.equals(targetMethod)) {
   for(AbstractInsnNode ins:m.instructions.toArray()) {
    if(ins instanceof MethodInsnNode call && call.owner.equals(READER) && call.name.equals("readNBT")) {
     call.name="readAnyNBT";call.desc=call.desc.replace(COMPOUND,TAG);count++;
    }
    if(ins instanceof FrameNode frame) {
     // Conditional optional-tag reads now merge Tag with null, rather than CompoundTag with null.
     for(List<Object> types:Arrays.asList(frame.local,frame.stack))if(types!=null)
      for(int i=0;i<types.size();i++)if("com/github/steveice10/opennbt/tag/builtin/CompoundTag".equals(types.get(i)))
       types.set(i,"com/github/steveice10/opennbt/tag/builtin/Tag");
    }
   }
  }
  return count;
 }
 static int patchLanguageEnumeration(ClassNode c) {
  int changes=0;
  for(MethodNode m:c.methods) {
   int groups=0;
   for(AbstractInsnNode ins:m.instructions.toArray()) {
    if(m.name.equals("<clinit>") && ins instanceof LdcInsnNode l &&
       "^([a-z][a-z])_([a-z][a-z]).lang$".equals(l.cst)) {
     l.cst="^([a-z][a-z])_([a-zA-Z][a-zA-Z])\\.lang$";changes++;
    }
    if(m.name.equals("lambda$findResources$4") && ins instanceof MethodInsnNode call &&
       call.owner.equals("java/util/regex/Matcher") && call.name.equals("group")) {
     if(++groups==2) {
      if(ins.getPrevious().getOpcode()!=Opcodes.ICONST_1)throw new IllegalStateException("Unexpected locale group");
      m.instructions.set(ins.getPrevious(),new InsnNode(Opcodes.ICONST_2));
      InsnList normalize=new InsnList();
      normalize.add(new FieldInsnNode(Opcodes.GETSTATIC,"java/util/Locale","ROOT","Ljava/util/Locale;"));
      normalize.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/String","toLowerCase","(Ljava/util/Locale;)Ljava/lang/String;",false));
      m.instructions.insert(ins,normalize);changes++;
     }
    }
    if(m.name.equals("lambda$findResources$1") && ins instanceof MethodInsnNode call &&
       call.owner.equals(c.name) && call.name.equals("readFile")) {
     InsnList prefix=new InsnList();
     prefix.add(new LdcInsnNode("assets/replaymod/"));
     prefix.add(new InsnNode(Opcodes.SWAP));
     prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;",false));
     m.instructions.insertBefore(ins,prefix);changes++;
    }
   }
  }
  return changes;
 }
 public static void main(String[] args) throws Exception {
  int patched=0, classes=0, methods=0, annotations=0, resourcePatches=0, nbtCallsites=0, nbtReaders=0, networkConfigs=0;
  MethodNode tagDecoder=compileTagDecoder(Path.of(args[0]));
  try(ZipFile in=new ZipFile(args[0]); ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
   Set<String> names=new HashSet<>();
   for(var entries=in.entries();entries.hasMoreElements();) {
    ZipEntry e=entries.nextElement();if(!names.add(e.getName()))throw new Exception("Duplicate "+e.getName());
    byte[] b=in.getInputStream(e).readAllBytes();
    if(e.getName().equals("mixins.replay.replaymod.json")) {
     String config=new String(b,java.nio.charset.StandardCharsets.UTF_8);
     if(config.indexOf("\"Mixin_ScreenButton\"")!=config.lastIndexOf("\"Mixin_ScreenButton\""))
      throw new IllegalStateException("Ambiguous replay mixin config");
     String amended=config.replace("\"Mixin_ScreenButton\"","\"Mixin_ScreenButton\",\n    \"Mixin_SuppressReplayClientPackets\"");
     if(amended.equals(config))throw new IllegalStateException("Unexpected replay mixin config");
     b=amended.getBytes(java.nio.charset.StandardCharsets.UTF_8);networkConfigs++;
    }
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
     if(c.name.equals("com/replaymod/core/versions/LangResourcePack")) {
      resourcePatches=patchLanguageEnumeration(c);
      changed|=resourcePatches>0;
     }
     if(c.name.equals(READER)) {addGenericNbtReader(c,tagDecoder);nbtReaders++;changed=true;}
     int registryChanges=patchRegistryCallsites(c);
     nbtCallsites+=registryChanges;changed|=registryChanges>0;
     if(changed) {
      ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);c.accept(w);b=w.toByteArray();
      ClassNode reread=new ClassNode();new ClassReader(b).accept(reread,ClassReader.EXPAND_FRAMES);
      for(MethodNode m:reread.methods)if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0){new Analyzer<BasicValue>(new BasicVerifier()).analyze(reread.name,m);methods++;}
     }
    }
    ZipEntry target=new ZipEntry(e.getName());target.setTime(e.getTime());out.putNextEntry(target);out.write(b);out.closeEntry();
   }
   String path=PACKET_MIXIN+".class";
   if(!names.add(path))throw new IllegalStateException("Packet mixin already exists");
   byte[] bytes=createPacketMixin();
   ClassNode generated=new ClassNode();new ClassReader(bytes).accept(generated,ClassReader.EXPAND_FRAMES);
   for(MethodNode m:generated.methods)new Analyzer<BasicValue>(new BasicVerifier()).analyze(generated.name,m);
   ZipEntry added=new ZipEntry(path);added.setTime(315532800000L);out.putNextEntry(added);out.write(bytes);out.closeEntry();
  }
  if(annotations!=5)throw new Exception("Expected five registration changes, got "+annotations);
  if(resourcePatches!=3)throw new Exception("Expected three language enumeration fixes, got "+resourcePatches);
  if(nbtReaders!=1 || nbtCallsites!=3)throw new Exception("Unexpected NBT patch counts: "+nbtReaders+" / "+nbtCallsites);
  if(networkConfigs!=1)throw new Exception("Expected one replay mixin configuration");
  System.out.println("Registration changes="+annotations);
  if(patched!=3)throw new Exception("Expected three patches, got "+patched);
  System.out.println("Parsed classes="+classes+"; patched lookup="+patched+"; verified methods="+methods);
 }
}
