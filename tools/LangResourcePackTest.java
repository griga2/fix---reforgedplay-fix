import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Executes the real pack against its JAR resources; Minecraft/FML APIs are dependency doubles. */
public class LangResourcePackTest {
    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path root = Files.createTempDirectory("replay-lang-test-");
        Map<String, String> sources = Map.ofEntries(
            Map.entry("net.minecraft.network.chat.Component", """
                package net.minecraft.network.chat;
                public interface Component {
                    static MutableComponent literal(String text) { return new MutableComponent(); }
                }
                """),
            Map.entry("net.minecraft.network.chat.MutableComponent", """
                package net.minecraft.network.chat;
                public class MutableComponent implements Component {}
                """),
            Map.entry("net.minecraft.server.packs.repository.PackSource", """
                package net.minecraft.server.packs.repository;
                public class PackSource { public static final PackSource DEFAULT = new PackSource(); }
                """),
            Map.entry("net.minecraft.server.packs.PackLocationInfo", """
                package net.minecraft.server.packs;
                public record PackLocationInfo(String id, net.minecraft.network.chat.Component title,
                    net.minecraft.server.packs.repository.PackSource source, java.util.Optional<?> knownPack) {}
                """),
            Map.entry("net.minecraft.server.packs.AbstractPackResources", """
                package net.minecraft.server.packs;
                public abstract class AbstractPackResources {
                    public AbstractPackResources(PackLocationInfo location) {}
                }
                """),
            Map.entry("net.minecraft.server.packs.PackType", """
                package net.minecraft.server.packs;
                public enum PackType {
                    CLIENT_RESOURCES("assets"), SERVER_DATA("data");
                    private final String directory;
                    PackType(String directory) {this.directory=directory;}
                    public String getDirectory() {return directory;}
                }
                """),
            Map.entry("net.minecraft.resources.ResourceLocation", """
                package net.minecraft.resources;
                public record ResourceLocation(String namespace, String path) {
                    public String getNamespace() {return namespace;}
                    public String getPath() {return path;}
                    public static ResourceLocation fromNamespaceAndPath(String ns, String path) {
                        return new ResourceLocation(ns,path);
                    }
                }
                """),
            Map.entry("net.minecraft.server.packs.resources.IoSupplier", """
                package net.minecraft.server.packs.resources;
                public interface IoSupplier<T> {T get() throws java.io.IOException;}
                """),
            Map.entry("net.minecraft.server.packs.PackResources", """
                package net.minecraft.server.packs;
                public interface PackResources {
                    interface ResourceOutput extends java.util.function.BiConsumer<
                        net.minecraft.resources.ResourceLocation,
                        net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream>> {}
                }
                """),
            Map.entry("cpw.mods.jarhandling.SecureJar", """
                package cpw.mods.jarhandling;
                public interface SecureJar {java.nio.file.Path getRootPath();}
                """),
            Map.entry("net.neoforged.fml.loading.moddiscovery.ModFile", """
                package net.neoforged.fml.loading.moddiscovery;
                public class ModFile {
                    public cpw.mods.jarhandling.SecureJar getSecureJar() {
                        return () -> net.neoforged.fml.loading.LoadingModList.root;
                    }
                }
                """),
            Map.entry("net.neoforged.fml.loading.moddiscovery.ModFileInfo", """
                package net.neoforged.fml.loading.moddiscovery;
                public class ModFileInfo {public ModFile getFile() {return new ModFile();}}
                """),
            Map.entry("net.neoforged.fml.loading.LoadingModList", """
                package net.neoforged.fml.loading;
                public class LoadingModList {
                    public static java.nio.file.Path root;
                    public static String lastId;
                    public static LoadingModList get() {return new LoadingModList();}
                    public net.neoforged.fml.loading.moddiscovery.ModFileInfo getModFileById(String id) {
                        lastId=id;
                        return "reforgedplaymod".equals(id)
                            ? new net.neoforged.fml.loading.moddiscovery.ModFileInfo() : null;
                    }
                }
                """)
        );
        List<String> compilerArgs = new ArrayList<>(List.of("-d", root.toString()));
        for (var source : sources.entrySet()) {
            Path file = root.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            compilerArgs.add(file.toString());
        }
        require(ToolProvider.getSystemJavaCompiler().run(null, null, null,
            compilerArgs.toArray(String[]::new)) == 0, "Dependency doubles did not compile");
        URL[] urls = {root.toUri().toURL(), jar.toUri().toURL(),
            Path.of(args[1]).toUri().toURL(), Path.of(args[2]).toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
             FileSystem fs = FileSystems.newFileSystem(jar)) {
            Class<?> loadingList = loader.loadClass("net.neoforged.fml.loading.LoadingModList");
            loadingList.getField("root").set(null, fs.getPath("/"));
            Class<?> packClass = loader.loadClass("com.replaymod.core.versions.LangResourcePack");
            Object pack = packClass.getConstructor().newInstance();
            require("reforgedplaymod".equals(loadingList.getField("lastId").get(null)), "Wrong container ID");
            Class<?> packType = loader.loadClass("net.minecraft.server.packs.PackType");
            Object client = packType.getField("CLIENT_RESOURCES").get(null);
            Object server = packType.getField("SERVER_DATA").get(null);
            Class<?> location = loader.loadClass("net.minecraft.resources.ResourceLocation");
            Method makeLocation = location.getMethod("fromNamespaceAndPath", String.class, String.class);
            Class<?> ioSupplier = loader.loadClass("net.minecraft.server.packs.resources.IoSupplier");
            Method getInput = ioSupplier.getMethod("get");
            Class<?> gsonClass = loader.loadClass("com.google.gson.Gson");
            Object gson = gsonClass.getConstructor().newInstance();
            Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
            Method getResource = packClass.getMethod("getResource", packType, location);
            Map<String, Map<?, ?>> expected = new TreeMap<>();
            try (var paths = Files.list(fs.getPath("/assets/replaymod/lang"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".lang")).toList()) {
                    Map<String, String> entries = new TreeMap<>();
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                        int split = line.indexOf('=');
                        require(split >= 0, "Invalid source translation: " + path);
                        String key = line.substring(0, split), value = line.substring(split + 1);
                        entries.put(key, value);
                        if (key.startsWith("replaymod.input.")) {
                            entries.put("key.replaymod." + key.substring("replaymod.input.".length()), value);
                        }
                    }
                    String name = "lang/" + path.getFileName().toString()
                        .replace(".lang", ".json").toLowerCase(Locale.ROOT);
                    Object resource = makeLocation.invoke(null, "replaymod", name);
                    Object supplier = getResource.invoke(pack, client, resource);
                    require(supplier != null, "Missing translation " + name);
                    try (InputStream input = (InputStream) getInput.invoke(supplier)) {
                        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        Map<?, ?> decoded = (Map<?, ?>) fromJson.invoke(gson, text, Map.class);
                        require(entries.equals(decoded), "Translation contents differ: " + name);
                        expected.put(name, decoded);
                    }
                }
            }
            require(expected.size() == 24, "Expected 24 locale files, got " + expected.size());
            System.out.println("PASS: constructor, correct container ID and direct reads of all 24 locales, including key aliases.");
            Method namespaces = packClass.getMethod("getNamespaces", packType);
            require(Set.of("replaymod").equals(namespaces.invoke(pack, client)), "Wrong client namespace");
            require(Set.of().equals(namespaces.invoke(pack, server)), "Unexpected server namespace");
            Class<?> output = loader.loadClass("net.minecraft.server.packs.PackResources$ResourceOutput");
            Map<String, Object> listed = new TreeMap<>();
            Object callback = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{output},
                (proxy, method, values) -> {
                    if (method.getName().equals("accept")) {
                        String namespace = (String) location.getMethod("getNamespace").invoke(values[0]);
                        require(namespace.equals("replaymod"), "Wrong listed namespace");
                        String path = (String) location.getMethod("getPath").invoke(values[0]);
                        require(listed.put(path, values[1]) == null, "Duplicate listed path: " + path);
                    }
                    return null;
                });
            packClass.getMethod("listResources", packType, String.class, String.class, output)
                .invoke(pack, client, "replaymod", "lang", callback);
            require(listed.keySet().equals(expected.keySet()), "Listed locales differ: " + listed.keySet());
            for (var resource : listed.entrySet()) {
                try (InputStream input = (InputStream) getInput.invoke(resource.getValue())) {
                    Map<?, ?> decoded = (Map<?, ?>) fromJson.invoke(gson,
                        new String(input.readAllBytes(), StandardCharsets.UTF_8), Map.class);
                    require(expected.get(resource.getKey()).equals(decoded), "Listed supplier differs: " + resource.getKey());
                }
            }
            System.out.println("PASS: enumeration and deferred reading of all 24 locales (dependency doubles; not a Minecraft launch).");
        }
    }
}
