import com.github.steveice10.netty.buffer.*;
import com.github.steveice10.opennbt.tag.builtin.*;
import com.github.steveice10.packetlib.tcp.io.*;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import com.replaymod.replaystudio.protocol.*;
import com.replaymod.replaystudio.protocol.packets.*;
import com.replaymod.replaystudio.protocol.registry.Registries;
import org.apache.commons.lang3.tuple.Pair;
import java.io.*;
import java.util.*;

/** Uses the actual bundled ReplayStudio, PacketLib, Netty and OpenNBT; no Minecraft/FML doubles. */
public class RegistryNbtTest {
    static final String REGISTRY="test:registry";
    static void require(boolean ok,String message) {if(!ok)throw new AssertionError(message);}

    static Packet packet(PacketTypeRegistry registry,List<Pair<String,Tag>> entries) throws IOException {
        Packet packet=new Packet(registry,PacketType.ConfigRegistries);
        try(Packet.Writer writer=packet.overwrite()) {
            writer.writeString(REGISTRY);
            writer.writeVarInt(entries.size());
            for(var entry:entries) {
                writer.writeString(entry.getKey());
                writer.writeBoolean(entry.getValue()!=null);
                if(entry.getValue()!=null)writer.writeNBT(entry.getValue());
            }
        }
        return packet;
    }

    static byte[] body(Packet packet) {
        byte[] bytes=new byte[packet.getBuf().readableBytes()];
        packet.getBuf().getBytes(packet.getBuf().readerIndex(),bytes);
        return bytes;
    }

    static void roundTrip(PacketTypeRegistry registry,List<Pair<String,Tag>> entries) throws Exception {
        Packet source=packet(registry,entries);
        try {
            Registries decoded=new Registries();
            PacketConfigRegistries.read(source,decoded);
            require(entries.equals(decoded.registriesMap.get(REGISTRY)),"Registry values changed");
            var rewritten=PacketConfigRegistries.write(registry,decoded);
            require(rewritten.size()==1,"Wrong rewritten packet count");
            try {require(Arrays.equals(body(source),body(rewritten.get(0))),"Packet bytes changed");}
            finally {rewritten.forEach(Packet::release);}

            decoded.enabledPacks=Collections.emptyList();
            ByteBuf cache=Unpooled.buffer();
            try {
                decoded.writeInternal(registry,new ByteBufNetOutput(cache));
                Registries restored=Registries.readInternal(registry,new ByteBufNetInput(cache));
                require(decoded.registriesMap.equals(restored.registriesMap),"Internal cache changed tags");
                require(!cache.isReadable(),"Internal cache left unread bytes");
            } finally {cache.release();}

            // Exercise the crash's real ReplayInputStream -> RegistriesBuilder -> packet decoder path.
            ByteBuf payload=Unpooled.buffer();
            byte[] streamBytes;
            try {
                new ByteBufNetOutput(payload).writeVarInt(source.getId());
                payload.writeBytes(body(source));
                ByteArrayOutputStream raw=new ByteArrayOutputStream();
                DataOutputStream stream=new DataOutputStream(raw);
                stream.writeInt(42);stream.writeInt(payload.readableBytes());
                byte[] data=new byte[payload.readableBytes()];payload.readBytes(data);stream.write(data);
                streamBytes=raw.toByteArray();
            } finally {payload.release();}
            try(ReplayInputStream input=new ReplayInputStream(registry,new ByteArrayInputStream(streamBytes),13,767)) {
                PacketData read=input.readPacket();
                require(read!=null && read.getPacket().getType()==PacketType.ConfigRegistries,"Replay stream lost registry packet");
                try {require(Arrays.equals(body(source),body(read.getPacket())),"Replay stream changed registry bytes");}
                finally {read.release();}
                require(input.readPacket()==null,"Unexpected extra replay packets");
            }
        } finally {source.release();}
    }

    static void enabledPackData(PacketTypeRegistry registry,List<Pair<String,Tag>> entries) throws Exception {
        Packet packet=new Packet(registry,PacketType.ConfigCustomPayload);
        try {
            try(Packet.Writer writer=packet.overwrite()) {
                writer.writeString(PacketEnabledPacksData.ID);
                writer.writeVarInt(1);writer.writeString(REGISTRY);
                writer.writeVarInt(entries.size());
                for(var entry:entries) {writer.writeString(entry.getKey());writer.writeNBT(entry.getValue());}
            }
            var actual=PacketEnabledPacksData.read(packet);
            Map<String,Tag> expected=new HashMap<>();entries.forEach(e->expected.put(e.getKey(),e.getValue()));
            require(Map.of(REGISTRY,expected).equals(actual),"Enabled-packs data changed tags");
        } finally {packet.release();}
    }

    static void rejectsInvalid(PacketTypeRegistry registry,byte[] invalid) throws Exception {
        Packet packet=new Packet(registry,PacketType.ConfigRegistries);
        try {
            try(Packet.Writer writer=packet.overwrite()) {
                writer.writeString(REGISTRY);writer.writeVarInt(1);writer.writeString("test:invalid");
                writer.writeBoolean(true);writer.writeBytes(invalid);
            }
            try {
                PacketConfigRegistries.read(packet,new Registries());
                throw new AssertionError("Malformed NBT was accepted");
            } catch(IOException | IndexOutOfBoundsException expected) {
                // PacketLib's ByteBufNetInput reports truncation as a buffer-bound exception.
                // Both errors reject the corrupt packet; neither may be silently accepted.
            }
        } finally {packet.release();}
    }

    public static void main(String[] args) {
        int result=0;
        try {runTests();} catch(Throwable error) {error.printStackTrace();result=1;}
        // The real ViaVersion mapping loader creates executor threads that outlive this standalone test.
        System.exit(result);
    }

    static void runTests() throws Exception {
        PacketTypeRegistry registry=PacketTypeRegistry.get(ProtocolVersion.getProtocol(767),State.CONFIGURATION);
        roundTrip(registry,List.of(Pair.of("test:compound",new CompoundTag())));
        System.out.println("PASS: compound registry packet, internal cache and ReplayInputStream.");
        roundTrip(registry,List.of(Pair.of("test:byte_array",new ByteArrayTag(new byte[]{7,0,10,-1}))));
        System.out.println("PASS: root type 7, byte-exact round trip through registry packet/cache/replay stream.");

        CompoundTag compound=new CompoundTag();compound.put("nested",new StringTag("данные"));
        List<Tag> values=Arrays.asList(new ByteTag((byte)-7),new ShortTag((short)1234),new IntTag(123456),
            new LongTag(123456789012L),new FloatTag(1.25f),new DoubleTag(2.5),
            new ByteArrayTag(new byte[]{0,7,10,-1}),new StringTag("данные"),
            new ListTag(List.of(new IntTag(1),new IntTag(2))),compound,
            new IntArrayTag(new int[]{-1,0,123456}),new LongArrayTag(new long[]{Long.MIN_VALUE,42}),
            new ListTag(),new CompoundTag(),new ByteArrayTag(new byte[0]));
        List<Pair<String,Tag>> entries=new ArrayList<>();
        for(int i=0;i<values.size();i++)entries.add(Pair.of("test:value_"+i,values.get(i)));
        roundTrip(registry,entries);
        enabledPackData(registry,entries);
        entries.add(Pair.of("test:absent",null));roundTrip(registry,entries);
        rejectsInvalid(registry,new byte[]{99});
        rejectsInvalid(registry,new byte[]{7,0,0,0,3,1});
        System.out.println("PASS: all 12 NBT root types, empty values, absent optional values, enabled-packs payload; malformed/truncated NBT rejected.");
    }
}
