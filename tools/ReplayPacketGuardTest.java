import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;

/** Executes the actual injected handler with real Netty; Minecraft/CallbackInfo are test doubles. */
public class ReplayPacketGuardTest {
    static final String MIXIN="com/replaymod/replay/mixin/Mixin_SuppressReplayClientPackets";
    static void require(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    static Map<String,Object> values(AnnotationNode a) {
        Map<String,Object> result=new HashMap<>();
        for(int i=0;a.values!=null && i<a.values.size();i+=2)result.put((String)a.values.get(i),a.values.get(i+1));
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path jar=Path.of(args[0]).toAbsolutePath();String marker;
        try(ZipFile zip=new ZipFile(jar.toFile())) {
            require(new String(zip.getInputStream(zip.getEntry("mixins.replay.replaymod.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"Mixin_SuppressReplayClientPackets\""),"Mixin not registered");
            ClassNode c=new ClassNode();new ClassReader(zip.getInputStream(zip.getEntry(MIXIN+".class")).readAllBytes()).accept(c,0);
            AnnotationNode annotation=c.invisibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            Map<String,Object> mixin=values(annotation);
            require(List.of(Type.getObjectType("net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl")).equals(mixin.get("value")),"Wrong target listener");
            MethodNode method=c.methods.stream().filter(m->m.name.equals("reforgedplayfix$dropReplayOutgoing")).findFirst().orElseThrow();
            Map<String,Object> inject=values(method.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow());
            require(List.of("send(Lnet/minecraft/network/protocol/Packet;)V").equals(inject.get("method")),"Wrong target send descriptor");
            require(Boolean.TRUE.equals(inject.get("cancellable")) && Integer.valueOf(1).equals(inject.get("require")),"Injection must be cancellable and required");
            require("HEAD".equals(values((AnnotationNode)((List<?>)inject.get("at")).get(0)).get("value")),"Must run before NeoForge validation");
            ClassNode replay=new ClassNode();new ClassReader(zip.getInputStream(zip.getEntry("com/replaymod/replay/ReplayHandler.class")).readAllBytes()).accept(replay,0);
            marker=(String)replay.fields.stream().filter(f->f.name.equals("PACKET_HANDLER_NAME")).findFirst().orElseThrow().value;
            require("ReplayModReplay_packetHandler".equals(marker),"Replay pipeline marker changed");
        }
        Path root=Files.createTempDirectory("replay-packet-guard-");
        Map<String,String> sources=Map.of(
            "net.minecraft.network.protocol.Packet","""
                package net.minecraft.network.protocol;
                public interface Packet<T> {String id(); boolean negotiated();}
                """,
            "net.minecraft.network.Connection","""
                package net.minecraft.network;
                public class Connection implements com.replaymod.recording.mixin.NetworkManagerAccessor {
                    public final io.netty.channel.Channel channel;
                    public Connection(io.netty.channel.Channel channel){this.channel=channel;}
                    public io.netty.channel.Channel getChannel(){return channel;}
                }
                """,
            "org.spongepowered.asm.mixin.injection.callback.CallbackInfo","""
                package org.spongepowered.asm.mixin.injection.callback;
                public class CallbackInfo {
                    private final boolean cancellable;private boolean cancelled;
                    public CallbackInfo(String name,boolean cancellable){this.cancellable=cancellable;}
                    public void cancel(){if(!cancellable)throw new IllegalStateException();cancelled=true;}
                    public boolean isCancelled(){return cancelled;}
                }
                """,
            "fixture.Probe","""
                package fixture;
                import java.lang.reflect.*;
                import io.netty.channel.*;
                import io.netty.channel.embedded.EmbeddedChannel;
                import io.netty.channel.local.LocalChannel;
                import net.minecraft.network.Connection;
                import net.minecraft.network.protocol.Packet;
                import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
                import com.replaymod.replay.mixin.Mixin_SuppressReplayClientPackets;
                public class Probe extends Mixin_SuppressReplayClientPackets {
                    static final String MARKER="ReplayModReplay_packetHandler";
                    static final Packet<?> FLYING=new FixturePacket("create_sa:key_flying",false);
                    static final Packet<?> ALLOWED=new FixturePacket("other:negotiated",true);
                    static final Packet<?> VANILLA=new FixturePacket("minecraft:move",true);
                    int checked,sent;
                    public Probe(Channel channel){this.connection=new Connection(channel);}
                    record FixturePacket(String id,boolean negotiated) implements Packet<Object>{}
                    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
                    void send(Packet<?> packet) throws Exception {
                        CallbackInfo callback=new CallbackInfo("send",true);
                        Method hook=Mixin_SuppressReplayClientPackets.class.getDeclaredMethod(
                            "reforgedplayfix$dropReplayOutgoing",Packet.class,CallbackInfo.class);
                        hook.setAccessible(true);hook.invoke(this,packet,callback);
                        if(callback.isCancelled())return;
                        // Same ordering as the NeoForge-patched listener: validate, then Connection.send.
                        checked++;
                        if(!packet.negotiated())throw new UnsupportedOperationException(
                            "Payload "+packet.id()+" may not be sent to the server!");
                        sent++;
                    }
                    void mustRejectUnnegotiated() throws Exception {
                        try{send(FLYING);throw new AssertionError("Validation bypassed on a real/non-replay connection");}
                        catch(UnsupportedOperationException expected){require(expected.getMessage().contains("create_sa:key_flying"),"Unexpected error");}
                    }
                    public static void run() throws Exception {
                        Probe startup=new Probe(null);startup.send(ALLOWED);startup.mustRejectUnnegotiated();
                        require(startup.sent==1 && startup.checked==2,"Startup/queued sends changed");
                        LocalChannel server=new LocalChannel();
                        try{
                            // Even a matching name on a non-EmbeddedChannel must not disable server validation.
                            server.pipeline().addLast(MARKER,new ChannelInboundHandlerAdapter());
                            Probe normal=new Probe(server);normal.send(ALLOWED);normal.send(VANILLA);normal.mustRejectUnnegotiated();
                            require(normal.sent==2 && normal.checked==3,"Multiplayer/recording sends changed");
                        }finally{server.unsafe().closeForcibly();}
                        EmbeddedChannel channel=new EmbeddedChannel();
                        try{
                            Probe replay=new Probe(channel);
                            replay.send(ALLOWED);replay.mustRejectUnnegotiated();
                            require(replay.sent==1 && replay.checked==2,"Unmarked EmbeddedChannel changed");
                            replay.sent=0;replay.checked=0;
                            channel.pipeline().addLast(MARKER,new FullReplayHandler());
                            replay.send(FLYING);replay.send(ALLOWED);replay.send(VANILLA);
                            require(replay.checked==0 && replay.sent==0,"Full replay reached validation/transport");
                            channel.pipeline().replace(MARKER,MARKER,new QuickReplayHandler());
                            replay.send(FLYING);
                            require(replay.checked==0 && replay.sent==0,"Quick replay reached validation/transport");
                            channel.pipeline().remove(MARKER);
                            replay.send(ALLOWED);replay.mustRejectUnnegotiated();
                            require(replay.checked==2 && replay.sent==1,"Validation not restored after replay marker removal");
                        }finally{channel.finishAndReleaseAll();}
                    }
                    static class FullReplayHandler extends ChannelInboundHandlerAdapter{}
                    static class QuickReplayHandler extends ChannelInboundHandlerAdapter{}
                }
                """
        );
        List<URL> urls=new ArrayList<>();urls.add(root.toUri().toURL());urls.add(jar.toUri().toURL());
        List<String> classpath=new ArrayList<>();classpath.add(jar.toString());
        for(int i=1;i<args.length;i++){Path p=Path.of(args[i]).toAbsolutePath();urls.add(p.toUri().toURL());classpath.add(p.toString());}
        List<String> compilerArgs=new ArrayList<>(List.of("-proc:none","-classpath",String.join(java.io.File.pathSeparator,classpath),"-d",root.toString()));
        for(var source:sources.entrySet()){
            Path path=root.resolve(source.getKey().replace('.','/')+".java");Files.createDirectories(path.getParent());
            Files.writeString(path,source.getValue());compilerArgs.add(path.toString());
        }
        require(ToolProvider.getSystemJavaCompiler().run(null,null,null,compilerArgs.toArray(String[]::new))==0,"Fixture compilation failed");
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())) {
            try{loader.loadClass("fixture.Probe").getMethod("run").invoke(null);}
            catch(java.lang.reflect.InvocationTargetException error){throw new AssertionError("Packet guard behavior failed",error.getCause());}
        }
        System.out.println("PASS: required HEAD injector metadata; full/quick replay packets cancelled before validation; multiplayer, recording, startup and unmarked channels retain validation. Real Netty, dependency doubles; Mixin transformation not executed.");
    }
}
