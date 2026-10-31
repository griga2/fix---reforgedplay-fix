import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
public class LifecycleTest {
 public static void main(String[] args) throws Exception {
  Path jar=Path.of(args[0]).toAbsolutePath();int mods=0,subscribers=0;
  try(ZipFile z=new ZipFile(jar.toFile())) {
   for(var es=z.entries();es.hasMoreElements();) {
    var e=es.nextElement();if(!e.getName().endsWith(".class"))continue;
    ClassNode c=new ClassNode();new ClassReader(z.getInputStream(e).readAllBytes()).accept(c,ClassReader.SKIP_CODE);
    if(c.visibleAnnotations==null)continue;
    for(AnnotationNode a:c.visibleAnnotations) {
     if(a.desc.equals("Lnet/neoforged/fml/common/Mod;")) {
      if(!c.name.equals("com/replaymod/core/ReplayModBackend") || !a.values.equals(List.of("value","reforgedplaymod")))throw new AssertionError("Unexpected entrypoint "+c.name);
      mods++;
     }
     if(a.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;") && a.values!=null) {
      for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals("modid")) {
       if(a.values.get(i+1).equals("replaymod"))throw new AssertionError("Stale event subscriber "+c.name);
       if(c.name.equals("com/replaymod/core/ReplayModBackend") && a.values.get(i+1).equals("reforgedplaymod"))subscribers++;
      }
     }
    }
    if(c.name.equals("com/replaymod/core/ReplayModBackend")) {
     MethodNode m=c.methods.stream().filter(x->x.name.equals("construct")).findFirst().orElseThrow();
     if(m.visibleAnnotations==null || m.visibleAnnotations.stream().noneMatch(a->a.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;")))throw new AssertionError("Missing event subscription");
    }
   }
  }
  if(mods!=1 || subscribers!=1)throw new AssertionError("Registration counts "+mods+" "+subscribers);
  // Exercise the real backend constructor and event method, with isolated dependency doubles.
  Path root=Files.createTempDirectory("replay-lifecycle-");
  Path core=root.resolve("com/replaymod/core/ReplayMod.java");Files.createDirectories(core.getParent());
  Files.writeString(core,"package com.replaymod.core; public class ReplayMod { public static ReplayMod instance; public static int initialized; public ReplayMod(ReplayModBackend backend) {instance=this;} void initModules(){initialized++;} }");
  Path event=root.resolve("net/neoforged/fml/event/lifecycle/FMLConstructModEvent.java");Files.createDirectories(event.getParent());
  Files.writeString(event,"package net.neoforged.fml.event.lifecycle; public class FMLConstructModEvent {}");
  int result=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-classpath",jar.toString(),"-d",root.toString(),core.toString(),event.toString());
  if(result!=0)throw new AssertionError("Stub compilation failed");
  try(URLClassLoader loader=new URLClassLoader(new URL[]{root.toUri().toURL(),jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
   Class<?> backend=loader.loadClass("com.replaymod.core.ReplayModBackend");
   backend.getConstructor().newInstance();
   Class<?> replay=loader.loadClass("com.replaymod.core.ReplayMod");
   if(replay.getField("instance").get(null)==null)throw new AssertionError("Core not constructed");
   Class<?> ev=loader.loadClass("net.neoforged.fml.event.lifecycle.FMLConstructModEvent");
   backend.getMethod("construct",ev).invoke(null,ev.getConstructor().newInstance());
   if(replay.getField("initialized").getInt(null)!=1)throw new AssertionError("Modules initialization was not called exactly once");
  }
  System.out.println("PASS: one real mod entrypoint; matching event subscriber; backend constructs core and invokes module initialization (dependency doubles, not a NeoForge runtime test).");
 }
}
